"""Motion check: records a video of reveal → correct → promotion → checkpoint at 375×812 and extracts frames at 30 fps
around each transition, so motion can be inspected frame by frame (screenshots are too slow to catch 250–550 ms moves).

usage: python3 qa/motion_video.py [outdir] [reduce|no-preference]"""
import asyncio, os, subprocess, sys, time, uuid, json, urllib.request
from playwright.async_api import async_playwright
sys.path.insert(0, os.path.dirname(__file__))
import oracle

BASE = os.environ.get("PE_BASE", "http://127.0.0.1:8080")
OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/qa-motion"
MOTION = sys.argv[2] if len(sys.argv) > 2 else "no-preference"
os.makedirs(OUT, exist_ok=True)
marks = {}


async def view(pg): return await pg.evaluate("document.getElementById('app').dataset.view")


async def wait_view(pg, prefixes, timeout=8000):
    t = time.time()
    while (time.time() - t) * 1000 < timeout:
        v = await view(pg)
        if v and v.startswith(prefixes): return v
        await pg.wait_for_timeout(20)
    raise AssertionError(f"timeout waiting for {prefixes} at {await view(pg)}")


async def main():
    user = "mv" + uuid.uuid4().hex[:5]
    urllib.request.urlopen(urllib.request.Request(BASE + f"/dev/v1/price-expert/fake-coin/u_{user}", data=json.dumps({"balance": 120}).encode(),
                                                  method="POST", headers={"Content-Type": "application/json"})).read()
    async with async_playwright() as p:
        b = await p.chromium.launch()
        ctx = await b.new_context(viewport={"width": 375, "height": 812}, record_video_dir=OUT, record_video_size={"width": 375, "height": 812},
                                  reduced_motion=MOTION)
        pg = await ctx.new_page()
        t0 = time.time()
        mark = lambda k: marks.__setitem__(k, round(time.time() - t0, 3))
        await pg.goto(f"{BASE}/play/dev.html?user={user}&panel=0")
        await wait_view(pg, ("G02.ONBOARDING",)); await pg.evaluate("() => fetch('/dev/v1/price-expert/player/u_' + new URLSearchParams(location.search).get('user'), {method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{\\"onboarded\\":true}'}).then(() => location.reload())")
        await wait_view(pg, ("G01.READY",)); await pg.wait_for_timeout(400)
        mark("start"); await pg.click('[data-act="start"]')
        await wait_view(pg, ("G03.READY",)); await pg.wait_for_timeout(1200)
        for i in range(3):
            mark(f"reveal{i}"); await pg.click('[data-act="reveal"]')
            await wait_view(pg, ("G03.QUESTION_ACTIVE",)); await pg.wait_for_timeout(900)
            mark(f"answer{i}"); await pg.click('[data-act="ans-' + oracle.choice("u_" + user, True) + '"]')
            await wait_view(pg, ("G03.READY", "G04.REACHED"), 6000)
            await pg.wait_for_timeout(900)
        await wait_view(pg, ("G04.REACHED",), 4000); mark("checkpoint")
        await pg.wait_for_timeout(2000)
        mark("cp-tap"); await pg.mouse.click(187, 420)
        await wait_view(pg, ("G03.READY",), 4000); await pg.wait_for_timeout(1200)
        mark("end")
        video = await pg.video.path()
        await ctx.close(); await b.close()
    # frames: 30 fps for 1.6 s after each marked action
    for k in ("start", "reveal0", "answer0", "checkpoint", "cp-tap"):
        d = os.path.join(OUT, k); os.makedirs(d, exist_ok=True)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", str(max(0, marks[k] - 0.1)), "-t", "1.8", "-i", video, "-vf", "fps=30,scale=188:-1",
                        os.path.join(d, "f%02d.png")], check=True)
    print(json.dumps({"video": video, "marks": marks, "motion": MOTION}))

asyncio.run(main())
