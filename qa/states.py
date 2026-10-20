"""Scenario QA beyond the happy path, against the running development backend (fake DigiCoin, dev login) at 375×812.
Each scenario uses its own player and saves screenshots; the run prints the views reached and fails on page errors.
Hidden prices come only from qa/oracle.py (test-only DB read). Admin actions go through the real admin API.

usage: python3 qa/states.py [outdir] [theme] [scenario ...]"""
import asyncio, json, os, sys, time, uuid, urllib.request
from playwright.async_api import async_playwright
sys.path.insert(0, os.path.dirname(__file__))
import oracle

BASE = os.environ.get("PE_BASE", "http://127.0.0.1:8080")
OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/qa-states"
THEME = sys.argv[2] if len(sys.argv) > 2 else "light"
ONLY = set(sys.argv[3:])
os.makedirs(OUT, exist_ok=True)
log, errors = [], []


def http(method, path, body=None, headers=None):
    h = {"Content-Type": "application/json", **(headers or {})}
    req = urllib.request.Request(BASE + path, method=method, data=None if body is None else json.dumps(body).encode(), headers=h)
    return json.loads(urllib.request.urlopen(req).read() or b"{}")


def dev(user, body): return http("POST", f"/dev/v1/price-expert/fake-coin/u_{user}", body)


_admin = {}
def admin(method, path, body=None):
    if "t" not in _admin:
        _admin["t"] = http("POST", "/admin/v1/gamification/price-expert/session", {"username": "editor", "password": "editor-dev-password"})["token"]
    return http(method, "/admin/v1/gamification/price-expert" + path, body, {"Authorization": "Bearer " + _admin["t"], "Idempotency-Key": str(uuid.uuid4())})


class Api:
    """The player API with a dev token, used to set up history (quota) without the UI."""
    def __init__(self, user, device): self.user, self.device = user, device
    def __call__(self, method, path, body=None):
        return http(method, "/v1/gamification/price-expert" + path, body,
                    {"Authorization": "Bearer dev." + self.user, "X-Device-Id": self.device, "Idempotency-Key": str(uuid.uuid4())})


async def view(pg): return await pg.evaluate("document.getElementById('app').dataset.view")


async def wait_view(pg, prefix, timeout=8000):
    t = time.time()
    prefixes = prefix if isinstance(prefix, tuple) else (prefix,)
    while (time.time() - t) * 1000 < timeout:
        v = await view(pg)
        if v and v.startswith(prefixes): return v
        await pg.wait_for_timeout(40)
    raise AssertionError(f"timeout waiting for {prefix}, at {await view(pg)}")


SCROLL_AUDIT = """() => { const out = []; const se = document.scrollingElement;
  if (se.scrollHeight > innerHeight + 1 || se.scrollWidth > innerWidth + 1) out.push('page');
  document.querySelectorAll('#app *').forEach(el => { const cs = getComputedStyle(el);
    if (el.closest('[data-scroll]') || el.closest('.mo-layer')) return;
    if ((el.scrollHeight > el.clientHeight + 1 && ['auto', 'scroll'].includes(cs.overflowY)) || (el.scrollWidth > el.clientWidth + 1 && ['auto', 'scroll'].includes(cs.overflowX))) out.push(el.className || el.tagName); });
  return out; }"""


async def shot(pg, name):
    await pg.screenshot(path=f"{OUT}/{name}-{THEME}.png")
    sc = await pg.evaluate(SCROLL_AUDIT)                           # UX-4 / T45: no player scrolling outside the product list
    if sc: errors.append(f"{name}: unexpected scroll {sc}")
    log.append(f"  {name}: {await view(pg)}")


async def act(pg, a): await pg.click(f'[data-act="{a}"]')


async def answer(pg, user, correct):
    await act(pg, "ans-" + oracle.choice("u_" + user, correct))


async def page(b, user, device=None, reduced=False):
    ctx = await b.new_context(viewport={"width": 375, "height": 812}, device_scale_factor=2, color_scheme=THEME,
                              reduced_motion="reduce" if reduced else "no-preference")
    pg = await ctx.new_page()
    pg.on("pageerror", lambda e: errors.append(f"{user}: {e}"))
    pg.on("console", lambda m: m.type == "error" and "Failed to load resource" not in m.text and errors.append(f"{user}: {m.text}"))
    q = f"user={user}&panel=0&theme={THEME}" + (f"&device={device}" if device else "")
    await pg.goto(f"{BASE}/play/dev.html?{q}")
    v = await wait_view(pg, ("G02.FIRST", "G01.READY", "G01.QUOTA", "G01.ACTIVE", "G11."))
    if v == "G02.FIRST":
        await act(pg, "help-done")
    await wait_view(pg, ("G01.READY", "G01.QUOTA", "G11.", "G01.ACTIVE"))
    return pg


async def start_and_reveal(pg):
    await act(pg, "start")
    await wait_view(pg, "G03.READY")
    await pg.wait_for_timeout(500)
    await act(pg, "reveal")
    await wait_view(pg, "G03.QUESTION_ACTIVE")


def user(tag):
    u = f"{tag}{uuid.uuid4().hex[:5]}"
    dev(u, {"balance": 120})
    return u


# ---------------------------------------------------------------------------------------------------------- scenarios

async def s_timeout(b):
    u = user("to"); pg = await page(b, u)
    await start_and_reveal(pg)
    t0 = time.time()
    await wait_view(pg, "G03.QUESTION_ACTIVE.LOW_TIME", 10000)
    log.append(f"  low-time state after {time.time() - t0:.1f}s")
    await shot(pg, "timeout-1-low-time")
    await wait_view(pg, "G03.TIMEOUT", 6000)
    log.append(f"  timeout shown {time.time() - t0:.2f}s after the question opened (input locks at 10.5 s)")
    await pg.wait_for_timeout(300); await shot(pg, "timeout-2-feedback")
    await wait_view(pg, "G05.OFFER", 6000); await shot(pg, "timeout-3-offer")
    await act(pg, "skip")
    await wait_view(pg, "G07.", 8000); await pg.wait_for_timeout(1500); await shot(pg, "timeout-4-result")
    await act(pg, "share")                                         # T48: name-free share image; dev host shares image-only
    await wait_view(pg, "G09.ASSET", 8000); await pg.wait_for_timeout(400); await shot(pg, "share-1-asset")
    await act(pg, "share-send"); await pg.wait_for_timeout(800); await shot(pg, "share-2-sent")


async def s_no_funds(b):
    u = user("nf"); pg = await page(b, u)
    dev(u, {"balance": 3})
    await start_and_reveal(pg)
    await answer(pg, u, False)
    await wait_view(pg, "G05.NO_FUNDS", 6000); await pg.wait_for_timeout(300); await shot(pg, "nofunds-1")
    await wait_view(pg, "G07.", 8000); await pg.wait_for_timeout(1500); await shot(pg, "nofunds-2-result")


async def miss_to_offer(pg, u):
    await start_and_reveal(pg)
    await answer(pg, u, False)
    await wait_view(pg, "G05.OFFER", 6000)


async def s_charge_failed(b):
    u = user("cf"); pg = await page(b, u)
    await miss_to_offer(pg, u)
    dev(u, {"script": [{"op": "charge", "outcome": "failed"}]})
    await act(pg, "continue")
    await wait_view(pg, "G06.FAILED_RETRY", 6000); await shot(pg, "charge-failed-retry")
    await act(pg, "continue")                                   # the retry succeeds (script consumed)
    await wait_view(pg, ("G06.SUCCESS", "G03.READY"), 6000); await pg.wait_for_timeout(600); await shot(pg, "charge-failed-then-success")
    bal = http("GET", f"/dev/v1/price-expert/fake-coin/u_{u}")["balance"]
    log.append(f"  balance after one failed + one confirmed charge: {bal} (expected 110)")
    assert bal == 110


async def s_charge_unknown(b):
    u = user("cu"); pg = await page(b, u)
    await miss_to_offer(pg, u)
    # every send (first and the safe re-sends after a "not found" query) times out without applying anything
    dev(u, {"script": [{"op": "charge", "outcome": "unknown_not_applied"}] * 12})
    await act(pg, "continue")
    await wait_view(pg, "G06.PENDING", 3000); await shot(pg, "charge-unknown-1-pending")
    await wait_view(pg, "G06.UNKNOWN", 8000); await shot(pg, "charge-unknown-2-unknown")
    await wait_view(pg, "G06.FAILED_END", 15000); await pg.wait_for_timeout(300); await shot(pg, "charge-unknown-3-ended")
    bal = http("GET", f"/dev/v1/price-expert/fake-coin/u_{u}")["balance"]
    log.append(f"  balance after an unconfirmed (not applied) charge: {bal} (expected 120 + rewards, never -10)")


async def s_charge_late(b):
    u = user("cl"); pg = await page(b, u)
    await miss_to_offer(pg, u)
    dev(u, {"script": [{"op": "charge", "outcome": "late_success", "late_ms": 12000}]})
    await act(pg, "continue")
    await wait_view(pg, "G06.FAILED_END", 16000); await pg.wait_for_timeout(300); await shot(pg, "charge-late-1-ended-unconfirmed")
    await wait_view(pg, "G06.REFUNDED", 25000); await shot(pg, "charge-late-2-refunded")   # a gate: the refund must reach the screen
    t = time.time()
    while time.time() - t < 25:
        bal = http("GET", f"/dev/v1/price-expert/fake-coin/u_{u}")["balance"]
        if bal >= 120: break
        time.sleep(1)
    log.append(f"  balance after late success + refund: {bal} (expected ≥ 120: charge refunded once)")
    assert bal >= 120


async def s_replacement(b):
    u = user("rp"); dev_a, dev_b = str(uuid.uuid4()), str(uuid.uuid4())
    a = await page(b, u, dev_a)
    await act(a, "start"); await wait_view(a, "G03.READY")
    bpg = await page(b, u, dev_b)
    await wait_view(bpg, "G01.READY.OTHER_DEVICE_RUN"); await shot(bpg, "replace-1-other-device-start")
    await act(bpg, "start")
    await wait_view(bpg, "G03.READY", 8000); await shot(bpg, "replace-2-new-device-playing")
    await act(a, "reveal")
    await wait_view(a, "G11.OTHER_DEVICE", 8000); await shot(a, "replace-3-old-device")


async def s_blocked(b):
    u = user("bl"); pg = await page(b, u)
    await act(pg, "start"); await wait_view(pg, "G03.READY")
    admin("POST", f"/players/u_{u}/block", {"reason": "QA", "review_reference": "QA-1"})
    await act(pg, "reveal")
    await wait_view(pg, "G11.BLOCKED", 8000); await shot(pg, "blocked")
    admin("POST", f"/players/u_{u}/unblock", {"reason": "QA"})


async def s_game_off(b):
    u = user("go")
    admin("POST", "/game/availability", {"enabled": False, "player_message": None, "reason": "QA"})
    try:
        pg = await page(b, u)
        await wait_view(pg, "G11.GAME_OFF", 6000); await shot(pg, "game-off")
    finally:
        admin("POST", "/game/availability", {"enabled": True, "player_message": None, "reason": "QA"})


async def s_quota(b):
    u = user("qt"); dv = str(uuid.uuid4()); api = Api(u, dv)
    for _ in range(3):
        r = api("POST", "/runs", {})["run"]["run_id"]
        api("POST", f"/runs/{r}/leave", {})
    pg = await page(b, u, dv)
    await wait_view(pg, "G01.QUOTA", 6000); await shot(pg, "quota-exhausted")


async def s_campaign(b):
    for c in admin("GET", "/campaigns"):
        if c["status"] != "ENDED": admin("POST", f"/campaigns/{c['campaign_id']}/end", {"reason": "QA cleanup"})
    now = time.time()
    iso = lambda t: time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t))
    camp = admin("POST", "/campaigns", {"name": "کمپین QA", "starts_at": iso(now - 60), "ends_at": iso(now + 3600), "code_checkpoint_ids": ["cp_3"],
                                       "code_amount_toman": 50000, "validity_days": 14, "terms": "قابل استفاده برای خرید بالای ۵۰۰ هزار تومان.",
                                       "code_cap": 10, "budget_toman": 500000})
    try:
        u = user("cp"); pg = await page(b, u)
        await wait_view(pg, "G01.READY.CAMPAIGN", 4000); await shot(pg, "campaign-1-start")
        await act(pg, "start"); await wait_view(pg, "G03.READY")
        for i in range(3):
            await pg.wait_for_timeout(400)
            await act(pg, "reveal"); await wait_view(pg, "G03.QUESTION_ACTIVE")
            await answer(pg, u, True)
            await wait_view(pg, ("G03.READY", "G04.REACHED"), 5000)
        await wait_view(pg, "G04.REACHED", 4000); await pg.wait_for_timeout(1400); await shot(pg, "campaign-2-checkpoint")
        await pg.mouse.click(187, 400)                            # tap after the guard closes it once
        await wait_view(pg, "G03.READY", 3000)
        await act(pg, "back"); await wait_view(pg, "G03.LEAVE_CONFIRM"); await shot(pg, "campaign-3-leave-confirm")
        await act(pg, "leave-yes")
        await wait_view(pg, "G07.", 10000)
        await wait_view(pg, "G07.CAMPAIGN_CODE", 12000); await pg.wait_for_timeout(1800); await shot(pg, "campaign-4-code")
        await act(pg, "copy")
        await wait_view(pg, "G07.CAMPAIGN_CODE.COPIED", 3000); await shot(pg, "campaign-5-copied")
    finally:
        admin("POST", f"/campaigns/{camp['campaign_id']}/end", {"reason": "QA end"})


async def s_offline(b):
    u = user("of"); pg = await page(b, u)
    await start_and_reveal(pg)
    await pg.context.set_offline(True)
    await wait_view(pg, "G10.OFFLINE_AFTER_RELEASE", 4000)        # keys lock while offline (T12); the clock keeps running
    await pg.wait_for_timeout(800)                                 # motion clones (aria-hidden, no pointer events) are gone
    assert await pg.locator('.v3-stage [data-act="ans-higher"]').count() == 0, "answer keys must be locked while offline"
    await shot(pg, "offline-1-after-release")
    await pg.context.set_offline(False)                            # back before the cutoff: the same question, same deadline
    await wait_view(pg, "G03.QUESTION_ACTIVE", 6000); await shot(pg, "offline-2-back-before-cutoff")
    await answer(pg, u, True)
    v = await wait_view(pg, ("G03.CORRECT", "G03.TIMEOUT"), 6000)
    log.append(f"  answered after reconnect: {v}")
    # offline again until after the cutoff: reconnecting cannot reopen the expired question
    await pg.wait_for_timeout(1500)
    await act(pg, "reveal"); await wait_view(pg, "G03.QUESTION_ACTIVE")
    await pg.context.set_offline(True)
    await pg.wait_for_timeout(12000)
    await pg.context.set_offline(False)
    v = await wait_view(pg, ("G03.TIMEOUT", "G05.", "G07."), 10000)
    await pg.wait_for_timeout(300); await shot(pg, "offline-3-back-after-cutoff")
    log.append(f"  reconnect after the cutoff: {v} (no reopened question)")


async def s_offline_before(b):
    u = user("ob"); pg = await page(b, u)
    await act(pg, "start"); await wait_view(pg, "G03.READY")
    await pg.context.set_offline(True)
    await wait_view(pg, "G10.OFFLINE_BEFORE_RELEASE", 4000); await shot(pg, "offline-before-release")
    await pg.context.set_offline(False)
    await wait_view(pg, "G03.READY", 6000)                         # no question was released; nothing timed out
    await act(pg, "reveal"); await wait_view(pg, "G03.QUESTION_ACTIVE")
    log.append("  recovered to READY and released a question normally")


async def s_motion(b):
    """Filmstrips: reveal (reference shrinks, comparison promotes) and correct → next ready. Frames every 90 ms."""
    for reduced in (False, True):
        tag = "reduced" if reduced else "motion"
        u = user("mo"); pg = await page(b, u, reduced=reduced)
        await act(pg, "start"); await wait_view(pg, "G03.READY"); await pg.wait_for_timeout(700)
        await act(pg, "reveal")
        for i in range(8):
            await pg.screenshot(path=f"{OUT}/film-{tag}-reveal-{i}-{THEME}.png"); await pg.wait_for_timeout(60)
        await wait_view(pg, "G03.QUESTION_ACTIVE")
        await pg.wait_for_timeout(300)
        await answer(pg, u, True)
        for i in range(10):
            await pg.screenshot(path=f"{OUT}/film-{tag}-correct-{i}-{THEME}.png"); await pg.wait_for_timeout(150)
        log.append(f"  {tag}: frames saved")


SCENARIOS = [s_timeout, s_no_funds, s_charge_failed, s_charge_unknown, s_charge_late, s_replacement, s_blocked, s_game_off,
             s_quota, s_campaign, s_offline, s_offline_before, s_motion]


async def main():
    failed = []
    async with async_playwright() as p:
        b = await p.chromium.launch()
        for s in SCENARIOS:
            name = s.__name__[2:]
            if ONLY and name not in ONLY: continue
            log.append(f"{name}:")
            try:
                await s(b)
            except Exception as e:
                failed.append(name); log.append(f"  FAILED: {type(e).__name__}: {e}")
        await b.close()
    print("\n".join(log))
    print(json.dumps({"theme": THEME, "failed": failed, "page_errors": errors}, ensure_ascii=False))
    if failed or errors: sys.exit(1)

asyncio.run(main())
