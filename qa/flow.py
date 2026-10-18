"""Visual QA driver: plays complete runs in Chromium at 375×812 against the running development backend and saves a
screenshot per state. The hidden price is read only through qa/oracle.py (database, test-only) to pick right/wrong.

usage: python3 qa/flow.py [outdir] [theme]"""
import asyncio, json, os, sys, time, uuid, urllib.request
from playwright.async_api import async_playwright
sys.path.insert(0, os.path.dirname(__file__))
import oracle

BASE = os.environ.get("PE_BASE", "http://127.0.0.1:8080")
OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/qa"
THEME = sys.argv[2] if len(sys.argv) > 2 else "light"
os.makedirs(OUT, exist_ok=True)
log = []

def dev(user, body):
    req = urllib.request.Request(BASE + f"/dev/v1/price-expert/fake-coin/u_{user}", data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    urllib.request.urlopen(req).read()

async def view(pg):
    return await pg.evaluate("document.getElementById('app').dataset.view")

async def wait_view(pg, prefix, timeout=8000):
    t = time.time()
    while (time.time() - t) * 1000 < timeout:
        v = await view(pg)
        if v and (v.startswith(prefix) if not isinstance(prefix, tuple) else v.startswith(prefix)):
            return v
        await pg.wait_for_timeout(50)
    raise AssertionError(f"timeout waiting for {prefix}, at {await view(pg)}")

async def shot(pg, name):
    v = await view(pg)
    await pg.screenshot(path=f"{OUT}/{name}.png")
    log.append(f"{name}: {v}")

async def click_act(pg, act):
    await pg.click(f'[data-act="{act}"]')

async def answer(pg, user, correct):
    c = oracle.choice("u_" + user, correct)
    await click_act(pg, "ans-" + c)

async def main():
    user = "qa" + uuid.uuid4().hex[:6]
    dev(user, {"balance": 120})
    async with async_playwright() as p:
        b = await p.chromium.launch()
        ctx = await b.new_context(viewport={"width": 375, "height": 812}, device_scale_factor=2, color_scheme=THEME)
        pg = await ctx.new_page()
        errors = []
        pg.on("pageerror", lambda e: errors.append(str(e)))
        pg.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
        await pg.goto(f"{BASE}/play/dev.html?user={user}&panel=0&theme={THEME}")
        await wait_view(pg, "G02.FIRST"); await shot(pg, "01_help_first")
        await click_act(pg, "help-done")
        await wait_view(pg, "G01.READY"); await shot(pg, "02_start")
        await click_act(pg, "start")
        await wait_view(pg, "G03.READY"); await pg.wait_for_timeout(300); await shot(pg, "03_ready_big_reference")
        # three correct answers → checkpoint 3
        for i in range(3):
            await click_act(pg, "reveal")
            await wait_view(pg, "G03.QUESTION_ACTIVE")
            await pg.wait_for_timeout(500)
            if i == 0: await shot(pg, "04_question_active")
            await answer(pg, user, True)
            await wait_view(pg, "G03.CORRECT_FEEDBACK")
            await pg.wait_for_timeout(350)
            if i == 0: await shot(pg, "05_correct_feedback")
            await wait_view(pg, ("G03.READY", "G04.REACHED"), 4000)
            if i < 2: await pg.wait_for_timeout(700)
        await wait_view(pg, "G04.REACHED", 3000)
        await pg.wait_for_timeout(500); await shot(pg, "06_checkpoint")
        # an early tap (inside the guard) must not close it
        t_open = time.time()
        await pg.mouse.click(187, 400)
        await pg.wait_for_timeout(150)
        assert (await view(pg)) == "G04.REACHED", "checkpoint closed by an early tap"
        # automatic continuation (F-01): closes by itself; measure
        await wait_view(pg, "G03.READY", 9000)
        log.append(f"checkpoint auto-closed after {time.time() - t_open:.2f}s (expected ≈ 0.4 + 5.0 s from opening)")
        await pg.wait_for_timeout(500)
        # a wrong answer → feedback → offer → continue
        await click_act(pg, "reveal")
        await wait_view(pg, "G03.QUESTION_ACTIVE"); await pg.wait_for_timeout(400)
        await answer(pg, user, False)
        await wait_view(pg, "G03.WRONG_FEEDBACK"); await pg.wait_for_timeout(400); await shot(pg, "07_wrong_feedback")
        t_fb = time.time()
        await wait_view(pg, "G05.OFFER", 6000)
        log.append(f"offer appeared {time.time() - t_fb:.2f}s after the wrong verdict (hold 1.7 s + activation)")
        await pg.wait_for_timeout(600); await shot(pg, "08_offer")
        await click_act(pg, "continue")
        await wait_view(pg, ("G06.SUCCESS", "G03.READY"), 6000); await pg.wait_for_timeout(700); await shot(pg, "09_continue_success")
        # the next miss ends the run (continue limit 1)
        await click_act(pg, "reveal")
        await wait_view(pg, "G03.QUESTION_ACTIVE"); await pg.wait_for_timeout(300)
        await answer(pg, user, False)
        await wait_view(pg, "G03.WRONG_FEEDBACK"); await pg.wait_for_timeout(300); await shot(pg, "10_wrong_limit_used")
        await wait_view(pg, "G07.", 8000)
        await wait_view(pg, ("G07.STANDARD", "G07.NEW_RECORD", "G07.NO_CHECKPOINT", "G07.QUOTA", "G07.REWARD"), 8000)
        await pg.wait_for_timeout(2500); await shot(pg, "11_result")
        await click_act(pg, "products")
        await wait_view(pg, "G08.LIST"); await pg.wait_for_timeout(900); await shot(pg, "12_products_loss")
        # no page or nested scrolling except the product list
        scroll = await pg.evaluate("""() => { const out = []; document.querySelectorAll('#app *').forEach(el => { const cs = getComputedStyle(el);
            if ((el.scrollHeight > el.clientHeight + 1 && ['auto','scroll'].includes(cs.overflowY)) && !el.closest('[data-scroll]')) out.push(el.className); }); return out; }""")
        log.append(f"unexpected scroll containers on G08: {scroll}")
        log.append(f"page errors: {errors}")
        await b.close()
    print("\n".join(log))

asyncio.run(main())
