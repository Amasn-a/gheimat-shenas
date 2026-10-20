"""Owner brief of 2026-10-20: visual QA at 360×640, 390×844 and 430×932 against the running development backend.
Plays real runs (the hidden price is read only through qa/oracle.py, test-only) and saves a screenshot per state, then
checks every screenshot's DOM for clipping, overlap, horizontal page scroll, touch targets under 44 px and RTL.

usage: python3 qa/brief.py [outdir] [WxH ...]"""
import asyncio, json, os, sys, time, uuid, urllib.request
from playwright.async_api import async_playwright
sys.path.insert(0, os.path.dirname(__file__))
import oracle

BASE = os.environ.get("PE_BASE", "http://127.0.0.1:8080")
OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/qa-brief"
SIZES = [tuple(map(int, a.split("x"))) for a in sys.argv[2:]] or [(360, 640), (390, 844), (430, 932)]
os.makedirs(OUT, exist_ok=True)
log, problems = [], []

def dev(path, body=None):
    req = urllib.request.Request(BASE + "/dev/v1/price-expert" + path, data=None if body is None else json.dumps(body).encode(),
                                 method="POST" if body is not None else "GET", headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req).read() or b"{}")

async def view(pg): return await pg.evaluate("document.getElementById('app').dataset.view")

async def wait_view(pg, prefixes, timeout=12000):
    prefixes = prefixes if isinstance(prefixes, tuple) else (prefixes,)
    t = time.time()
    while (time.time() - t) * 1000 < timeout:
        v = await view(pg)
        if v and v.startswith(prefixes): return v
        await pg.wait_for_timeout(40)
    raise AssertionError(f"timeout waiting for {prefixes}, at {await view(pg)}")

# DOM audit run on every screenshot: what a reviewer would otherwise check by eye
AUDIT = r"""() => {
  const out = [], W = innerWidth, H = innerHeight, vis = e => { const s = getComputedStyle(e); return s.visibility !== 'hidden' && s.display !== 'none' && +s.opacity > 0.05; };
  if (document.documentElement.scrollWidth > W + 1) out.push('horizontal page scroll ' + document.documentElement.scrollWidth);
  if (document.documentElement.dir !== 'rtl' && document.dir !== 'rtl' && getComputedStyle(document.getElementById('app')).direction !== 'rtl') out.push('not RTL');
  const blocked = el => { const r = el.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2; const top = document.elementFromPoint(Math.min(W - 1, Math.max(0, x)), Math.min(H - 1, Math.max(0, y))); return top && !el.contains(top) && !top.closest('.pe-tour__block,.pe-tour__hole,.v3-sheetwrap,.pe-cphost,.mo-layer') ? top : null; };
  for (const b of document.querySelectorAll('#app button, #app [role=button]')) {
    if (!vis(b) || b.closest('[inert]') || b.closest('.v3-hist__scroll')) continue;
    const r = b.getBoundingClientRect(); if (!r.width) continue;
    if (r.width < 43.5 || r.height < 39.5) out.push(`small target ${Math.round(r.width)}x${Math.round(r.height)} «${(b.getAttribute('aria-label') || b.textContent).trim().slice(0, 24)}»`);
    if (r.right > W + 1 || r.left < -1 || r.bottom > H + 1 || r.top < -1) out.push(`button outside viewport «${b.textContent.trim().slice(0, 24)}»`);
    const o = blocked(b); if (o && !b.disabled && !b.closest('.v3-stage [inert]')) out.push(`button covered «${b.textContent.trim().slice(0, 20)}» by ${o.className || o.tagName}`);
  }
  for (const t of document.querySelectorAll('#app h1, #app h2, #app h3, #app p, #app b, #app span')) {
    if (!vis(t) || !t.textContent.trim() || t.closest('.v3-hist__scroll') || t.closest('[aria-hidden=true]') || t.closest('.pe-sr')) continue;
    const r = t.getBoundingClientRect(); if (!r.width) continue;
    if (r.right > W + 1 || r.left < -1) out.push(`text past the screen edge «${t.textContent.trim().slice(0, 24)}»`);
    const cs = getComputedStyle(t);
    if (cs.overflow === 'hidden' && t.scrollWidth > t.clientWidth + 2 && cs.webkitLineClamp === 'none' && cs.textOverflow !== 'ellipsis') out.push(`clipped text «${t.textContent.trim().slice(0, 24)}»`);
  }
  const scrollers = [...document.querySelectorAll('#app *')].filter(e => { const s = getComputedStyle(e); return /(auto|scroll)/.test(s.overflowY) && e.scrollHeight > e.clientHeight + 2; });
  if (scrollers.length) out.push('vertical scroll in ' + scrollers.map(e => e.className.split(' ')[0]).join(','));
  return out;
}"""

async def shot(pg, size, name):
    v = await view(pg)
    w, h = size
    await pg.screenshot(path=f"{OUT}/{w}x{h}_{name}.png")
    issues = await pg.evaluate(AUDIT)
    log.append(f"{w}x{h} {name}: {v}" + (f"  !! {issues}" if issues else ""))
    if issues: problems.append((f"{w}x{h}", name, v, issues))

async def tap(pg, act): await pg.click(f'[data-act="{act}"]')

async def player(b, size, onboarded=True, user=None):
    user = user or "qb" + uuid.uuid4().hex[:6]
    dev(f"/fake-coin/u_{user}", {"balance": 120})
    if onboarded: dev(f"/player/u_{user}", {"onboarded": True})
    ctx = await b.new_context(viewport={"width": size[0], "height": size[1]}, device_scale_factor=2, color_scheme="dark")   # dark system: must stay light
    pg = await ctx.new_page()
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.on("console", lambda m: pg.errors.append(m.text) if m.type == "error" else None)
    await pg.goto(f"{BASE}/play/dev.html?user={user}&panel=0")
    return user, pg

async def answer(pg, user, correct):
    await tap(pg, "ans-" + oracle.choice("u_" + user, correct))

async def reveal(pg):
    await tap(pg, "reveal"); await wait_view(pg, "G03.QUESTION_ACTIVE"); await pg.wait_for_timeout(250)

async def run_main(b, size):
    user, pg = await player(b, size)
    await wait_view(pg, "G01.READY"); await pg.wait_for_timeout(400); await shot(pg, size, "01_start")
    await tap(pg, "start"); await wait_view(pg, "G03.READY"); await pg.wait_for_timeout(500); await shot(pg, size, "02_ready_first_product")
    await reveal(pg); await shot(pg, size, "03_question_white_timer")
    await pg.wait_for_timeout(4200); await shot(pg, size, "04_timer_towards_orange")
    # an answer whose response is held 2.5 s: the countdown is replaced by the processing state
    async def slow(route): await asyncio.sleep(2.5); await route.continue_()
    await pg.route("**/questions/*/answer", slow)
    await answer(pg, user, True); await pg.wait_for_timeout(400); await shot(pg, size, "05_answer_processing")
    await wait_view(pg, "G03.CORRECT_FEEDBACK", 8000); await pg.wait_for_timeout(350); await shot(pg, size, "06_correct")
    await pg.unroute("**/questions/*/answer")
    for i in range(2):
        await wait_view(pg, "G03.READY"); await pg.wait_for_timeout(500); await reveal(pg); await answer(pg, user, True)
    await wait_view(pg, "G04.REACHED", 8000); await pg.wait_for_timeout(900); await shot(pg, size, "07_checkpoint_celebration")
    await pg.wait_for_timeout(400)
    await pg.click(".v3-cp"); await wait_view(pg, "G03.READY"); await pg.wait_for_timeout(600)
    await reveal(pg); await pg.wait_for_timeout(7200); await shot(pg, size, "08_timer_low_red")
    await answer(pg, user, False)
    await wait_view(pg, "G05.OFFER", 9000); await pg.wait_for_timeout(500); await shot(pg, size, "09_offer_with_balance")
    dev(f"/fake-coin/u_{user}", {"script": [{"op": "charge", "outcome": "late_success", "late_ms": 9000}]})
    await tap(pg, "continue"); await pg.wait_for_timeout(500); await shot(pg, size, "10_purchase_pending")
    await wait_view(pg, "G06.UNKNOWN", 9000); await pg.wait_for_timeout(300); await shot(pg, size, "11_purchase_recovery_after_visible_zero")
    await wait_view(pg, ("G06.SUCCESS", "G03.READY"), 20000); await pg.wait_for_timeout(700); await shot(pg, size, "12_purchase_confirmed")
    await reveal(pg); await answer(pg, user, False)
    await wait_view(pg, "G07.", 12000); await pg.wait_for_timeout(1800); await shot(pg, size, "13_end_screen_loss_in_view")
    hs = await pg.evaluate("""() => { const s = document.querySelector('[data-hscroll]'); const l = s && s.querySelector('[data-loss]'); if (!s || !l) return null;
        const a = s.getBoundingClientRect(), r = l.getBoundingClientRect(); return { inView: r.left >= a.left - 1 && r.right <= a.right + 1, sl: s.scrollLeft, n: s.children.length,
        order: [...s.children].map(c => +c.dataset.turn), ids: [...s.children].map(c => c.dataset.qid) }; }""")
    log.append(f"{size} history: {hs}")
    if not hs or not hs["inView"]: problems.append((size, "13", "history", f"losing turn not in view: {hs}"))
    elif hs["order"] != sorted(hs["order"]): problems.append((size, "13", "history", f"not chronological: {hs['order']}"))
    # manual scroll back to the start survives a re-render (pending reward refreshes re-render the screen)
    await pg.evaluate("() => { const s = document.querySelector('[data-hscroll]'); s.scrollLeft = s.scrollWidth; s.dispatchEvent(new Event('scroll')); }")
    await pg.evaluate("() => window.__rerender && window.__rerender()")
    await pg.wait_for_timeout(300); await shot(pg, size, "14_end_screen_scrolled_to_start")
    await tap(pg, "share"); await wait_view(pg, "G09.ASSET"); await pg.wait_for_timeout(300); await shot(pg, size, "15_share_sheet")
    await tap(pg, "share-close"); await pg.wait_for_timeout(200)
    await tap(pg, "to-start"); await wait_view(pg, ("G01.READY", "G01.DAILY")); await pg.wait_for_timeout(400); await shot(pg, size, "16_back_to_start")
    assert not pg.errors, pg.errors
    await pg.context.close()

async def run_timeout(b, size):
    user, pg = await player(b, size)
    await wait_view(pg, "G01.READY"); await tap(pg, "start"); await wait_view(pg, "G03.READY"); await pg.wait_for_timeout(400)
    await reveal(pg)
    await wait_view(pg, "G03.TIME_UP_PENDING", 12000); await pg.wait_for_timeout(300); await shot(pg, size, "20_time_up_waiting_for_server")
    await wait_view(pg, "G03.TIMEOUT", 9000); await pg.wait_for_timeout(400); await shot(pg, size, "21_timeout_feedback")
    await wait_view(pg, "G05.OFFER", 9000); await pg.wait_for_timeout(10_300); await shot(pg, size, "22_offer_visible_time_up")
    await wait_view(pg, "G07.", 9000); await pg.wait_for_timeout(1500); await shot(pg, size, "23_end_screen_timeout")
    assert not pg.errors, pg.errors
    await pg.context.close()

async def run_onboarding(b, size):
    user, pg = await player(b, size, onboarded=False)
    dev(f"/player/u_{user}", {"attended_days": 3})       # the daily reward must wait until the onboarding is over
    names = {"FIRST": "30_onb_first_product", "REVEAL": "31_onb_reveal", "SECOND": "32_onb_second", "TIMER": "33_onb_timer", "ANSWER": "34_onb_answer", "PROGRESS": "35_onb_progress", "DONE": "36_onb_done"}
    await wait_view(pg, "G02.ONBOARDING.FIRST")
    while True:
        v = await view(pg)
        if not v.startswith("G02."): break
        step = v.split(".")[-1]; await pg.wait_for_timeout(450); await shot(pg, size, names[step])
        # touches outside the lesson do nothing
        await pg.mouse.click(5, size[1] - 5); assert (await view(pg)) == v, "a touch outside moved the lesson"
        if step == "REVEAL": await tap(pg, "tour-reveal")
        elif step == "ANSWER": await tap(pg, "tour-ans-higher")
        elif step == "DONE": await tap(pg, "tour-finish")
        else: await tap(pg, "tour-next")
        await pg.wait_for_timeout(250)
    await wait_view(pg, "G01.DAILY_REWARD.CLAIMABLE"); await pg.wait_for_timeout(400); await shot(pg, size, "40_daily_reward")
    await tap(pg, "daily-claim"); await wait_view(pg, "G01.DAILY_REWARD.CLAIMED"); await pg.wait_for_timeout(300); await shot(pg, size, "41_daily_reward_claimed")
    await tap(pg, "daily-close"); await wait_view(pg, "G01.READY"); await pg.reload(); await wait_view(pg, ("G01.READY", "G01.DAILY"))
    assert (await view(pg)) == "G01.READY.NEW_PLAYER", "no second modal after the claim, no onboarding after completion: " + await view(pg)
    await tap(pg, "guide"); await wait_view(pg, "G02.GUIDE.FIRST"); await pg.wait_for_timeout(400); await shot(pg, size, "42_guide_reopened")
    assert not pg.errors, pg.errors
    await pg.context.close()

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        for size in SIZES:
            for fn in (run_onboarding, run_main, run_timeout):
                try: await asyncio.wait_for(fn(b, size), 150)
                except BaseException as e:
                    if isinstance(e, KeyboardInterrupt): raise
                    problems.append((size, fn.__name__, "FAILED", f"{type(e).__name__}: {e}"))
        await b.close()
    print("\n".join(log))
    print(json.dumps({"problems": [list(map(str, x)) for x in problems]}, ensure_ascii=False, indent=1))
    if problems: sys.exit(1)

asyncio.run(main())
