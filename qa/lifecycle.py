"""Client lifecycle regressions from the strict review of b023340, in Chromium against the running development backend.
Each check drives the real player modules (dev.html) and fails the run on a regression:
  checkpoint_reset (#4)  a checkpoint due when the run ends must not block reveal in the next run
  stale_products (#10)   a late product-history response must not change the screen of a newer run
  share_busy (#10)       an abandoned share request does not leave the share button disabled
  replay_clock (#11)     an idempotent replay must not move the server clock estimate
  body_timeout (#12)     a stalled response body times out; a malformed success is an error
  offer_per_miss (#13)   with a continue cap of 2, the second miss gets its own offer
  copy_state (#28)       copy feedback follows the clipboard result and is visible
  quota_reset (#29)      the quota countdown ticks and the server is asked again at the reset
 brief 2026-10-20:
  cp_once                the checkpoint celebration is mounted once: re-renders and recovery never replay it
  answer_at_zero         an answer sent at ~9.6 s stays «processing» through the visible zero and is evaluated
  pick_vs_expiry         a selection and the visible zero in the same instant make exactly one local transition
  daily_double_tap       two taps on «دریافت کن» send one claim
  end_back               Back on the End Screen (on screen or the device's) goes to the start; no run, no payment
  light_only             a dark system theme cannot switch the game to dark
  purchase_reconcile     a run read while a purchase is in flight keeps it pending (review of 54304a8)

usage: python3 qa/lifecycle.py [check ...]"""
import asyncio, json, os, sys, time, uuid, urllib.request
from playwright.async_api import async_playwright
sys.path.insert(0, os.path.dirname(__file__))
import oracle

BASE = os.environ.get("PE_BASE", "http://127.0.0.1:8080")
ONLY = set(sys.argv[1:])
log, failed = [], []
A = "/admin/v1/gamification/price-expert"


def http(method, path, body=None, headers=None):
    req = urllib.request.Request(BASE + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", **(headers or {})})
    return json.loads(urllib.request.urlopen(req).read() or b"{}")


_tok = {}
def admin(method, path, body=None, extra=None):
    if "t" not in _tok:
        _tok["t"] = http("POST", A + "/session", {"username": "editor", "password": "editor-dev-password"})["token"]
    return http(method, A + path, body, {"Authorization": "Bearer " + _tok["t"], "Idempotency-Key": str(uuid.uuid4()), **(extra or {})})


async def view(pg): return await pg.evaluate("document.getElementById('app').dataset.view")


async def wait_view(pg, prefixes, timeout=10000):
    prefixes = prefixes if isinstance(prefixes, tuple) else (prefixes,)
    t = time.time()
    while (time.time() - t) * 1000 < timeout:
        v = await view(pg)
        if v and v.startswith(prefixes): return v
        await pg.wait_for_timeout(40)
    raise AssertionError(f"timeout waiting for {prefixes}, at {await view(pg)}")


async def page(b, user, scheme="light"):
    http("POST", f"/dev/v1/price-expert/player/u_{user}", {"onboarded": True})
    ctx = await b.new_context(viewport={"width": 375, "height": 812}, color_scheme=scheme)
    pg = await ctx.new_page()
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    await pg.goto(f"{BASE}/play/dev.html?user={user}&panel=0")
    await wait_view(pg, "G01.READY")
    await pg.evaluate("async () => { window.G = await import('/play/src/game.js'); window.API = await import('/play/src/api.js'); window.CLK = await import('/play/src/clock.js'); }")
    return pg


async def to_result(pg):
    await pg.click('[data-act="start"]'); await wait_view(pg, "G03.READY"); await pg.wait_for_timeout(300)
    await pg.evaluate("G.leaveConfirmed()")
    await wait_view(pg, "G07.")
    await pg.wait_for_timeout(400)


async def c_checkpoint_reset(b):
    pg = await page(b, "lc" + uuid.uuid4().hex[:6])
    await pg.click('[data-act="start"]'); await wait_view(pg, "G03.READY")
    await pg.evaluate("G.S.ui.cpDue = { checkpointId: 'cp_3', requiredCorrect: 3 }")   # the run ends inside the promotion delay
    await pg.evaluate("G.leaveConfirmed()"); await wait_view(pg, "G07.")
    await pg.wait_for_timeout(400)
    await pg.click('[data-act="to-start"]'); await wait_view(pg, ("G01.READY", "G01.DAILY"))
    if (await view(pg)).startswith("G01.DAILY"): await pg.click('[data-act="daily-close"]')
    await pg.click('[data-act="start"]'); await wait_view(pg, "G03.READY"); await pg.wait_for_timeout(400)
    await pg.click('[data-act="reveal"]')
    await wait_view(pg, "G03.QUESTION_ACTIVE", 6000)
    log.append("  reveal works in the next run")


async def c_stale_products(b):
    pg = await page(b, "ls" + uuid.uuid4().hex[:6])
    async def slow(route):
        await asyncio.sleep(2.5); await route.continue_()
    await pg.route("**/runs/*/products", slow)
    await to_result(pg)                                           # the history request is still running
    await pg.click('[data-act="to-start"]'); await wait_view(pg, ("G01.READY", "G01.DAILY"))
    if (await view(pg)).startswith("G01.DAILY"): await pg.click('[data-act="daily-close"]')
    await pg.click('[data-act="start"]'); await wait_view(pg, "G03.READY")
    await pg.wait_for_timeout(3500)                              # the old history response arrives now
    v = await view(pg)
    assert v.startswith("G03."), f"a stale history response replaced the new run: {v}"
    log.append(f"  new run kept its screen: {v}")


async def c_share_busy(b):
    pg = await page(b, "lh" + uuid.uuid4().hex[:6])
    await to_result(pg)
    async def slow(route):
        await asyncio.sleep(2.5); await route.continue_()
    await pg.route("**/runs/*/share", slow)
    await pg.click('[data-act="share"]'); await pg.wait_for_timeout(200)
    await pg.click('[data-act="to-start"]'); await wait_view(pg, ("G01.READY", "G01.DAILY"))   # leave the result while the share request runs
    if (await view(pg)).startswith("G01.DAILY"): await pg.click('[data-act="daily-close"]')
    await pg.click('[data-act="start"]'); await wait_view(pg, "G03.READY")
    await pg.wait_for_timeout(3000)
    await pg.evaluate("G.leaveConfirmed()"); await wait_view(pg, "G07."); await pg.wait_for_timeout(400)
    assert await pg.locator('[data-act="share"]').count() == 1, "share stayed disabled after an abandoned request"
    log.append("  share is available again on the next result")


async def c_replay_clock(b):
    pg = await page(b, "lr" + uuid.uuid4().hex[:6])
    await pg.click('[data-act="start"]'); await wait_view(pg, "G03.READY")
    moved = await pg.evaluate("""async () => {
        const key = crypto.randomUUID(), run = G.S.run.runId, off = () => CLK.serverNow() - Date.now();
        await API.api.reveal(run, key); const before = off();
        await new Promise(r => setTimeout(r, 3000));
        await API.api.reveal(run, key);                       // replayed by the server with its original server_time
        return off() - before; }""")
    assert abs(moved) < 500, f"the replay moved the clock by {moved:.0f} ms"
    log.append(f"  clock moved {moved:.0f} ms on a replay")


async def c_body_timeout(b):
    pg = await page(b, "lb" + uuid.uuid4().hex[:6])
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
    log.append(f"  stalled body timed out after {r['ms']:.0f} ms; malformed success → {r['bad']}")


async def c_offer_per_miss(b):
    ov = admin("GET", "/config"); active = ov["active"]["version"]; cfg = ov["active"]["config"]
    d = admin("POST", "/config/versions", dict(cfg, continue_rule=dict(cfg["continue_rule"], cap=2)))
    admin("POST", f"/config/versions/{d['version']}/publish", {"version": d["version"], "expected_active_version": active, "draft_revision": d["revision"], "reason": "qa cap 2"})
    try:
        u = "lo" + uuid.uuid4().hex[:6]
        pg = await page(b, u)
        await pg.click('[data-act="start"]'); await wait_view(pg, "G03.READY"); await pg.wait_for_timeout(300)
        offers = []
        for i in range(2):
            await pg.click('[data-act="reveal"]'); await wait_view(pg, "G03.QUESTION_ACTIVE"); await pg.wait_for_timeout(400)
            await pg.click(f'[data-act="ans-{oracle.choice("u_" + u, False)}"]')
            await wait_view(pg, "G05.OFFER", 9000)
            offers.append(await pg.evaluate("G.S.offer.offerId"))
            if i == 0:
                await pg.click('[data-act="continue"]'); await wait_view(pg, ("G06.SUCCESS", "G03.READY"), 9000); await pg.wait_for_timeout(1200)
        assert offers[0] != offers[1], f"the second miss replayed the first offer: {offers}"
        await pg.click('[data-act="skip"]'); await wait_view(pg, "G07.", 9000)
        log.append("  two misses, two offers")
    finally:
        ov = admin("GET", "/config")
        r = admin("POST", "/config/versions", cfg)
        admin("POST", f"/config/versions/{r['version']}/publish", {"version": r["version"], "expected_active_version": ov["active"]["version"], "draft_revision": r["revision"], "reason": "qa restore"})


async def c_copy_state(b):
    pg = await page(b, "lp" + uuid.uuid4().hex[:6])
    await to_result(pg)
    await pg.wait_for_timeout(4000)                               # pending-reward refreshes of the result are over
    await pg.evaluate("""() => { G.S.result = { ...G.S.result, campaignCode: { status: 'issued', code: 'QA-CODE', amountToman: 50000, terms: 'شرایط', expiresAt: null } }; G.render();
        navigator.clipboard.writeText = () => Promise.reject(new Error('denied')); document.execCommand = () => false; }""")
    await pg.click('[data-act="copy"]'); await pg.wait_for_timeout(300)
    assert await view(pg) == "G07.CAMPAIGN_CODE.COPY_FAILED", await view(pg)
    assert "کپی شد" not in await pg.inner_text(".v3-code"), "failure shown as copied"
    await pg.evaluate("() => { navigator.clipboard.writeText = () => Promise.resolve(); }")
    await pg.wait_for_timeout(3500)                               # the failure toast goes
    await pg.click('[data-act="copy"]'); await pg.wait_for_timeout(300)
    assert "کپی شد" in await pg.inner_text(".v3-code"), "success not visible on the button"
    log.append("  denied → failure message; allowed → «کپی شد» on the button")


async def c_quota_reset(b):
    pg = await page(b, "lq" + uuid.uuid4().hex[:6])
    await pg.evaluate("""() => { G.S.boot.quota = { ...G.S.boot.quota, playsLeft: 0, resetsAt: new Date(CLK.serverNow() + 3000).toISOString() };
        G.S.view = 'G01.QUOTA_EXHAUSTED'; G.render(); }""")
    t1 = await pg.inner_text("[data-countdown]"); await pg.wait_for_timeout(1300); t2 = await pg.inner_text("[data-countdown]")
    assert t1 != t2, f"countdown is static: {t1}"
    await wait_view(pg, "G01.READY", 6000)                         # at the reset the server is asked again (3 plays)
    log.append(f"  countdown {t1} → {t2}, then the server's quota")


# ------------------------------------------------------------------------------------------------ brief 2026-10-20

async def reach_checkpoint(pg, u):
    await pg.click('[data-act="start"]'); await wait_view(pg, "G03.READY")
    for i in range(3):
        await wait_view(pg, "G03.READY"); await pg.wait_for_timeout(400)
        await pg.click('[data-act="reveal"]'); await wait_view(pg, "G03.QUESTION_ACTIVE"); await pg.wait_for_timeout(200)
        await pg.click(f'[data-act="ans-{oracle.choice("u_" + u, True)}"]')
    await wait_view(pg, "G04.REACHED", 8000)


async def c_cp_once(b):
    u = "lk" + uuid.uuid4().hex[:6]
    pg = await page(b, u)
    await pg.evaluate("""() => { window.__mounts = 0; new MutationObserver(ms => ms.forEach(m => m.addedNodes.forEach(n => { if (n.classList && n.classList.contains('v3-cp')) window.__mounts++; })))
        .observe(document.querySelector('.pe-cphost'), { childList: true }); }""")
    await reach_checkpoint(pg, u)
    same = await pg.evaluate("""async () => { const a = document.querySelector('.v3-cp'); G.render(); G.render(); window.dispatchEvent(new Event('online'));
        await new Promise(r => setTimeout(r, 1600)); return a === document.querySelector('.v3-cp'); }""")   # incl. the 1.2 s guard step that used to re-render it
    assert same, "a re-render rebuilt the checkpoint overlay (its entrance replays)"
    await pg.wait_for_timeout(300); await pg.click(".v3-cp"); await wait_view(pg, "G03.READY")
    await pg.evaluate("() => document.dispatchEvent(new Event('visibilitychange'))")   # recovery reads the run again
    await pg.wait_for_timeout(1200)
    mounts = await pg.evaluate("window.__mounts")
    assert mounts == 1, f"celebration mounted {mounts} times"
    await pg.reload(); await wait_view(pg, ("G01.ACTIVE", "G03."))       # a reload resumes without celebrating again
    if (await view(pg)).startswith("G01.ACTIVE"): await pg.click('[data-act="resume"]'); await wait_view(pg, "G03.READY", 6000)
    await pg.wait_for_timeout(1200)
    assert (await view(pg)) == "G03.READY", await view(pg)
    log.append("  one mount; re-renders, a reconnect, a visibility recovery and a reload never replay it")


async def c_answer_at_zero(b):
    u = "lz" + uuid.uuid4().hex[:6]
    pg = await page(b, u)
    await pg.click('[data-act="start"]'); await wait_view(pg, "G03.READY"); await pg.wait_for_timeout(300)
    await pg.click('[data-act="reveal"]'); await wait_view(pg, "G03.QUESTION_ACTIVE")
    async def slow(route):
        await asyncio.sleep(2.2); await route.continue_()               # arrives ~11.8 s after t0: admitted (≤ 12.5 s)
    await pg.route("**/questions/*/answer", slow)
    await pg.wait_for_function("() => Date.parse(G.S.q.inputDeadline) - CLK.serverNow() < 400")
    await pg.click(f'[data-act="ans-{oracle.choice("u_" + u, True)}"]')
    seen = set()
    for _ in range(40):
        seen.add(await view(pg)); await pg.wait_for_timeout(50)
        if (await view(pg)).startswith("G03.CORRECT"): break
    final = await wait_view(pg, ("G03.CORRECT", "G03.TIMEOUT", "G03.WRONG"), 9000)
    assert "G03.TIME_UP_PENDING" not in seen and final.startswith("G03.CORRECT"), f"views {seen} → {final}"
    log.append(f"  processing through the visible zero ({sorted(seen)}) → {final}")


async def c_pick_vs_expiry(b):
    u = "lx" + uuid.uuid4().hex[:6]
    pg = await page(b, u)
    await pg.click('[data-act="start"]'); await wait_view(pg, "G03.READY"); await pg.wait_for_timeout(300)
    await pg.click('[data-act="reveal"]'); await wait_view(pg, "G03.QUESTION_ACTIVE")
    r = await pg.evaluate("""() => new Promise(done => {
        const views = []; new MutationObserver(() => views.push(document.getElementById('app').dataset.view)).observe(document.getElementById('app'), { attributes: true, attributeFilter: ['data-view'] });
        let sent = 0; const real = window.fetch; window.fetch = (u, o) => { if (String(u).includes('/answer')) sent++; return real(u, o); };
        const spin = () => { const rem = Date.parse(G.S.q.inputDeadline) - CLK.serverNow();
          if (rem > 2) return requestAnimationFrame(spin);
          G.answer('higher'); setTimeout(() => done({ views: [...new Set(views)], sent, pick: G.S.q && G.S.q.pick, locked: G.S.q && G.S.q.locked }), 600); };
        spin(); })""")
    both = "G03.ANSWER_PENDING" in r["views"] and "G03.TIME_UP_PENDING" in r["views"]
    assert not both and r["sent"] <= 1, f"two transitions for one question: {r}"
    log.append(f"  one transition at the boundary: {r}")


async def c_daily_double_tap(b):
    u = "ld" + uuid.uuid4().hex[:6]
    http("POST", f"/dev/v1/price-expert/player/u_{u}", {"onboarded": True, "attended_days": 2})
    ctx = await b.new_context(viewport={"width": 375, "height": 812}); pg = await ctx.new_page()
    claims = []
    pg.on("request", lambda r: claims.append(r.url) if r.method == "POST" and r.url.endswith("/daily-reward/claim") else None)
    await pg.goto(f"{BASE}/play/dev.html?user={u}&panel=0")
    await wait_view(pg, "G01.DAILY_REWARD.CLAIMABLE")
    await pg.evaluate("() => { const b = document.querySelector('[data-act=daily-claim]'); b.click(); b.click(); }")
    await wait_view(pg, "G01.DAILY_REWARD.CLAIMED", 6000)
    assert len(claims) == 1, f"{len(claims)} claim requests"
    bal = http("GET", f"/dev/v1/price-expert/fake-coin/u_{u}")["balance"]
    log.append(f"  one claim request; balance {bal}")


async def c_end_back(b):
    u = "le" + uuid.uuid4().hex[:6]
    pg = await page(b, u)
    await to_result(pg)
    posts = []
    pg.on("request", lambda r: posts.append(r.url) if r.method == "POST" else None)
    await pg.click('[data-act="to-start"]'); await wait_view(pg, ("G01.READY", "G01.DAILY"))
    if (await view(pg)).startswith("G01.DAILY"): await pg.click('[data-act="daily-close"]')
    assert not any("/runs" in p or "/offers" in p for p in posts), f"Back created something: {posts}"
    after_back = list(posts)
    assert await pg.evaluate("G.S.run === null && G.S.q === null && G.S.result === null"), "local run state kept"
    await pg.click('[data-act="start"]'); await wait_view(pg, "G03.READY")
    await pg.evaluate("G.leaveConfirmed()"); await wait_view(pg, "G07."); await pg.wait_for_timeout(600)
    await pg.go_back(); await wait_view(pg, ("G01.READY", "G01.DAILY", "G01.QUOTA"), 6000)   # the device's Back does the same
    log.append(f"  UI Back and device Back → {await view(pg)}; POSTs caused by Back: {after_back}")


async def c_light_only(b):
    pg = await page(b, "ll" + uuid.uuid4().hex[:6], scheme="dark")
    r = await pg.evaluate("""() => ({ theme: document.documentElement.dataset.theme, scheme: getComputedStyle(document.documentElement).colorScheme,
        card: getComputedStyle(document.documentElement).getPropertyValue('--card').trim(), ink: getComputedStyle(document.documentElement).getPropertyValue('--ink').trim(),
        meta: document.querySelector('meta[name=color-scheme]').content })""")
    assert r["theme"] == "light" and r["card"].upper() == "#FFFFFF" and r["ink"].upper() == "#1E2129" and "only light" in r["meta"], r
    log.append(f"  dark system theme → {r}")


async def c_purchase_reconcile(b):
    u = "lr" + uuid.uuid4().hex[:6]
    pg = await page(b, u)
    await pg.click('[data-act="start"]'); await wait_view(pg, "G03.READY"); await pg.wait_for_timeout(300)
    await pg.click('[data-act="reveal"]'); await wait_view(pg, "G03.QUESTION_ACTIVE"); await pg.wait_for_timeout(300)
    await pg.click(f'[data-act="ans-{oracle.choice("u_" + u, False)}"]'); await wait_view(pg, "G05.OFFER", 9000)
    async def slow(route):
        await asyncio.sleep(2.4); await route.continue_()               # the purchase reaches the server late (client timeout 3 s)
    await pg.route("**/offers/*/continue", slow)
    await pg.click('[data-act="continue"]'); await wait_view(pg, "G06.PENDING")
    await pg.wait_for_timeout(800)
    await pg.evaluate("() => document.dispatchEvent(new Event('visibilitychange'))")   # back to the app: the run still reads OFFER_OPEN
    seen = set()
    for _ in range(30):
        seen.add(await view(pg)); await pg.wait_for_timeout(100)
    assert not any(v.startswith("G05.") for v in seen), f"the in-flight purchase was dropped: {seen}"
    v = await wait_view(pg, ("G06.SUCCESS", "G03.READY"), 12000)
    log.append(f"  a reconcile during the purchase kept it pending ({sorted(seen)}) → {v}")

CHECKS = [c_checkpoint_reset, c_stale_products, c_share_busy, c_replay_clock, c_body_timeout, c_offer_per_miss, c_copy_state, c_quota_reset,
          c_cp_once, c_answer_at_zero, c_pick_vs_expiry, c_daily_double_tap, c_end_back, c_light_only, c_purchase_reconcile]


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        for c in CHECKS:
            name = c.__name__[2:]
            if ONLY and name not in ONLY: continue
            log.append(f"{name}:")
            try: await asyncio.wait_for(c(b), 90)                  # a hang is a failure too
            except BaseException as e:
                if isinstance(e, KeyboardInterrupt): raise
                failed.append(name); log.append(f"  FAILED: {type(e).__name__}: {e}")
        await b.close()
    print("\n".join(log))
    print(json.dumps({"failed": failed}))
    if failed: sys.exit(1)

asyncio.run(main())
