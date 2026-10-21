"""Server-driven states of the redesigned player (2026-10-21) not covered by qa/lifecycle.py or qa/redesign.py, against the
running development backend. Same harness as qa/lifecycle.py (independent checks, pass/fail summary, non-zero exit on a
failure or a page error); a screenshot of each reached state goes to $PE_QA_OUT (default /tmp/qa-states).
game_off and campaign change global settings for a moment: do not run this next to other QA scripts.

  replacement      a run on another device: G01.READY.OTHER_DEVICE_RUN; starting here ends it there (G11.OTHER_DEVICE)
  blocked          a block during a run → G11.BLOCKED on the next action
  game_off         the kill switch → G11.GAME_OFF at entry
  offline_before   offline before «نشان بده»: G10.OFFLINE_BEFORE_RELEASE, no question released; back online it plays on
  campaign         campaign home (G01.READY.CAMPAIGN); the code earned at a checkpoint shows on the loss screen and copies

usage: python3 qa/states.py [check ...]"""
import asyncio, os, sys, time, uuid
sys.path.insert(0, os.path.dirname(__file__))
from lifecycle import admin, page, wait_view, view, tap, start, reveal, answer, correct_n, run_checks

OUT = os.environ.get("PE_QA_OUT", "/tmp/qa-states")
os.makedirs(OUT, exist_ok=True)
async def shot(pg, name): await pg.screenshot(path=f"{OUT}/{name}.png")


async def c_replacement(b):
    u, dev_a, dev_b = "sr" + uuid.uuid4().hex[:6], str(uuid.uuid4()), str(uuid.uuid4())
    a = await page(b, u, device=dev_a)
    await start(a)
    bp = await page(b, u, device=dev_b)
    await wait_view(bp, "G01.READY.OTHER_DEVICE_RUN"); await shot(bp, "replace-1-other-device-home")
    await start(bp)
    await a.wait_for_timeout(700); await tap(a, "reveal")
    await wait_view(a, "G11.OTHER_DEVICE", 8000); await shot(a, "replace-2-old-device")
    await tap(a, "home"); await wait_view(a, "G01.", 8000)
    return f"new device plays ({await view(bp)}); old device → G11.OTHER_DEVICE → home"


async def c_blocked(b):
    pg = await page(b)
    await start(pg)
    admin("POST", f"/players/u_{pg.u}/block", {"reason": "QA", "review_reference": "QA-1"})
    try:
        await pg.wait_for_timeout(700); await tap(pg, "reveal")
        await wait_view(pg, "G11.BLOCKED", 8000); await shot(pg, "blocked")
        assert "بازی غیرفعال شده است" in await pg.inner_text(".q-state")
    finally:
        admin("POST", f"/players/u_{pg.u}/unblock", {"reason": "QA"})
    return "G11.BLOCKED with its exact copy"


async def c_game_off(b):
    admin("POST", "/game/availability", {"enabled": False, "player_message": None, "reason": "QA"})
    try:
        pg = await page(b)
        await wait_view(pg, "G11.GAME_OFF", 6000); await shot(pg, "game-off")
    finally:
        admin("POST", "/game/availability", {"enabled": True, "player_message": None, "reason": "QA"})
    return "G11.GAME_OFF at entry"


async def c_offline_before(b):
    pg = await page(b)
    await start(pg)
    await pg.context.set_offline(True)
    await wait_view(pg, "G10.OFFLINE_BEFORE_RELEASE", 4000); await shot(pg, "offline-before-release")
    assert await pg.locator('[data-act="reveal"]').count() == 0, "«نشان بده» must wait for the connection"
    await pg.wait_for_timeout(1500)
    await pg.context.set_offline(False)
    await wait_view(pg, "G03.READY", 6000)                             # no question was released; nothing timed out
    assert await pg.evaluate("G.S.q === null && G.S.run.score === 0")
    await reveal(pg)
    return "recovered to READY and released a question normally"


async def c_campaign(b):
    for c in admin("GET", "/campaigns"):
        if c["status"] != "ENDED": admin("POST", f"/campaigns/{c['campaign_id']}/end", {"reason": "QA cleanup"})
    iso = lambda t: time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t))
    now = time.time()
    camp = admin("POST", "/campaigns", {"name": "کمپین QA", "starts_at": iso(now - 60), "ends_at": iso(now + 3600), "code_checkpoint_ids": ["cp_5"],
                                       "code_amount_toman": 50000, "validity_days": 14, "terms": "قابل استفاده برای خرید بالای ۵۰۰ هزار تومان.",
                                       "code_cap": 10, "budget_toman": 500000})
    try:
        pg = await page(b, balance=0)                                  # no continue: the loss screen is the run's result
        await pg.context.grant_permissions(["clipboard-read", "clipboard-write"], origin=pg.url.split("/play")[0])
        await wait_view(pg, "G01.READY.CAMPAIGN", 4000); await shot(pg, "campaign-1-home")
        await start(pg); await correct_n(pg, 5)
        await wait_view(pg, "G04.REACHED", 6000); await pg.wait_for_timeout(800); await shot(pg, "campaign-2-checkpoint")
        await tap(pg, "cp-go"); await reveal(pg); await answer(pg, False)
        await wait_view(pg, ("G05.NO_FUNDS", "G05.LOSS"), 10000)        # LOSS when the small dev catalog has no product left
        await pg.wait_for_selector('.q-code [data-act="copy"]', timeout=8000)   # «در حال صدور کد…» first: issued just after the run ends
        code = await pg.inner_text(".q-code__val b"); await shot(pg, "campaign-3-code")
        await tap(pg, "copy"); await pg.wait_for_timeout(300)
        assert "کپی شد" in await pg.inner_text(".q-code"), "copy not confirmed"
        assert await pg.evaluate("navigator.clipboard.readText()") == code
        return f"code {code} on the loss screen; copied"
    finally:
        admin("POST", f"/campaigns/{camp['campaign_id']}/end", {"reason": "QA end"})


CHECKS = [c_replacement, c_blocked, c_game_off, c_offline_before, c_campaign]

if __name__ == "__main__":
    sys.exit(1 if asyncio.run(run_checks(CHECKS, set(sys.argv[1:]))) else 0)
