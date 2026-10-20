"""Admin QA driver: signs in to the standalone admin against the running development backend, exercises A01–A07 through
the UI (draft → validation error → publish, kill switch, search/review/block/unblock, history, campaigns, Viewer read-only)
and saves a screenshot per state. Fails loudly on page errors or unexpected UI.

usage: python3 qa/admin_flow.py [outdir] [theme]"""
import asyncio, json, os, sys, urllib.request, uuid
from playwright.async_api import async_playwright, expect

BASE = os.environ.get("PE_BASE", "http://127.0.0.1:8080")
OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/qa-admin"
THEME = sys.argv[2] if len(sys.argv) > 2 else "light"
os.makedirs(OUT, exist_ok=True)
errors, shots = [], []


def play_one_run(user):
    """Creates player history over the real player API so A05 has a run, operations and a player record."""
    dev = TestPlayer(user)
    dev.post("/runs", {})
    return dev


class TestPlayer:
    def __init__(self, name):
        self.name, self.device = name, str(uuid.uuid4())

    def post(self, path, body):
        req = urllib.request.Request(BASE + "/v1/gamification/price-expert" + path, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json", "Authorization": "Bearer dev." + self.name,
                                              "X-Device-Id": self.device, "Idempotency-Key": str(uuid.uuid4())})
        return json.loads(urllib.request.urlopen(req).read())


async def shot(pg, name):
    p = os.path.join(OUT, f"{len(shots):02d}-{name}-{THEME}.png")
    await pg.screenshot(path=p, full_page=True)
    shots.append(p)


async def sign_in(pg, user, pw):
    await pg.goto(BASE + "/admin/#A01")
    await pg.fill("input[name=username]", user)
    await pg.fill("input[name=password]", pw)
    await pg.click("button[type=submit]")
    await pg.wait_for_selector(".qa-shell")
    await pg.wait_for_selector(".qa-card .qa-field")


def admin_api(method, path, body=None, token=None, extra=None):
    h = {"Content-Type": "application/json", "Idempotency-Key": str(uuid.uuid4()), **(extra or {})}
    if token: h["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + "/admin/v1/gamification/price-expert" + path, method=method,
                                 data=None if body is None else json.dumps(body).encode(), headers=h)
    return json.loads(urllib.request.urlopen(req).read())


def discard_leftover_draft():
    t = admin_api("POST", "/session", {"username": "editor", "password": "editor-dev-password"})["token"]
    ov = admin_api("GET", "/config", token=t)
    if ov["draft"]: admin_api("POST", f"/config/versions/{ov['draft']['version']}/discard", {}, t, {"If-Match": ov["draft_revision"]})


async def main():
    discard_leftover_draft()
    user = "adm" + uuid.uuid4().hex[:6]
    play_one_run(user)
    async with async_playwright() as p:
        b = await p.chromium.launch()
        ctx = await b.new_context(viewport={"width": 1280, "height": 800}, color_scheme=THEME, locale="fa-IR")
        pg = await ctx.new_page()
        pg.on("pageerror", lambda e: errors.append(str(e)))
        pg.on("console", lambda m: m.type == "error" and "Failed to load resource" not in m.text and errors.append(m.text))  # expected 401/404 responses are asserted in the UI

        # sign-in screen and a wrong password
        await pg.goto(BASE + "/admin/")
        await pg.wait_for_selector("form[data-form=signin]")
        await shot(pg, "signin")
        await pg.fill("input[name=username]", "editor")
        await pg.fill("input[name=password]", "wrong")
        await pg.click("button[type=submit]")
        await expect(pg.locator(".ad-banner")).to_contain_text("درست نیست")
        await shot(pg, "signin-error")

        await sign_in(pg, "editor", "editor-dev-password")
        await shot(pg, "A01")

        # A01: change daily plays → draft bar shows the change and the cap
        await pg.fill("[data-path=plays_per_day]", "۴")
        await pg.press("[data-path=plays_per_day]", "Tab")
        await expect(pg.locator(".qa-vbar")).to_contain_text("دست روزانه ۳ → ۴")
        await shot(pg, "A01-draft")

        # A02: invalid step → error, publish disabled
        await pg.click("a[data-to=A02]")
        await pg.fill("[data-path='checkpoints[3].required_correct']", "5")
        await pg.press("[data-path='checkpoints[3].required_correct']", "Tab")
        await expect(pg.locator(".qa-err").first).to_contain_text("باید بیشتر از ۷ باشد")
        assert await pg.locator(".qa-vbar [data-act=noop][disabled]").count() == 1, "publish must be disabled while errors exist"
        await shot(pg, "A02-error")
        await pg.fill("[data-path='checkpoints[3].required_correct']", "10")
        await pg.press("[data-path='checkpoints[3].required_correct']", "Tab")
        await expect(pg.locator(".qa-vbar [data-act=publish]")).to_be_enabled()

        # publish confirm with the diff, then publish
        await pg.click(".qa-vbar [data-act=publish]")
        await expect(pg.locator(".ad-dialog")).to_contain_text("دست روزانه: ۳ ← ۴")
        await shot(pg, "A00-publish-confirm")
        await pg.click("[data-act=dlg-ok]")
        await expect(pg.locator(".ad-banner--ok")).to_contain_text("منتشر شد")
        await shot(pg, "A02-published")

        # restore launch value 3 (draft → publish) so later runs keep the approved value
        await pg.click("a[data-to=A01]")
        await pg.fill("[data-path=plays_per_day]", "3")
        await pg.press("[data-path=plays_per_day]", "Tab")
        await pg.click(".qa-vbar [data-act=publish]")
        await pg.click("[data-act=dlg-ok]")
        await expect(pg.locator(".ad-banner--ok")).to_be_visible()

        # A03 economy and preview
        await pg.click("a[data-to=A03]")
        await expect(pg.locator(".ad-sheet")).to_contain_text("ادامه با ۱۰ دیجی‌کوین")
        await shot(pg, "A03")

        # A04 difficulty + pair counts
        await pg.click("a[data-to=A04]")
        await pg.click("[data-act=pairs]")
        await expect(pg.locator(".qa-table tbody tr td:last-child").nth(4)).not_to_have_text("")
        counts = await pg.locator(".qa-table tbody tr td:last-child").all_inner_texts()
        assert len(counts) == 5 and all(c.strip() for c in counts), counts
        await shot(pg, "A04-pairs")

        # A05: search a player, review, block, unblock
        await pg.click("a[data-to=A05]")
        await pg.wait_for_selector(".ad-stats")
        await shot(pg, "A05")
        await pg.fill("[data-model='ops.q']", "u_nobody_" + uuid.uuid4().hex[:4])
        await pg.click("form[data-form=search] button")
        await expect(pg.locator(".ad-empty")).to_contain_text("نتیجه‌ای پیدا نشد")
        await shot(pg, "A05-search-empty")
        await pg.fill("[data-model='ops.q']", "u_" + user)
        await pg.click("form[data-form=search] button")
        await pg.wait_for_selector("[data-act=sel-run]")
        await pg.click("[data-act=sel-run]")
        await pg.check("input[name=outcome][value=suspicious]")
        await pg.fill("[data-model='ops.note']", "بررسی آزمایشی QA")
        await pg.click("[data-act=review]")
        await expect(pg.locator(".ad-toast")).to_contain_text("بررسی ثبت شد")
        await shot(pg, "A05-player")
        await pg.click("[data-act=block]")
        assert await pg.locator("[data-act=dlg-ok]").is_disabled(), "block requires a reason"
        await pg.fill("[data-model='dialog.reason']", "الگوی پاسخ خودکار (QA)")
        await expect(pg.locator("[data-act=dlg-ok]")).to_be_enabled()
        await shot(pg, "A05-block-confirm")
        await pg.click("[data-act=dlg-ok]")
        await expect(pg.locator("#main .ad-kv").first).to_contain_text("مسدود از")
        await shot(pg, "A05-blocked")
        await pg.click("[data-act=unblock]")
        await pg.fill("[data-model='dialog.reason']", "رفع پس از بررسی (QA)")
        await pg.click("[data-act=dlg-ok]")
        await expect(pg.locator("#main .ad-kv").first).to_contain_text("فعال")

        # kill switch off → on
        await pg.click("[data-act=kill]")
        await shot(pg, "A05-kill-confirm")
        await pg.click("[data-act=dlg-ok]")
        await expect(pg.locator(".ad-banner--warn")).to_contain_text("بازی خاموش شد")
        await shot(pg, "A05-game-off")
        await pg.click("[data-act=game-on]")
        await pg.click("[data-act=dlg-ok]")
        await expect(pg.locator(".ad-banner--ok")).to_contain_text("روشن شد")

        # A07 campaign create (valid) and validation errors
        await pg.click("a[data-to=A07]")
        await pg.wait_for_selector("[data-act=camp-new]")
        await pg.click("[data-act=camp-new]")
        await pg.click("form[data-form=camp] button[type=submit]")
        await pg.wait_for_selector(".qa-err")
        await shot(pg, "A07-errors")
        name = "کمپین QA " + uuid.uuid4().hex[:4]
        await pg.fill("[data-model='camps.form.name']", name)
        await pg.fill("[data-model='camps.form.starts']", "2030-01-01T10:00")
        await pg.fill("[data-model='camps.form.ends']", "2030-01-03T10:00")
        await pg.check("[data-act=camp-cp][value=cp_7]")
        await pg.fill("[data-model='camps.form.amount']", "50000")
        await pg.fill("[data-model='camps.form.validity']", "14")
        await pg.fill("[data-model='camps.form.cap']", "100")
        await pg.fill("[data-model='camps.form.budget']", "5000000")
        await pg.fill("[data-model='camps.form.terms']", "قابل استفاده برای خرید بالای ۵۰۰ هزار تومان.")
        await pg.click("form[data-form=camp] button[type=submit]")
        await expect(pg.locator(".qa-table")).to_contain_text(name)
        await shot(pg, "A07")
        # end it again so repeated QA runs do not collide on overlapping campaigns
        row = pg.locator("tr", has_text=name)
        await row.locator("[data-act=camp-end]").click()
        await pg.fill("[data-model='dialog.reason']", "پاک‌سازی QA")
        await pg.click("[data-act=dlg-ok]")
        await expect(pg.locator(".ad-toast")).to_contain_text("کمپین پایان یافت")

        # A06 history shows the changes above
        await pg.click("a[data-to=A06]")
        await pg.wait_for_selector(".qa-table tbody tr")
        await expect(pg.locator(".qa-table")).to_contain_text("خاموشی بازی")
        await shot(pg, "A06")
        await pg.click("[data-act=hist-f][data-f=review]")
        await expect(pg.locator(".qa-table")).to_contain_text("مشکوک")

        # Viewer: read-only everywhere, no version bar, no write buttons
        await pg.click("[data-act=signout]")
        await sign_in(pg, "viewer", "viewer-dev-password")
        assert await pg.locator("[data-path=plays_per_day]").is_disabled()
        assert await pg.locator(".qa-vbar").count() == 0
        await shot(pg, "A01-viewer")
        await pg.click("a[data-to=A05]")
        await pg.wait_for_selector(".ad-stats")
        assert await pg.locator("[data-act=kill]").count() == 0
        await pg.click("a[data-to=A07]")
        await pg.wait_for_selector(".qa-card h2")
        assert await pg.locator("[data-act=camp-new]").count() == 0
        await shot(pg, "A07-viewer")

        # horizontal overflow check at the design width
        overflow = await pg.evaluate("document.documentElement.scrollWidth > innerWidth + 1")
        assert not overflow, "horizontal overflow at 1280"
        await b.close()
    print(json.dumps({"theme": THEME, "screens": len(shots), "page_errors": errors}, ensure_ascii=False))
    if errors:
        sys.exit(1)

asyncio.run(main())
