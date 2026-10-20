"""Client lifecycle regressions from the strict review of b023340, in Chromium against the running development backend.
Each check drives the real player modules (dev.html) and fails the run on a regression:
  checkpoint_reset (#4)  a checkpoint due when the run ends must not block reveal in the next run
  stale_products (#10)   a late products response must not replace the screen of a newer run
  share_busy (#10)       an abandoned share request does not leave the share button disabled
  replay_clock (#11)     an idempotent replay must not move the server clock estimate
  body_timeout (#12)     a stalled response body times out; a malformed success is an error
  offer_per_miss (#13)   with a continue cap of 2, the second miss gets its own offer
  copy_state (#28)       copy feedback follows the clipboard result and is visible
  quota_reset (#29)      the quota countdown ticks and the server is asked again at the reset

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


async def page(b, user):
    ctx = await b.new_context(viewport={"width": 375, "height": 812})
    pg = await ctx.new_page()
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    await pg.goto(f"{BASE}/play/dev.html?user={user}&panel=0")
    await wait_view(pg, "G02.FIRST"); await pg.click('[data-act="help-done"]'); await wait_view(pg, "G01.READY")
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
    await pg.click('[data-act="start"]'); await wait_view(pg, "G03.READY"); await pg.wait_for_timeout(400)
    await pg.click('[data-act="reveal"]')
    await wait_view(pg, "G03.QUESTION_ACTIVE", 6000)
    log.append("  reveal works in the next run")


async def c_stale_products(b):
    pg = await page(b, "ls" + uuid.uuid4().hex[:6])
    await to_result(pg)
    async def slow(route):
        await asyncio.sleep(2.5); await route.continue_()
    await pg.route("**/runs/*/products", slow)
    await pg.click('[data-act="products"]'); await wait_view(pg, "G08.LOADING")
    await pg.click('[data-act="back-result"]'); await wait_view(pg, "G07.")
    await pg.click('[data-act="start"]'); await wait_view(pg, "G03.READY")
    await pg.wait_for_timeout(3500)                              # the old products response arrives now
    v = await view(pg)
    assert v.startswith("G03."), f"a stale products response replaced the new run: {v}"
    log.append(f"  new run kept its screen: {v}")


async def c_share_busy(b):
    pg = await page(b, "lh" + uuid.uuid4().hex[:6])
    await to_result(pg)
    async def slow(route):
        await asyncio.sleep(2.5); await route.continue_()
    await pg.route("**/runs/*/share", slow)
    await pg.click('[data-act="share"]'); await pg.wait_for_timeout(200)
    await pg.click('[data-act="start"]'); await wait_view(pg, "G03.READY")   # leave the result while the share request runs
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


CHECKS = [c_checkpoint_reset, c_stale_products, c_share_busy, c_replay_clock, c_body_timeout, c_offer_per_miss, c_copy_state, c_quota_reset]


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
