"""Client lifecycle checks for the redesigned player (2026-10-21), in Chromium against the running development backend.
Each check is an independent async function with its own player; the run prints pass/fail per check and exits non-zero
on any failure (an uncaught page error fails its check too). Hidden prices come only from qa/oracle.py (test-only).

  checkpoint_reset     a checkpoint due when the run is left does not block «نشان بده» in the next run
  stale_products       a late «کالاهای دست قبلی» response does not change the screen of a newer run
  replay_clock         an idempotent replay does not move the server clock estimate
  body_timeout         a stalled response body times out; a malformed success is an error
  offer_per_miss       with a continue cap of 2 (temporary admin config), the second miss gets its own offer
  copy_state           the campaign-code copy feedback on the loss screen follows the clipboard result
  quota_reset          chances 0: disabled start with a ticking countdown; at the reset only the server's answer enables it
  cp_once (T13)        celebration mounted once per (run, checkpoint): re-renders, reconnect, recovery, reload never replay
                       it; «ادامه بده» ignores the first 600 ms; no tap-anywhere, no automatic continue
  zero_tap (T05/T06)   a pick at the visible zero is not sent; the server records a timeout, never an early loss
  pick_before_zero     a pick just before zero with a slow response stays pending and is settled by the server
  pick_vs_expiry       a pick and the visible zero in the same instant make exactly one transition
  daily_double_tap     two taps on «دریافت کن» send one claim and close the modal straight to the home
  loss_home (T18)      «صفحه اصلی» / device Back on the offer declines it once and opens the home with no decision modal
  purchase_lost        a lost purchase response is reconciled: one key, one 5-coin debit, the same run continues
  purchase_reconcile   a run read while the purchase is in flight keeps it pending
  pay_failed_retry     (T17) rejected charge → G06.FAILED_RETRY, nothing debited; retry succeeds once; next miss LIMIT_USED
  pay_failed_end       (T17) rejected charge, offer window runs out → G06.FAILED_END, nothing debited, home works
  pay_unknown_applied  (T17) debit applied, response lost → confirmed, same run, one debit
  pay_late_success     (T17) confirmation 12 s late → G06.UNKNOWN, then success with one debit
  pay_not_applied      (T17) provider lost the request, nothing applied → the server re-sends it: success, one debit
  pay_refunded         (T17) confirmation after resolve-by → G06.FAILED_END.UNCONFIRMED → G06.REFUNDED, money back (~3 min)
  offline_refresh (T07) offline and a reload during a question keep its deadline; the result is shown once
  light_only           a dark system preference cannot switch the game to dark

usage: python3 qa/lifecycle.py [check ...]"""
import asyncio, json, os, sys, time, uuid, urllib.request
from playwright.async_api import async_playwright
sys.path.insert(0, os.path.dirname(__file__))
import oracle

BASE = os.environ.get("PE_BASE", "http://127.0.0.1:8080")
A = "/admin/v1/gamification/price-expert"
FA = str.maketrans("۰۱۲۳۴۵۶۷۸۹", "0123456789")
PAGES = []          # pages of the running check: closed and checked for page errors after it


def http(method, path, body=None, headers=None):
    req = urllib.request.Request(BASE + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", **(headers or {})})
    return json.loads(urllib.request.urlopen(req).read() or b"{}")


def dev(path, body=None): return http("GET" if body is None else "POST", "/dev/v1/price-expert" + path, body)
def coin(u): return dev(f"/fake-coin/u_{u}")["balance"]
def api(u, method, path, body=None):
    """The player API with a dev token, for set-up only (spending the day's chances)."""
    return http(method, "/v1/gamification/price-expert" + path, body if body is not None or method == "GET" else {},
                {"Authorization": "Bearer dev." + u, "X-Device-Id": str(uuid.uuid5(uuid.NAMESPACE_URL, u)), "Idempotency-Key": str(uuid.uuid4())})


_tok = {}
def admin(method, path, body=None):
    if "t" not in _tok:
        _tok["t"] = http("POST", A + "/session", {"username": "editor", "password": "editor-dev-password"})["token"]
    return http(method, A + path, body, {"Authorization": "Bearer " + _tok["t"], "Idempotency-Key": str(uuid.uuid4())})


async def view(pg): return await pg.evaluate("document.getElementById('app').dataset.view")


async def wait_view(pg, prefixes, timeout=12000):
    prefixes = prefixes if isinstance(prefixes, tuple) else (prefixes,)
    t = time.time()
    while (time.time() - t) * 1000 < timeout:
        v = await view(pg)
        if v and v.startswith(prefixes): return v
        await pg.wait_for_timeout(40)
    raise AssertionError(f"timeout waiting for {prefixes}, at {await view(pg)}")


MODULES = "async () => { window.G = await import('/play/src/game.js'); window.API = await import('/play/src/api.js'); window.CLK = await import('/play/src/clock.js'); }"
# every data-view the player passes through, with the server time it was entered
RECORD = """() => { window.__vs = []; const app = document.getElementById('app'), push = () => { const v = app.dataset.view; if (!__vs.length || __vs[__vs.length - 1].v !== v) __vs.push({ v, t: CLK.serverNow() }); };
  push(); new MutationObserver(push).observe(app, { attributes: true, attributeFilter: ['data-view'] }); }"""


async def page(b, u=None, balance=120, scheme="light", onboarded=True, attended=None, close_daily=True, device=None):
    u = u or "lc" + uuid.uuid4().hex[:6]
    dev(f"/fake-coin/u_{u}", {"balance": balance})
    dev(f"/player/u_{u}", {"onboarded": onboarded, **({"attended_days": attended} if attended is not None else {})})
    ctx = await b.new_context(viewport={"width": 390, "height": 844}, color_scheme=scheme)
    pg = await ctx.new_page()
    pg.u, pg.errors = u, []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    PAGES.append(pg)
    await pg.goto(f"{BASE}/play/dev.html?user={u}&panel=0" + (f"&device={device}" if device else ""))
    v = await wait_view(pg, ("G01.", "G02.", "G11."))
    if v.startswith("G01.DAILY_REWARD") and close_daily:
        await tap(pg, "daily-close"); await wait_view(pg, ("G01.READY", "G01.QUOTA"))
    await pg.evaluate(MODULES)
    return pg


async def tap(pg, act): await pg.click(f'[data-act="{act}"]')
async def answer(pg, correct): await tap(pg, "ans-" + oracle.choice("u_" + pg.u, correct))


async def start(pg):
    await tap(pg, "start"); await wait_view(pg, "G03.READY")


async def reveal(pg):
    await wait_view(pg, ("G03.READY", "G06.SUCCESS")); await pg.wait_for_timeout(650)
    await tap(pg, "reveal"); await wait_view(pg, "G03.QUESTION_ACTIVE"); await pg.wait_for_timeout(350)


async def at_home(pg, timeout=12000):
    """The home; today's first finished run earns the daily reward, whose modal is closed here."""
    v = await wait_view(pg, ("G01.READY", "G01.QUOTA", "G01.DAILY_REWARD"), timeout)
    if v.startswith("G01.DAILY_REWARD"):
        await tap(pg, "daily-close"); v = await wait_view(pg, ("G01.READY", "G01.QUOTA"), timeout)
    return v


async def miss_to_offer(pg, started=False):
    if not started: await start(pg)
    await reveal(pg); await answer(pg, False)
    await wait_view(pg, "G05.OFFER", 9000)
    return await pg.evaluate("G.S.run.runId")


async def correct_n(pg, n):
    """Answers n questions correctly from READY; stops on the celebration of the n-th."""
    for i in range(n):
        await reveal(pg); await answer(pg, True)
        await wait_view(pg, ("G03.READY", "G04.REACHED"), 9000)
        await pg.wait_for_timeout(150)


async def seen(pg): return [x["v"] for x in await pg.evaluate("window.__vs")]


def post_log(pg):
    """POST requests of the page as (path, Idempotency-Key)."""
    out = []
    pg.on("request", lambda r: out.append((r.url.split("/price-expert")[-1], r.headers.get("idempotency-key"))) if r.method == "POST" else None)
    return out


# ------------------------------------------------------------------------------------------------ checks

async def c_checkpoint_reset(b):
    pg = await page(b)
    await start(pg)
    await pg.evaluate("G.S.ui.cpDue = { checkpointId: 'cp_5', requiredCorrect: 5 }")   # the run ends inside the promotion delay
    await tap(pg, "back"); await wait_view(pg, "G03.LEAVE_CONFIRM"); await tap(pg, "leave-yes")
    await at_home(pg); await pg.wait_for_timeout(300)
    await start(pg); await reveal(pg)
    return "reveal works in the next run"


async def c_stale_products(b):
    pg = await page(b)
    await start(pg)
    async def slow(route):
        await asyncio.sleep(2.5)
        try: await route.continue_()
        except Exception: pass
    await pg.route("**/runs/*/products", slow)
    await tap(pg, "back"); await tap(pg, "leave-yes")                 # home: the history request of that run is still running
    await at_home(pg)
    await start(pg)
    await pg.wait_for_timeout(3500)                                    # the old history response arrives now
    v = await view(pg)
    assert v == "G03.READY" and await pg.locator(".q-hist").count() == 0, f"a stale history response replaced the new run: {v}"
    return f"new run kept its screen: {v}"


async def c_replay_clock(b):
    pg = await page(b)
    await start(pg)
    moved = await pg.evaluate("""async () => {
        const key = crypto.randomUUID(), run = G.S.run.runId, off = () => CLK.serverNow() - Date.now();
        await API.api.reveal(run, key); const before = off();
        await new Promise(r => setTimeout(r, 3000));
        await API.api.reveal(run, key);                       // replayed by the server with its original server_time
        return off() - before; }""")
    assert abs(moved) < 500, f"the replay moved the clock by {moved:.0f} ms"
    return f"clock moved {moved:.0f} ms on a replay"


async def c_body_timeout(b):
    pg = await page(b)
    r = await pg.evaluate("""async () => {
        const real = window.fetch, out = {};
        window.fetch = (url, o) => Promise.resolve(new Response(new ReadableStream({ start(c) {
            c.enqueue(new TextEncoder().encode('{"server_time":'));
            o.signal.addEventListener('abort', () => c.error(new DOMException('aborted', 'AbortError'))); } }),
            { status: 200, headers: { 'Content-Type': 'application/json' } }));
        const t = performance.now();
        try { await API.request('GET', '/bootstrap', { timeoutMs: 300 }); out.stalled = 'resolved'; } catch (e) { out.stalled = e.code; }
        out.ms = performance.now() - t;
        window.fetch = () => Promise.resolve(new Response('not json', { status: 200 }));
        try { await API.request('GET', '/bootstrap'); out.bad = 'resolved'; } catch (e) { out.bad = e.code; }
        window.fetch = real; return out; }""")
    assert r["stalled"] == "TIMEOUT" and r["ms"] < 2000, f"stalled body: {r}"
    assert r["bad"] == "BAD_RESPONSE", f"malformed success: {r}"
    return f"stalled body timed out after {r['ms']:.0f} ms; malformed success → {r['bad']}"


async def c_offer_per_miss(b):
    ov = admin("GET", "/config"); active = ov["active"]["version"]; cfg = ov["active"]["config"]
    d = admin("POST", "/config/versions", dict(cfg, continue_rule=dict(cfg["continue_rule"], cap=2)))
    admin("POST", f"/config/versions/{d['version']}/publish", {"version": d["version"], "expected_active_version": active, "draft_revision": d["revision"], "reason": "qa cap 2"})
    try:
        pg = await page(b)
        await start(pg)
        await miss_to_offer(pg, started=True)
        offers = [await pg.evaluate("G.S.offer.offerId")]
        await tap(pg, "continue"); await wait_view(pg, "G06.SUCCESS", 9000)
        await miss_to_offer(pg, started=True)
        offers.append(await pg.evaluate("G.S.offer.offerId"))
        assert offers[0] != offers[1], f"the second miss replayed the first offer: {offers}"
        await tap(pg, "loss-home"); await at_home(pg)
        return "two misses, two offers"
    finally:
        ov = admin("GET", "/config")
        r = admin("POST", "/config/versions", cfg)
        admin("POST", f"/config/versions/{r['version']}/publish", {"version": r["version"], "expected_active_version": ov["active"]["version"], "draft_revision": r["revision"], "reason": "qa restore"})


async def c_copy_state(b):
    pg = await page(b, balance=0)
    await start(pg); await reveal(pg); await answer(pg, False)
    await wait_view(pg, "G05.NO_FUNDS", 10000)                         # a finished run: the loss screen stays
    await pg.wait_for_timeout(3000)                                    # the run result has been read
    await pg.evaluate("""() => { G.S.result = { ...G.S.result, campaignCode: { status: 'issued', code: 'QA-CODE', amountToman: 50000, terms: 'شرایط', expiresAt: null } }; G.render();
        navigator.clipboard.writeText = () => Promise.reject(new Error('denied')); document.execCommand = () => false; }""")
    await tap(pg, "copy"); await pg.wait_for_timeout(300)
    assert "کپی نشد" in await pg.inner_text(".q-toast--no"), "no failure message"
    assert "کپی شد" not in await pg.inner_text(".q-code"), "failure shown as copied"
    await pg.evaluate("() => { navigator.clipboard.writeText = () => Promise.resolve(); }")
    await pg.wait_for_timeout(3200)                                    # the failure toast goes
    await tap(pg, "copy"); await pg.wait_for_timeout(300)
    assert "کپی شد" in await pg.inner_text(".q-code"), "success not visible on the button"
    return "denied → failure toast; allowed → «کپی شد» on the button"


QUOTA_SOON = """() => { G.S.boot.quota = { ...G.S.boot.quota, playsLeft: 0, resetsAt: new Date(CLK.serverNow() + 2500).toISOString() };
    G.S.view = 'G01.QUOTA_EXHAUSTED'; G.render(); }"""
# polls until the view settles back on a home state; notes whether «شروع بازی» was ever enabled
WATCH_START = """(until) => new Promise(done => { const out = { enabled: [], views: [] }, t0 = performance.now();
    const step = () => { const v = document.getElementById('app').dataset.view; if (out.views[out.views.length - 1] !== v) out.views.push(v);
      if (document.querySelector('[data-act="start"]:not([disabled])')) out.enabled.push(v);
      if (performance.now() - t0 > until) return done(out); requestAnimationFrame(step); }; step(); })"""


async def c_quota_reset(b):
    async def slow_boot(pg):
        async def slow(route):
            await asyncio.sleep(1.5)
            try: await route.continue_()
            except Exception: pass
        await pg.route("**/bootstrap", slow)
    # a. the day's chances are spent: the server still says 0 at the (simulated) reset → it never becomes playable
    u = "lq" + uuid.uuid4().hex[:6]
    dev(f"/fake-coin/u_{u}", {"balance": 120})
    for _ in range(3):
        rid = api(u, "POST", "/runs")["run"]["run_id"]; api(u, "POST", f"/runs/{rid}/leave")
    pg = await page(b, u)
    await wait_view(pg, "G01.QUOTA_EXHAUSTED")
    btn = pg.locator(".q-homesheet .q-btn--primary")
    assert await btn.is_disabled() and await pg.locator('[data-act="start"]').count() == 0, "start not disabled at 0 chances"
    t1 = await pg.inner_text("[data-countdown]"); await pg.wait_for_timeout(1300); t2 = await pg.inner_text("[data-countdown]")
    assert t1 != t2, f"countdown is static: {t1}"
    await slow_boot(pg)
    await pg.evaluate(QUOTA_SOON)
    w = await pg.evaluate(WATCH_START, 6000)
    # (the unclaimed daily reward of those runs may open its modal again over the home: the home under it stays exhausted)
    assert "G01.QUOTA_CHECKING" in w["views"] and not w["enabled"] and await pg.evaluate("G.S.view") == "G01.QUOTA_EXHAUSTED", f"server said 0 but: {w}"
    # b. chances available on the server: enabled only after its answer arrived
    pg = await page(b)
    await slow_boot(pg)
    await pg.evaluate(QUOTA_SOON)
    w = await pg.evaluate(WATCH_START, 6000)
    assert w["views"][:2] == ["G01.QUOTA_EXHAUSTED", "G01.QUOTA_CHECKING"] and w["enabled"] and all(v.startswith("G01.READY") for v in w["enabled"]), w
    return f"countdown {t1} → {t2}; server 0 → stays disabled; server 3 → {w['views']}"


async def c_cp_once(b):
    pg = await page(b)
    await pg.evaluate("""() => { window.__mounts = 0; new MutationObserver(ms => ms.forEach(m => m.addedNodes.forEach(n => { if (n.classList && n.classList.contains('q-cp')) window.__mounts++; })))
        .observe(document.querySelector('.q-cphost'), { childList: true }); }""")
    await start(pg); await correct_n(pg, 5)
    await wait_view(pg, "G04.REACHED", 6000)
    early = await pg.evaluate("""() => { const dt = performance.now() - G.S.ui.cpAt; document.querySelector('.q-cp [data-act="cp-go"]').click();
        return { dt, view: document.getElementById('app').dataset.view }; }""")
    assert early["view"] == "G04.REACHED" or early["dt"] >= 600, f"closed by an early tap: {early}"
    same = await pg.evaluate("""async () => { const a = document.querySelector('.q-cp'); G.render(); G.render(); window.dispatchEvent(new Event('online'));
        document.dispatchEvent(new Event('visibilitychange')); await new Promise(r => setTimeout(r, 1600)); return a === document.querySelector('.q-cp'); }""")
    assert same, "a re-render or recovery rebuilt the checkpoint overlay (its entrance replays)"
    await pg.click(".q-cp__in"); await pg.mouse.click(30, 120)        # taps beside the button do nothing
    await pg.wait_for_timeout(4200)                                    # past the old 5 s auto-continue
    assert (await view(pg)) == "G04.REACHED", f"closed without «ادامه بده»: {await view(pg)}"
    assert await pg.locator(".q-cp [data-act]").count() == 1, "the celebration has more than one control"
    await tap(pg, "cp-go"); await wait_view(pg, "G03.READY")
    await pg.evaluate("() => document.dispatchEvent(new Event('visibilitychange'))")   # recovery reads the run again
    await pg.evaluate("G.render()"); await pg.wait_for_timeout(1200)
    mounts = await pg.evaluate("window.__mounts")
    assert mounts == 1 and (await view(pg)) == "G03.READY", f"celebration mounted {mounts} times, at {await view(pg)}"
    await pg.reload(); v = await wait_view(pg, ("G01.ACTIVE", "G03."))       # a reload resumes without celebrating again
    if v.startswith("G01.ACTIVE"): await tap(pg, "resume")
    await wait_view(pg, "G03.READY", 6000); await pg.wait_for_timeout(1500)
    assert (await view(pg)) == "G03.READY" and await pg.locator(".q-cp").count() == 0, f"after reload: {await view(pg)}"
    return f"one mount; early tap at {early['dt']:.0f} ms ignored; no tap-anywhere or auto-continue; reload does not replay it"


async def c_zero_tap(b):
    pg = await page(b)
    await start(pg); await reveal(pg)
    await pg.evaluate(RECORD)
    r = await pg.evaluate("""() => new Promise(done => {
        window.__sent = 0; const real = window.fetch; window.fetch = (u, o) => { if (String(u).includes('/answer')) __sent++; return real(u, o); };
        const q = G.S.q, spin = () => { if (Date.parse(q.inputDeadline) - CLK.serverNow() > 0) return requestAnimationFrame(spin);
          G.answer('higher'); G.answer('lower');
          setTimeout(() => done({ pick: q.pick, locked: q.locked, accept: Date.parse(q.acceptDeadline), view: document.getElementById('app').dataset.view }), 300); };
        spin(); })""")
    assert r["pick"] is None and r["locked"] and r["view"] == "G03.TIME_UP_PENDING", f"a pick at zero was taken: {r}"
    await wait_view(pg, "G03.TIMEOUT", 9000)
    vs = await pg.evaluate("window.__vs")
    first = next(x for x in vs if x["v"].startswith(("G03.TIMEOUT", "G03.WRONG", "G05.", "G03.CORRECT")))
    assert first["v"] == "G03.TIMEOUT" and first["t"] >= r["accept"], f"result before the arrival deadline: {first} vs {r['accept']}"
    await wait_view(pg, "G05.OFFER", 6000)
    res = await pg.evaluate("({ sent: __sent, lr: G.S.run.lastResult.result, sub: document.querySelector('.q-loss__sub').textContent })")
    assert res["sent"] == 0 and res["lr"] == "timeout" and "زمانت" in res["sub"], res
    return f"no request; {[x['v'] for x in vs]}; timeout shown {first['t'] - r['accept']:.0f} ms after the arrival deadline"


async def c_pick_before_zero(b):
    pg = await page(b)
    await start(pg); await reveal(pg)
    async def slow(route):
        await asyncio.sleep(2.2)                                       # arrives ~11.8 s after t0: admitted (≤ 12.5 s)
        try: await route.continue_()
        except Exception: pass
    await pg.route("**/questions/*/answer", slow)
    await pg.wait_for_function("() => Date.parse(G.S.q.inputDeadline) - CLK.serverNow() < 400")
    await pg.evaluate(RECORD)
    await answer(pg, True)
    final = await wait_view(pg, ("G03.CORRECT", "G03.TIMEOUT", "G03.WRONG", "G05."), 9000)
    vs = await seen(pg)
    assert final.startswith("G03.CORRECT") and "G03.TIME_UP_PENDING" not in vs and "G03.ANSWER_PENDING" in vs, f"{vs} → {final}"
    return f"{vs}"


async def c_pick_vs_expiry(b):
    pg = await page(b)
    await start(pg); await reveal(pg)
    r = await pg.evaluate("""() => new Promise(done => {
        const views = []; new MutationObserver(() => views.push(document.getElementById('app').dataset.view)).observe(document.getElementById('app'), { attributes: true, attributeFilter: ['data-view'] });
        let sent = 0; const real = window.fetch; window.fetch = (u, o) => { if (String(u).includes('/answer')) sent++; return real(u, o); };
        const spin = () => { const rem = Date.parse(G.S.q.inputDeadline) - CLK.serverNow();
          if (rem > 2) return requestAnimationFrame(spin);
          G.answer('higher'); setTimeout(() => done({ views: [...new Set(views)], sent, pick: G.S.q && G.S.q.pick, locked: G.S.q && G.S.q.locked }), 600); };
        spin(); })""")
    both = "G03.ANSWER_PENDING" in r["views"] and "G03.TIME_UP_PENDING" in r["views"]
    assert not both and r["sent"] <= 1, f"two transitions for one question: {r}"
    return f"one transition at the boundary: {r}"


async def c_daily_double_tap(b):
    pg = await page(b, attended=2, close_daily=False)
    claims = []
    pg.on("request", lambda r: claims.append(r.url) if r.method == "POST" and r.url.endswith("/daily-reward/claim") else None)
    await wait_view(pg, "G01.DAILY_REWARD.CLAIMABLE")
    await pg.evaluate("() => { const b = document.querySelector('[data-act=daily-claim]'); b.click(); b.click(); }")
    await wait_view(pg, "G01.READY", 8000)
    assert len(claims) == 1, f"{len(claims)} claim requests"
    assert await pg.locator(".q-daily").count() == 0, "modal still open"
    bal = coin(pg.u)
    await pg.wait_for_function("b => document.querySelector('.q-bal b').textContent.replace(/[۰-۹]/g, d => '۰۱۲۳۴۵۶۷۸۹'.indexOf(d)) == b", arg=bal, timeout=5000)
    assert bal > 120, f"no credit: {bal}"
    return f"one claim; modal closed to {await view(pg)}; balance {bal} shown"


async def no_decision_modal(pg):
    bad = await pg.evaluate("() => [...document.querySelectorAll('.q-sheet:not(.q-daily), [data-act=continue], [data-act=skip], .q-loss')].map(e => e.className)")
    assert not bad, f"old decision UI on the home: {bad}"


async def c_loss_home(b):
    pg = await page(b)
    posts = post_log(pg)
    rid = await miss_to_offer(pg)
    await pg.evaluate("() => { const b = document.querySelector('.q-foot [data-act=loss-home]'); b.click(); b.click(); }")   # a double tap
    await at_home(pg); await no_decision_modal(pg)
    skips = {k for p, k in posts if p.endswith("/skip")}
    assert len(skips) == 1 and not any(p.endswith("/continue") for p, _ in posts), f"posts: {posts}"
    assert api(pg.u, "GET", f"/runs/{rid}")["state"] == "FINISHED"
    posts.clear()
    await miss_to_offer(pg)                                            # the device's Back does the same
    await pg.go_back(); await at_home(pg); await no_decision_modal(pg)
    assert sum(p.endswith("/skip") for p, _ in posts) == 1 and not any(p.endswith("/continue") for p, _ in posts), f"posts: {posts}"
    return "UI (double tap) and device Back: one decline each, home without decision UI"


async def c_purchase_lost(b):
    pg = await page(b)
    posts = post_log(pg)
    rid = await miss_to_offer(pg)
    n = {"c": 0}
    async def lose(route):                                             # the server handles it; the response never arrives
        n["c"] += 1
        try:
            if n["c"] <= 2: await route.fetch(); await route.abort()
            else: await route.continue_()
        except Exception: pass
    await pg.route("**/offers/*/continue", lose)
    await pg.evaluate(RECORD)
    await tap(pg, "continue")
    await wait_view(pg, "G06.SUCCESS", 15000)
    keys = {k for p, k in posts if p.endswith("/continue")}
    run2 = await pg.evaluate("G.S.run.runId")
    bal = coin(pg.u)
    assert len(keys) == 1 and run2 == rid and bal == 115, f"keys {keys}, run {run2} vs {rid}, balance {bal}"
    return f"{n['c']} sends with one key; {await seen(pg)}; balance 120 → {bal}; same run"


async def c_purchase_reconcile(b):
    pg = await page(b)
    await miss_to_offer(pg)
    async def slow(route):
        await asyncio.sleep(2.4)                                       # the purchase reaches the server late (client timeout 3 s)
        try: await route.continue_()
        except Exception: pass
    await pg.route("**/offers/*/continue", slow)
    await tap(pg, "continue"); await wait_view(pg, "G06.PENDING")
    await pg.wait_for_timeout(800)
    await pg.evaluate(RECORD)
    await pg.evaluate("() => document.dispatchEvent(new Event('visibilitychange'))")   # back to the app: the run still reads OFFER_OPEN
    await wait_view(pg, "G06.SUCCESS", 12000)
    vs = await seen(pg)
    assert not any(v.startswith("G05.") for v in vs) and coin(pg.u) == 115, f"the in-flight purchase was dropped: {vs}"
    return f"kept pending: {vs}"


async def pay(b, outcome, **kw):
    pg = await page(b)
    rid = await miss_to_offer(pg)
    dev(f"/fake-coin/u_{pg.u}", {"script": [{"op": "charge", "outcome": outcome, **kw}]})
    await pg.evaluate(RECORD)
    await tap(pg, "continue")
    return pg, rid


async def c_pay_failed_retry(b):
    pg, rid = await pay(b, "failed")
    await wait_view(pg, "G06.FAILED_RETRY", 6000)
    assert coin(pg.u) == 120 and await pg.locator('[data-act="continue"]').count() == 1, "rejected charge debited or no retry"
    await tap(pg, "continue"); await wait_view(pg, "G06.SUCCESS", 8000)
    assert coin(pg.u) == 115 and await pg.evaluate("G.S.run.runId") == rid
    await reveal(pg); await answer(pg, False)                          # cap 1: the next miss has no continue
    await wait_view(pg, "G05.LOSS.LIMIT_USED", 9000)
    assert await pg.locator('[data-act="continue"]').count() == 0
    return f"{await seen(pg)}; balance 115"


async def c_pay_failed_end(b):
    pg, _ = await pay(b, "failed")
    await wait_view(pg, "G06.FAILED_RETRY", 6000)
    await wait_view(pg, "G06.FAILED_END", 12000)                       # the offer window runs out without a retry
    await pg.wait_for_timeout(1500)
    assert (await view(pg)) == "G06.FAILED_END" and await pg.locator('[data-act="continue"]').count() == 0 and coin(pg.u) == 120
    await tap(pg, "loss-home"); await at_home(pg); await no_decision_modal(pg)
    return f"{await seen(pg)}; balance 120"


async def c_pay_unknown_applied(b):
    pg, rid = await pay(b, "unknown_applied")
    await wait_view(pg, "G06.SUCCESS", 20000)
    assert coin(pg.u) == 115 and await pg.evaluate("G.S.run.runId") == rid
    return f"{await seen(pg)}; balance 115"


async def c_pay_late_success(b):
    pg, rid = await pay(b, "late_success", late_ms=12000)
    await wait_view(pg, "G06.UNKNOWN", 9000)
    await wait_view(pg, "G06.SUCCESS", 25000)
    assert coin(pg.u) == 115 and await pg.evaluate("G.S.run.runId") == rid
    return f"{await seen(pg)}; balance 115"


async def c_pay_not_applied(b):
    pg, _ = await pay(b, "unknown_not_applied")
    v = await wait_view(pg, ("G06.SUCCESS", "G06.FAILED", "G06.REFUNDED", "G05.LOSS"), 20000)
    bal = coin(pg.u)                                                   # the server re-sends the same debit key in time: one debit
    assert v == "G06.SUCCESS" and bal == 115, f"{v} with balance {bal}"
    return f"{await seen(pg)}; balance {bal}"


async def c_pay_refunded(b):
    pg, _ = await pay(b, "late_success", late_ms=80000)
    await wait_view(pg, "G06.UNKNOWN", 9000)
    await pg.wait_for_timeout(9000)
    assert (await view(pg)) == "G06.UNKNOWN", "past the offer deadlines an admitted purchase still waits: " + await view(pg)
    await wait_view(pg, "G06.FAILED_END.UNCONFIRMED", 80000)
    await wait_view(pg, "G06.REFUNDED", 120000)
    t = time.time()
    while coin(pg.u) < 120 and time.time() - t < 25: time.sleep(1)
    assert coin(pg.u) == 120, f"balance {coin(pg.u)}"
    return f"{await seen(pg)}; refunded once, balance 120"


def timer_digits(t): return int(t.translate(FA))


async def c_offline_refresh(b):
    pg = await page(b)
    await start(pg); await reveal(pg)
    q0 = await pg.evaluate("({ id: G.S.q.questionId, d: G.S.q.inputDeadline })")
    await pg.context.set_offline(True)
    await wait_view(pg, "G10.OFFLINE_AFTER_RELEASE", 4000)
    assert await pg.locator('.q-screenhost [data-act^="ans-"]').count() == 0, "answers must be locked while offline"
    await pg.wait_for_timeout(1200)
    await pg.context.set_offline(False)
    await wait_view(pg, "G03.QUESTION_ACTIVE", 6000)
    async def same_question():
        q = await pg.evaluate("({ id: G.S.q.questionId, d: G.S.q.inputDeadline, rem: Date.parse(G.S.q.inputDeadline) - CLK.serverNow(), t: document.querySelector('.q-timer__n').textContent })")
        assert q["id"] == q0["id"] and q["d"] == q0["d"], f"the question or its deadline changed: {q0} → {q}"
        assert abs(timer_digits(q["t"]) - -(-q["rem"] // 1000)) <= 1, f"timer shows {q['t']} with {q['rem']:.0f} ms left"
        return q
    q1 = await same_question()
    await pg.reload()
    v = await wait_view(pg, ("G01.ACTIVE", "G03."))
    if v.startswith("G01.ACTIVE"): await tap(pg, "resume")
    await wait_view(pg, "G03.QUESTION_ACTIVE", 6000)
    await pg.evaluate(MODULES)
    q2 = await same_question()
    assert q2["rem"] < q1["rem"], "the timer restarted"
    await pg.evaluate(RECORD)
    haptics = []                                                       # the dev host logs one haptic per verdict shown
    pg.on("console", lambda m: haptics.append(m.text) if "haptic correct" in m.text else None)
    await answer(pg, True)
    await pg.evaluate("() => window.dispatchEvent(new Event('online'))")            # a reconnect while the answer is in flight
    v = await wait_view(pg, ("G03.CORRECT_FEEDBACK", "G03.READY"), 6000)
    assert v == "G03.CORRECT_FEEDBACK", f"the verdict was skipped: {await seen(pg)}"
    await pg.evaluate("() => { window.dispatchEvent(new Event('online')); document.dispatchEvent(new Event('visibilitychange')); }")
    await wait_view(pg, "G03.READY", 6000); await pg.wait_for_timeout(800)
    vs = await seen(pg)
    shown = [v for v in vs if v != "G10.RESUMING"]                     # the reconnect pill comes and goes on the same verdict
    entered = sum(1 for a, v in zip([""] + shown, shown) if v == "G03.CORRECT_FEEDBACK" != a)
    assert entered == 1 and len(haptics) == 1 and await pg.evaluate("G.S.run.score") == 1, f"result shown {entered}× ({len(haptics)} haptics): {vs}"
    return f"same question and deadline after offline and reload ({q1['rem']:.0f} → {q2['rem']:.0f} ms left); {vs}"


async def c_light_only(b):
    pg = await page(b, scheme="dark")
    r = await pg.evaluate("""() => { const cs = getComputedStyle(document.documentElement);
        const dark = [...document.styleSheets].flatMap(s => { try { return [...s.cssRules]; } catch (e) { return []; } })
          .filter(r => r.media && /prefers-color-scheme:\\s*dark/.test(r.media.mediaText) && matchMedia(r.media.mediaText).matches).length;
        return { theme: document.documentElement.dataset.theme, scheme: cs.colorScheme, bg: cs.getPropertyValue('--bg').trim(), ink: cs.getPropertyValue('--ink').trim(),
          page: getComputedStyle(document.querySelector('.q-page')).backgroundColor, meta: document.querySelector('meta[name=color-scheme]').content,
          dark, sysDark: matchMedia('(prefers-color-scheme: dark)').matches }; }""")
    assert r["sysDark"] and r["theme"] == "light" and r["bg"].upper() == "#FFFFFF" and r["ink"].upper() == "#23252B" and "only light" in r["meta"] \
        and r["page"] == "rgb(255, 255, 255)" and r["dark"] == 0, r
    return f"dark system theme → {r}"


CHECKS = [c_checkpoint_reset, c_stale_products, c_replay_clock, c_body_timeout, c_offer_per_miss, c_copy_state, c_quota_reset,
          c_cp_once, c_zero_tap, c_pick_before_zero, c_pick_vs_expiry, c_daily_double_tap, c_loss_home,
          c_purchase_lost, c_purchase_reconcile, c_pay_failed_retry, c_pay_failed_end, c_pay_unknown_applied, c_pay_late_success,
          c_pay_not_applied, c_pay_refunded, c_offline_refresh, c_light_only]
LONG = {"pay_refunded": 300}


async def run_checks(checks, only=()):
    results = []
    async with async_playwright() as p:
        b = await p.chromium.launch()
        for c in checks:
            name = c.__name__[2:]
            if only and name not in only: continue
            t = time.time()
            try:
                note = await asyncio.wait_for(c(b), LONG.get(name, 120))   # a hang is a failure too
                errs = [e for pg in PAGES for e in pg.errors]
                if errs: raise AssertionError(f"page errors: {errs[:3]}")
                results.append((name, True, note))
            except BaseException as e:
                if isinstance(e, KeyboardInterrupt): raise
                results.append((name, False, f"{type(e).__name__}: {e}"))
            for pg in PAGES:
                try: await pg.context.close()
                except Exception: pass
            PAGES.clear()
            ok, msg = results[-1][1], results[-1][2]
            print(f"{'PASS' if ok else 'FAIL'} {name} ({time.time() - t:.0f}s): {msg}", flush=True)
        await b.close()
    failed = [n for n, ok, _ in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed" + (f"; failed: {', '.join(failed)}" if failed else ""))
    return failed


if __name__ == "__main__":
    sys.exit(1 if asyncio.run(run_checks(CHECKS, set(sys.argv[1:]))) else 0)
