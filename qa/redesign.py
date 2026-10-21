"""Redesign of 2026-10-21: visual QA at 360×640, 390×844 and 430×932 against the running development backend.
Plays real runs (the hidden price is read only through qa/oracle.py, test-only), saves a screenshot per state and checks
each one's DOM: horizontal page scroll, controls outside the viewport or covered, touch targets under 44 px, text past the
screen edge, back/close at the physical right, button icons at the left, and that every rendered font is Digikala.

usage: python3 qa/redesign.py [outdir] [WxH ...]"""
import asyncio, json, os, sys, time, uuid, urllib.request
from playwright.async_api import async_playwright
sys.path.insert(0, os.path.dirname(__file__))
import oracle

BASE = os.environ.get("PE_BASE", "http://127.0.0.1:8080")
OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/qa-redesign"
SIZES = [tuple(map(int, a.split("x"))) for a in sys.argv[2:]] or [(360, 640), (390, 844), (430, 932)]
os.makedirs(OUT, exist_ok=True)
log, problems = [], []

def dev(path, body=None):
    req = urllib.request.Request(BASE + "/dev/v1/price-expert" + path, data=None if body is None else json.dumps(body).encode(),
                                 method="POST" if body is not None else "GET", headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req).read() or b"{}")

async def view(pg): return await pg.evaluate("document.getElementById('app').dataset.view")

async def wait_view(pg, prefixes, timeout=14000):
    prefixes = prefixes if isinstance(prefixes, tuple) else (prefixes,)
    t = time.time()
    while (time.time() - t) * 1000 < timeout:
        v = await view(pg)
        if v and v.startswith(prefixes): return v
        await pg.wait_for_timeout(40)
    raise AssertionError(f"timeout waiting for {prefixes}, at {await view(pg)}")

AUDIT = r"""() => {
  const out = [], W = innerWidth, H = innerHeight;
  const vis = e => { const s = getComputedStyle(e); return s.visibility !== 'hidden' && s.display !== 'none' && +s.opacity > 0.05 && !e.closest('[inert]'); };
  if (document.documentElement.scrollWidth > W + 1) out.push('horizontal page scroll ' + document.documentElement.scrollWidth);
  if (document.documentElement.dir !== 'rtl') out.push('not RTL');
  const host = [...document.querySelectorAll('.q-cphost .q-cp')].length ? document.querySelector('.q-cphost') : document.querySelector('.q-screenhost');
  for (const b of host.querySelectorAll('button')) {
    if (!vis(b) || b.closest('.q-hist__row')) continue;
    const r = b.getBoundingClientRect(); if (!r.width) continue;
    const name = (b.getAttribute('aria-label') || b.textContent).trim().slice(0, 24);
    if (r.width < 43.5 || r.height < 43.5) out.push(`small target ${Math.round(r.width)}x${Math.round(r.height)} «${name}»`);
    if (r.right > W + 1 || r.left < -1 || r.bottom > H + 1 || r.top < -1) out.push(`button outside viewport «${name}»`);
    const x = r.left + r.width / 2, y = r.top + r.height / 2, top = document.elementFromPoint(Math.min(W - 1, Math.max(0, x)), Math.min(H - 1, Math.max(0, y)));
    if (top && !b.contains(top) && !top.closest('.mo-layer,.q-spot') && !b.closest('.q-scrim') === !!top.closest('.q-scrim')) out.push(`button covered «${name}» by ${top.className || top.tagName}`);
    // icons inside action buttons are written after the label: physically on the left
    const i = b.querySelector(':scope > .q-btn__i, :scope > .q-ab__i'), s = b.querySelector(':scope > span');
    if (i && s && i.getBoundingClientRect().left > s.getBoundingClientRect().left) out.push(`icon not on the left «${name}»`);
  }
  for (const b of host.querySelectorAll('.q-iconbtn[data-act="back"],.q-iconbtn[data-act="exit-app"],.q-iconbtn[data-act="loss-home"],.q-iconbtn[data-act="guide-close"],.q-iconbtn[data-act="onb-prev"],.q-sheet__x')) {
    if (!vis(b)) continue;
    const r = b.getBoundingClientRect(); if (r.left + r.width / 2 < W / 2) out.push(`back/close not on the right «${b.getAttribute('aria-label')}»`);
  }
  for (const t of host.querySelectorAll('h1, h2, h3, p, b, span, dd, dt, small')) {
    if (!vis(t) || !t.textContent.trim() || t.closest('.q-hist__row') || t.closest('.pe-sr')) continue;
    const r = t.getBoundingClientRect(); if (!r.width) continue;
    if (r.right > W + 1 || r.left < -1) out.push(`text past the screen edge «${t.textContent.trim().slice(0, 24)}»`);
    const f = getComputedStyle(t).fontFamily; if (!/^"?Digikala"?/.test(f)) out.push('font ' + f);
  }
  return [...new Set(out)];
}"""

async def shot(pg, size, name):
    v = await view(pg)
    w, h = size
    await pg.screenshot(path=f"{OUT}/{w}x{h}_{name}.png")
    issues = await pg.evaluate(AUDIT)
    fonts = await pg.evaluate("[...document.fonts].filter(f => f.status === 'loaded').map(f => f.family)")
    if any(f.strip('"') != 'Digikala' for f in fonts): issues.append('loaded non-Digikala font ' + str(fonts))
    log.append(f"{w}x{h} {name}: {v}" + (f"  !! {issues}" if issues else ""))
    if issues: problems.append((f"{w}x{h}", name, v, issues))

async def tap(pg, act): await pg.click(f'[data-act="{act}"]')

async def player(b, size, onboarded=True, balance=120, user=None, route=None):
    user = user or "qr" + uuid.uuid4().hex[:6]
    dev(f"/fake-coin/u_{user}", {"balance": balance})
    if onboarded: dev(f"/player/u_{user}", {"onboarded": True})
    ctx = await b.new_context(viewport={"width": size[0], "height": size[1]}, device_scale_factor=2, color_scheme="dark", reduced_motion="no-preference")
    pg = await ctx.new_page()
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.on("console", lambda m: pg.errors.append(m.text) if m.type == "error" else None)
    if route: await route(pg)
    await pg.goto(f"{BASE}/play/dev.html?user={user}&panel=0")
    return user, pg

async def at_home(pg, timeout=14000):
    """The home after a run; today's first finished run earns the daily reward, whose modal is closed here."""
    v = await wait_view(pg, ("G01.READY", "G01.QUOTA", "G01.DAILY_REWARD"), timeout)
    if v.startswith("G01.DAILY_REWARD"):
        await tap(pg, "daily-close"); v = await wait_view(pg, ("G01.READY", "G01.QUOTA"), timeout)
    return v

async def answer(pg, user, correct):
    await tap(pg, "ans-" + oracle.choice("u_" + user, correct))

async def reveal(pg):
    await wait_view(pg, ("G03.READY", "G06.SUCCESS")); await pg.wait_for_timeout(650)
    await tap(pg, "reveal"); await wait_view(pg, "G03.QUESTION_ACTIVE"); await pg.wait_for_timeout(350)

async def correct_to(pg, user, n, start):
    """Answers correctly from score `start` to `n`, closing celebrations on the way; returns when the screen is READY."""
    for s in range(start, n):
        await reveal(pg); await answer(pg, user, True)
        v = await wait_view(pg, ("G03.READY", "G04.REACHED"), 9000)
        await pg.wait_for_timeout(200)
        if (await view(pg)) == "G04.REACHED" and s + 1 < n:
            await pg.wait_for_timeout(700); await tap(pg, "cp-go")

async def run_main(b, size):
    user, pg = await player(b, size)
    await wait_view(pg, "G01.READY"); await pg.wait_for_timeout(500); await shot(pg, size, "01_home_new_player")
    await tap(pg, "start"); await wait_view(pg, "G03.READY"); await pg.wait_for_timeout(500); await shot(pg, size, "02_ready")
    await reveal(pg); await shot(pg, size, "03_question")
    await pg.wait_for_timeout(7300); await shot(pg, size, "04_last_3_seconds")
    async def slow(route):
        await asyncio.sleep(2.0)
        try: await route.fallback()
        except Exception: pass
    await pg.route("**/questions/*/answer", slow)
    await answer(pg, user, True); await pg.wait_for_timeout(300); await shot(pg, size, "05_answer_pending")
    await wait_view(pg, "G03.CORRECT_FEEDBACK", 8000); await pg.wait_for_timeout(350); await shot(pg, size, "06_correct")
    await pg.unroute("**/questions/*/answer")
    await wait_view(pg, "G03.READY"); await correct_to(pg, user, 5, 1)
    await wait_view(pg, "G04.REACHED", 9000); await pg.wait_for_timeout(900); await shot(pg, size, "07_checkpoint_5_badge_only")
    await tap(pg, "cp-go"); await wait_view(pg, "G03.READY"); await pg.wait_for_timeout(500); await shot(pg, size, "08_ready_after_checkpoint")
    await correct_to(pg, user, 10, 5)
    await wait_view(pg, "G04.REACHED", 9000); await pg.wait_for_timeout(900); await shot(pg, size, "09_checkpoint_10_with_coin")
    await tap(pg, "cp-go")
    await reveal(pg); await answer(pg, user, False)
    await wait_view(pg, "G03.WRONG_FEEDBACK", 8000); await pg.wait_for_timeout(350); await shot(pg, size, "10_wrong_feedback")
    await wait_view(pg, "G05.OFFER", 9000); await pg.wait_for_timeout(400); await shot(pg, size, "11_loss_continue")
    await pg.route("**/offers/*/continue", slow)
    await tap(pg, "continue"); await pg.wait_for_timeout(300); await shot(pg, size, "12_payment_pending")
    await pg.unroute("**/offers/*/continue")
    await wait_view(pg, "G06.SUCCESS", 10000); await pg.wait_for_timeout(500); await shot(pg, size, "13_continued_same_run")
    await reveal(pg); await answer(pg, user, False)
    await wait_view(pg, "G05.LOSS", 9000); await pg.wait_for_timeout(1200); await shot(pg, size, "14_loss_limit_used")
    await tap(pg, "loss-home"); await at_home(pg); await pg.wait_for_timeout(1500); await shot(pg, size, "15_home_history_balance")
    return pg.errors

async def run_timeout(b, size):
    user, pg = await player(b, size)
    await wait_view(pg, "G01.READY"); await tap(pg, "start"); await reveal(pg)
    await pg.wait_for_timeout(10150); await shot(pg, size, "16_time_up_checking")
    await wait_view(pg, "G03.TIMEOUT", 9000); await pg.wait_for_timeout(300); await shot(pg, size, "17_timeout_feedback")
    await wait_view(pg, "G05.OFFER", 9000); await pg.wait_for_timeout(300); await shot(pg, size, "18_loss_timeout_under_5")
    await tap(pg, "loss-home"); await wait_view(pg, "G01.", 9000)
    return pg.errors

async def run_no_funds(b, size):
    user, pg = await player(b, size, balance=2)
    await wait_view(pg, "G01.READY"); await tap(pg, "start"); await reveal(pg); await answer(pg, user, False)
    await wait_view(pg, "G05.NO_FUNDS", 10000); await pg.wait_for_timeout(1200); await shot(pg, size, "19_loss_no_funds")
    return pg.errors

async def run_onboarding(b, size):
    async def hold(pg):
        async def late(route):
            await asyncio.sleep(1.2)
            try: await route.fallback()
            except Exception: pass
        await pg.route("**/bootstrap", late)
    user, pg = await player(b, size, onboarded=False, route=hold)
    await pg.wait_for_timeout(500); await shot(pg, size, "20_splash")
    await pg.unroute("**/bootstrap")
    await wait_view(pg, "G02.ONBOARDING.P1"); await pg.wait_for_timeout(700); await shot(pg, size, "21_onboarding_1_first")
    await pg.wait_for_timeout(2600); await shot(pg, size, "22_onboarding_1_second")
    await pg.wait_for_timeout(2600); await shot(pg, size, "23_onboarding_1_timer")
    await tap(pg, "onb-next"); await wait_view(pg, "G02.ONBOARDING.P2"); await pg.wait_for_timeout(300); await shot(pg, size, "24_onboarding_2")
    await tap(pg, "onb-done"); await wait_view(pg, "G01.READY"); await pg.wait_for_timeout(300)
    await tap(pg, "guide"); await wait_view(pg, "G02.GUIDE"); await pg.wait_for_timeout(300); await shot(pg, size, "25_guide")
    await tap(pg, "guide-close"); await wait_view(pg, "G01.READY")
    return pg.errors

async def run_daily_quota(b, size):
    user = "qr" + uuid.uuid4().hex[:6]
    dev(f"/player/u_{user}", {"onboarded": True, "attended_days": 3})
    user, pg = await player(b, size, user=user)
    await wait_view(pg, "G01.DAILY_REWARD"); await pg.wait_for_timeout(400); await shot(pg, size, "26_daily_reward")
    await tap(pg, "daily-claim"); await wait_view(pg, "G01.READY", 12000); await pg.wait_for_timeout(600); await shot(pg, size, "27_after_claim_home")
    for i in range(3):   # spend the day's chances
        if (await at_home(pg)).startswith("G01.QUOTA"): break
        await tap(pg, "start"); await reveal(pg)
        if i == 0:
            await tap(pg, "back"); await pg.wait_for_timeout(300); await shot(pg, size, "28_leave_confirm")
            await tap(pg, "leave-yes"); continue
        await answer(pg, user, False); await wait_view(pg, ("G05.OFFER", "G05.LOSS"), 9000); await pg.wait_for_timeout(300); await tap(pg, "loss-home")
    await at_home(pg); await wait_view(pg, "G01.QUOTA_EXHAUSTED", 12000); await pg.wait_for_timeout(600); await shot(pg, size, "29_home_no_chances")
    return pg.errors

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        errs = []
        for size in SIZES:
            for f in (run_main, run_timeout, run_no_funds, run_onboarding, run_daily_quota):
                try: errs += await f(b, size)
                except Exception as e: problems.append((f"{size[0]}x{size[1]}", f.__name__, "exception", [repr(e)])); log.append(f"{size} {f.__name__} FAILED {e!r}")
        await b.close()
    print("\n".join(log))
    print("\nPROBLEMS:" if problems else "\nno layout problems found")
    for pr in problems: print(" ", pr)
    if errs: print("console errors:", sorted(set(errs))[:10])
    json.dump({"log": log, "problems": problems, "errors": errs}, open(f"{OUT}/report.json", "w"), ensure_ascii=False, indent=1)

asyncio.run(main())
