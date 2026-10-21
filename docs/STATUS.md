# Integration status, decisions applied, and open items

## Integrations — fake or real

| Integration | Status | Notes |
|---|---|---|
| DigiCoin (balance, charge, credit, refund, query) | **FAKE — in-project adapter** (R-44) | DB-backed fake with scriptable outcomes. The real or test DigiCoin service is **not connected and not called**. `UnconfiguredCoinGateway` is the placeholder for the real adapter (balance unknown, every mutation UNKNOWN). Production start-up refuses both |
| Player login (Super App token) | **Development stand-in** | `Bearer dev.<name>`. The real token-validation contract has not been supplied. Production start-up refuses the stand-in |
| Admin sign-in | **Development stand-in** | Username and password with PBKDF2, server-side roles. Organisation SSO with MFA is not available here |
| Campaign code source | **FAKE** | Codes look like `DEV-XXXX…` and are AES-encrypted at rest. The source and account-binding contract are still open. Production start-up refuses it |
| Super App host bridge (back, close, haptics, share, deep link, theme) | **Adapter only, not wired** | `player/src/host.js` defines one adapter. `player/src/host-boot.js` is the production entry: the build bundles it with `host.js` and the game into one file, and it calls `configureHost({})` before loading the game. Until the integration fills that object with the real bridge, the build shows G11.AUTH_ERROR. No bridge names were invented |
| Product catalog | **Real data, offline import** | The 1,000-product sample collected earlier from Digikala is imported. 992 rows imported (8 rejected for a missing displayed variant id; every price is positive); 989 eligible. Live ingestion is not built: `api.digikala.com` is not reachable from this environment |
| Product images | CDN URLs (`dkstatics-public.digikala.com`) | In development, local copies of the same images are served under `/media/` because the CDN is not reachable from the sandbox |
| Analytics | Structured log lines (`LogEventSink`) | Event names are drafts for the analytics team. No vendor was added |
| PostgreSQL | **Real** | All tests and QA ran against PostgreSQL 16 |

## Redesign of 2026-10-21 — what was implemented

Source: «Gheimat-Shenas-Redesign-Spec» (v1, 21 Oct 2026, 16 requests), applied to this repository and to the Play demo. Its rules override older values for the same items; its concept images were followed with small visual changes (white base, Digikala red, the badge family).

| # | Request | Implemented | Where |
|---|---|---|---|
| 1 | Splash and logo | G00.SPLASH: game mark (price tag on a red tile, not the Digikala logo), «قیمت‌شناس», «گرون‌تره یا ارزون‌تر؟», dots + «داریم بازی رو آماده می‌کنیم...». Waits only for the font and the bootstrap (config, auth, player state); no fake progress or forced delay. New account → onboarding, others → home. Returning home between runs does not show the splash. G00.ERROR: short text + «تلاش دوباره» | `views.js vSplash`, `game.js boot` |
| 2 | Claim without a second display | Daily reward: the single «دریافت کن» closes the modal on success and goes to the home (no «دریافت شد» step); processing/slow/error stay on the same modal with retry. Checkpoint rewards need no claim: the server registers them with the answer that crosses the line, so the celebration has only «ادامه بده» | `game.js claimDaily/applyDaily`, `SettlementService.crossingRewards` |
| 3 | Back and icon placement | Back/close are the first header item (physical right) and point right; in «ادامه»/«شروع بازی»/«بعدی»/«نشان بده»/«بزن بریم» the icon is written after the label (physical left); forward arrows point left, play keeps the right-pointing triangle. `qa/redesign.py` checks both on every screenshot | `icons.js`, `views.js`, `game.css` |
| 4 | Digikala font | The supplied variable font (weight 100–900) is self-hosted (`player/fonts/digikala-wght.woff2`, `.woff`, `.ttf`), `font-display: block`; `main.js` waits for it (retry with back-off, no fallback font) before the first paint. Vazirmatn and Lalezar were removed from the player. The share image (the only canvas text) was removed with the share feature | `fonts.css`, `main.js`, `index.html` |
| 5, 10 | Compact reference, name and price first | Reference card 76 px image before «نشان بده», 60 px strip during the question; name 19 px, two lines max; price 30 px on its own line (LTR-isolated digits). Second product: label, two-line name, image capped at 120 px, price row reserved (hidden «؟» → revealed price in the same box), so nothing jumps | `views.js refCard/cmpCard`, `game.css` |
| 6 | Reward symbols on the progress bar | Bar kept; the first checkpoint shows its badge, the others a coin stack with the exact amount (1, a cluster for 3, a stack for 5) | `views.js track` |
| 7 | Streak day order | The server's floating 7-day window is kept; the first day of the window is at the physical right (RTL flow). Each day has an accessible label (weekday, today, taken, inactive). Streak logic and business time zone unchanged | `views.js dailyModal` |
| 8 | Timer emphasis | Timer box above the decision area: large fixed-width number, «ثانیه وقت داری», draining line computed from the server deadline (never a decremented counter). Last 3 s: «وقت داره تموم می‌شه», warmer plate, gentle pulse of the number only; two haptic warnings at 3 s and 1 s through the host bridge only. Zero: both answers lock, «زمان تموم شد», then the server check. Reduced motion removes the pulse | `views.js timer`, `game.js tickLoop` |
| 9 | Timeout feedback where the player looks | The verdict replaces the prompt above the answers (same place, same sizes): «درست گفتی!», «جوابت درست نبود», «زمان تموم شد», with text marks on the buttons; the revealed price fills the second card's price row. Pending server answers show «در حال بررسی پاسخ...», never a loss. About 1 s in place, then the loss screen once | `game.js feedback`, `durations.js missHoldMs` |
| 11 | Checkpoints, rewards, record reward | 5/10/15/20 paying **0/1/3/5**, each paid **once when crossed** (9 in a run that reaches 20). Record reward: **1** DigiCoin once per run when the score reaches the all-time record frozen at run start, if that record is **above 20** (owner table: 20 → none; 21 → at 21; 27 → at 27, not again at 28). Unique ledger key per (run, checkpoint) and (run, record target) | `LaunchConfig`, `GameConfig`, `SettlementService`, `DeadlineService`, V5, `RedesignRewardsIT` |
| 12 | Loss screen and continue of the same run | The old offer sheet and End Screen are replaced by one surface (G05/G06): badge of the highest checkpoint **of this run** (neutral shield and «هنوز عنوانی نگرفتی» under 5), «باختی!», «زمانت تموم شد» / «این بار قیمت رو درست حدس نزدی», three stats (امتیاز, بهترین امتیاز, عنوان), «تو بهتر از {x} درصد دیجی‌کالایی‌ها بودی!» (or «این دست {n} جواب درست دادی» under 5), «ادامه» with «۵ دیجی‌کوین» under it, then «صفحه اصلی». Payment states, low balance («برای ادامه ۵ دیجی‌کوین لازمه»), limit used (no continue shown) all on this surface. Back/«صفحه اصلی» declines an open offer once and opens the home | `views.js vLoss`, `game.js lossScreen/lossHome` |
| 13 | New home | Header (back, logo, DigiCoin balance in pastel yellow, «راهنمای بازی» at the left), record card with trophy, «کالاهای دست قبلی» (the last finished run's products, the losing card in view and marked «اینجا باختی»; «کالاهای اولین دستت اینجا می‌مونه» for a new player), bottom start area (illustration, title, «شانس بازی: n», «شروع بازی»; zero chances: disabled with «تا شانس بعدی hh:mm:ss» from the server; at zero «در حال بررسی شانس بعدی...» until the server confirms). The content scrolls; the start area never covers the history | `views.js vHome`, `game.js routeHome/loadHistory`, `QueryService` (`last_run_id`) |
| 14 | Badges | Four shield badges cut from the concept art (transparent WebP, no text, no amounts in the art), titles rendered live in Digikala under them | `player/assets/badges/` |
| 15 | First-entry onboarding | Two pages: (1) a fixed sandbox of the game screen with a dimmed layer and a moving spotlight (reference + price → second product + answers → timer, about 2.6 s each, looping; reduced motion: still steps with «قبلی/بعدی»), «آموزش ۱ از ۲», «بعدی», «رد کردن»; (2) «باید رکورد بزنی», the exact subtitle, the four badges with thresholds, «پاسخ‌های درستت حساب می‌شن», «بزن بریم» / «قبلی». Finishing or skipping is stored on the account; no play, payment or timer is used | `views.js vOnboard1/2`, `game.js` onboarding |
| 16 | Home guide | G02.GUIDE: scrollable page with back/close at the right, «راهنمای قیمت‌شناس», four sections with their own illustrations (two tags, clock, badge row, return arrow), numbers read from the configuration, «فهمیدم» returns to the same home state | `views.js vGuide` |

**Backend and data:** migration `V5__redesign_rewards.sql` (ledger `reward_key` with a unique index per run and key, `RECORD_REWARD` type, `pe_run.best_score_at_start`, stored configurations back-filled with their old meaning). At start-up the backend publishes, once, a copy of an older active configuration with the redesign values (checkpoints, continue 5, band starts, record reward); plays per day, continue cap, streak, alerts and switches are kept; runs in progress finish under the version they started with. Daily account cap is now plays × (sum of checkpoint rewards + record reward) + streak = **31** at launch (was 121). R-50 still holds: coins earned in the same run (now credited during the run) are subtracted before the continue balance check.

**Admin:** A02 shows each checkpoint's «درصد متن» (the fixed comparison copy) and the record reward fields; the note explains payment at crossing. A03 preview matches the loss screen.

**Conflicts and decisions to confirm (implemented as described):**
- **Continue window.** The spec's loss screen has no timer, but the current server rule (kept, §12 "current valid behaviour") is a 10 s visible offer window with a 15 s arrival deadline. The loss screen therefore shows the remaining seconds next to the cost («۵ دیجی‌کوین · ۸ ثانیه»); when it runs out, «ادامه» disappears and only «صفحه اصلی» stays. Removing the window would need a backend change (owner decision).
- **Difficulty band starts** followed the old checkpoints (3/5/7/10) and now follow the new ones (5/10/15/20), as §16 asks; distances are unchanged. This makes runs longer, so rewards are paid more often than with the old curve; QA also saw more runs end with "no products left" on the 989-product sample at high scores.
- **Percentages 50/70/90/99** are fixed product copy (§1), stored per checkpoint (`comparison_percent`), and need the owner's confirmation before public release; no statistics API was invented.
- **Badge names and the record-reward repeat rule** are the spec's proposals (one record reward per run, frozen target; repeats in later runs with the same record are allowed until the business decides otherwise).
- **Daily reward still exists** (streak reward 1, claimed in the modal) and counts in the cap of 31.
- **DigiCoin logo:** no official asset was supplied; a plain coin mark (no currency sign) stands in. Asset dependency.
- **Share** was removed from the player (the loss screen design has no share and the spec removes extra buttons); the backend endpoint and A01 switch remain.
- **Leaving a run** (back → confirm) now goes straight to the home; the run's record and history appear there.

## Owner decisions applied where older sources conflict

| Topic | Older source | Applied |
|---|---|---|
| Checkpoint overlay | R-64 (tap only), F-01 (tap or auto 5 s) | **Redesign 2026-10-21 §10:** only the «ادامه بده» button closes it (600 ms guard); no countdown or automatic continue |
| Continue offer window | Handoff: the window opens at the miss | **F-02:** the window starts at a post-feedback activation call. The server checks eligibility again (game-off, funds). Wrong-answer feedback does not consume the window |
| Payout cap day | — | **F-03:** a credit counts against the cap of the run's **start** business day, even if it is paid later |
| Block during a payment | Handoff: end after the payment resolves | Contract: the run ends **immediately**. A debit confirmed afterwards is refunded once (T27) |
| Answer timing | R-45 (10 s, 10,500 / 11,000 ms) | **Brief 2026-10-20:** 10 s visible window, input locks at the visible zero (10,000 ms); the server admits an answer whose first arrival is ≤ 12,500 ms, inclusive (stamped when the body has been read) |
| Extra-life timing | R-43 / R-39 (run ends at the 10 s offer deadline; no reservation) | **Brief 2026-10-20:** 10 s visible window; a purchase is admitted when it first arrives ≤ 15,000 ms after the offer opened. An admitted purchase may finish after that; the run waits for the charge result until `resolve_by` (proposed: arrival deadline + 60 s), then ends unconfirmed and a later debit is refunded once |
| Daily plays, continue, cap | Older spec values; R-20 | 3 plays a day (R-33). **Redesign:** continue costs 5, limit 1; checkpoints 5/10/15/20 pay 0/1/3/5 each when crossed; record reward 1 above 20; streak reward 1; daily cap 31 with a high-water mark |
| A01 inactivity field | The A01 board draws it as an input | The owner fixed it at 3 minutes, so it is shown **read-only** to avoid changing timing without approval. This only matters if the owner wants it editable |
| Images | Opaque per-question image proxy (handoff option) | CDN URL released only **after** reveal, never before (T05). The proxy was not built |

## Owner brief of 2026-10-20 — what was implemented

| Area | Implemented | Where |
|---|---|---|
| Timing contract | Question 10 s visible / 12.5 s arrival; extra life 10 s visible / 15 s arrival. Deadlines limit the **first arrival** only; admitted operations finish and deliver afterwards. Receipt-first admission for answers and (new) purchases; the expiry sweeps settle or admit a recorded receipt instead of expiring it; alerts `ON_TIME_ANSWER_TIMED_OUT` / `ON_TIME_PURCHASE_EXPIRED` if that ever fails | backend `RunService`, `DeadlineService`, `PaymentService`, `Offer`, `GameConfig`, `LaunchConfig`, V4; player `game.js` |
| Config | `offer_accept_ms` and `payment_resolution_ms` added; validator fixes the four launch timing values; A01 shows them read-only («۱۰ / ۱۲٫۵» and «۱۰ / ۱۵» ثانیه). At start-up an active version without them is copied once with the launch timing (audited `system:migration`); runs keep their pinned version | `AdminService.migrateTiming`, `ConfigValidator`, admin `app.js` |
| Lifecycle | One operation per question / offer; one key = one operation (unique per user+key, reuse elsewhere → 422, nothing created); skip/leave/new start refused while a purchase is admitted; client keeps an in-flight purchase pending through run reads, treats `REQUEST_IN_PROGRESS` as "still checking", re-sends the same key while it could still arrive, then reads the run; stale responses ignored by generation | `JdbcIdempotencyRepository`, V4 index, `game.js` |
| Wallet balance | Shown only in the extra-life purchase sheet (G05/G06) | `views.js` |
| Record | Removed from gameplay and the End Screen (still on the start screen; the API still returns it) | `views.js` |
| Dark mode | Removed: player renders light only (`color-scheme: only light`, no dark tokens, host theme ignored). The spotlight dim stays | `tokens.css`, `ds.css`, `main.js`, `host.js`, HTML |
| Start screen | Intro «دو تا کالا رو ببین، قیمت رو حدس بزن و جواب بده!»; «راهنمای بازی» opens the visual guide | `views.js` |
| Onboarding | Mandatory on first entry, 7-step spotlight tour on a safe demo (two drawn demo products, no server call, no catalog product); no skip in one action; finished state saved on the account (`POST /player/onboarding`, retried once); entry order onboarding → daily reward → start, never stacked | `game.js` (TOUR), `demo.js` |
| Daily reward | Modal «جایزه‌ی روزانه»: streak count, last 7 days attended, amount, «دریافت کن». The streak reward earned by the day's first finished run waits as `CLAIMABLE`; the claim is idempotent across taps, retries and devices; states claimable / processing / claimed / delayed. The streak appears nowhere else (start, End Screen, share) | `DailyRewardService`, `PlayerApi`, V4, `views.js` |
| Gameplay HUD | No «کالای مرجع» / «کالای دوم» labels; count only (with a screen-reader sentence); timer white → orange → red in the last 3 s on a dark plate; processing state after an answer; one continuous progress bar with flags at the checkpoints and ∞ after the last | `views.js`, `app.css` |
| Checkpoint | Duplicate celebration fixed at the root (see below); one cartoon character per checkpoint; copy «[نام] رو گرفتی.», «هدف بعدی: [نام] با [تعداد] جواب درست»; «[مقدار] تا دیجی‌کوین دریافت کردی.» only after a confirmed grant (End Screen), «پاداش فعلی… واریز در پایان دست» during the run | `game.js` (`openCp`), `characters.js`, `views.js` |
| End Screen | No record, settlement ticks, play-again or bottom share; icon share at the physical top-right, Back at the top-left → start screen (no run, no payment); horizontal product history, chronological, with question ids, the losing card in view, timeout shown as «وقت تموم شد», scroll position preserved across re-renders | `views.js`, `game.js`, `QueryService` |
| Difficulty | Audited: R-41, `LaunchConfig` and A04 agree; no change. New tests for bands, reveal-time choice, pinned snapshot, import filtering, fallback, directions and performance | `DifficultyCurveIT`, `DifficultyPerformanceIT` |

**Duplicate checkpoint celebration — cause and fix.** The overlay was rendered as part of the screen markup, and every `render()` rebuilds the screen: the checkpoint guard timer's own render at 1.2 s (and any toast, image load or reconnect) recreated the overlay and replayed its entrance animation. Fix: the overlay is mounted once per checkpoint into a separate host (`.pe-cphost`) outside the re-rendered screen host, guarded by a per-run "shown" set, and removed only when it closes. Probe: before, 2 mounts and 4 entrance animations per checkpoint; after, 1 mount and 2 (card + character). `qa/lifecycle.py cp_once` keeps it fixed.

**Conflicts and choices to confirm (implemented as described, labelled proposed):**
- *Checkpoint copy vs payout rule.* The brief says «دریافت کردی» only after a confirmed grant; checkpoint rewards are paid at the end of the run (highest only, R-17). So the in-run celebration keeps the approved «پاداش فعلی: X دیجی‌کوین / واریز در پایان دست», and «X تا دیجی‌کوین دریافت کردی.» appears on the End Screen once the credit is confirmed. Paying per checkpoint during the run would change the reward rule, so it was not done.
- *When the daily reward is earned.* "Attended" = a finished run that day (the existing streak rule; opening the game is not attendance). The reward appears after the day's first finished run and is claimable from then on (also when returning to the start screen from the End Screen).
- *`payment_resolution_ms` = 60,000* (how long an admitted purchase may wait for the charge result after the 15 s arrival deadline). Proposed; valid range 10,000–600,000.
- *`timeout_grace_ms` = 1,000* (unchanged): the sweep writes a timeout at arrival deadline + 1 s, stamped at the deadline.
- *Visible zero of the offer:* the buy button locks and the sheet shows «زمان پیشنهاد تموم شد» with «دیدن نتیجه»; the server ends the offer at 15 s (+ grace). A purchase sent just before the visible zero shows «در حال بررسی…» until the server answers.
- *Back placement on the End Screen:* the brief places share at the physical top-right; Back went to the top-left.
- *Characters* are hand-authored inline SVG in one family (not generated, not official Digikala art; R-26).
- *Design sizes:* screenshots were taken at 360×640, 390×844 and 430×932 as the brief asks (R-32 had fixed 375×812).
- *QA catalog:* a 150-product subset with local images (the full image folder is on the owner's computer).

## Proposed values (implemented, labelled, awaiting approval or tuning)

- **Timing:**
  - `checkpoint_auto_continue_ms` is no longer used by the player (redesign §10: no automatic continue); the field stays in the configuration.
  - `offer_activation_ms` 180,000: how long an unactivated miss waits before the run ends. This reuses the inactivity value.
  - `timeout_grace_ms` 1,000: the timeout worker waits this long after the accept deadline. Since the answer receipt is now recorded before any run lock, the grace only has to cover ingress → one single-row write, so lowering it to around 300–500 ms (to shorten the ~12.3 s timeout verdict) is safer than before. Watch the `ON_TIME_ANSWER_TIMED_OUT` alert: it fires if an on-time answer still lost to a timeout, which means the grace is too short for the real latency.
- **Payments after the offer closes:** a charge that was never sent is closed without touching the wallet. One that may have reached the provider is never sent again; it is reconciled by status query only (refunded once if it lands) and moves to NEEDS_REVIEW with an alert when the 72 h retry window ends. If DigiCoin guarantees that "not found" is final after its timeout (D06), it could be closed sooner.
- **Selection:** a reveal first tries a random sample of 48 candidates per direction, then every candidate of the band. Look-ahead uses an in-memory price index of the pinned catalog version (the last 4 versions are cached; published versions never change).
- **Workers:** coin operations of up to 4 players are processed at once (one player's in order). A provider gets at most 64 concurrent calls per instance; beyond that a call is answered "unknown" without being sent and reconciled later. Reading one request may take at most 10 s (`sun.net.httpserver.maxReqTime`, standalone HTTP edge only).
- **Quota screen:** at the reset instant the player asks the server again (every 2 s, at most 5 times) instead of trusting the local countdown.
- **Idempotency lease:** 30 s for player requests, 15 min for admin writes (game-off finishes every active run). A retry after the lease takes the key over (recovery after a crash). Rows written by an older instance without a lease age from their creation time.
- **A01 switches:** a run follows the switches of the configuration version it started with, like every other setting. Turning the streak switch off hides the streak card, row and share line and pays no streak reward; the server still counts streak days in the background (**unresolved:** should counting pause while the switch is off?).
- **Daily reward:** `processing` for up to 2 minutes after a claim, then `delayed` while the credit is unconfirmed; the claimed credit retries for 72 h like other credits. Abuse signal `share_after_visible_window`: share of answers arriving after the 10 s visible window (information only).
- **Motion durations** (`player/src/durations.js`): correct hold 1,300 ms, miss hold 1,000 (redesign §8: 700–1,000), promotion 540, checkpoint button guard 600.
- **Rate limits:** start 10 per minute per user, polling 4 per second per run. Idempotency retention 24 h. Coin call timeout 2 s. A reward is shown as "delayed" after 10 s pending.
- **Risk signals** (A05, information only, R-60): share of answers under 600 ms; median under 0.9 s. These thresholds are proposals.
- **Copy:** CP-20 (unconfirmed payment at the deadline) uses **draft wording** that does not claim the debit never happened. CP-34 and CP-35 are proposed.

## Blockers and missing contracts

1. **Super App player authentication:** the token format and validation endpoint are needed to replace `DevPlayerAuthenticator`.
2. **Host bridge:** the API for back/close, haptics, native share, deep-link scheme, theme and safe areas. Share falls back to image-only, and no app URL scheme was invented. The WebView floor also decides the build target, currently `es2021`.
3. **DigiCoin:** the adapter contract (idempotency key, query by key, error taxonomy, timeouts) and authorisation to connect. **Kept fake by instruction.**
4. **Campaign code source:** where codes come from, how they bind to the account, the failure policy, and the budget currency and reservation semantics.
5. **Admin SSO** for production sign-in.
6. **Owner values:**
   - payout alert threshold (R-47);
   - admin numeric bounds (`PE_BOUND_MAX_*`);
   - catalog price-change threshold;
   - CP-20 approved copy;
   - the offer-activation wait.
   - `payment_resolution_ms` (60 s proposed) and the daily-reward timing above (brief 2026-10-20).
7. **Live catalog ingestion:** depends on access to the Digikala product API from the deployment network.

None of these block the playable path in development. Each is isolated behind a port or a configuration value.

## Review of 716d889 — findings fixed

| # | Finding | Fix | Covered by |
|---|---|---|---|
| 1 | A charge retried after the offer ended could debit the player (then refund) | A debit is sent only while the offer can still be granted. After that, a charge that may have been sent is reconciled by status query only: if it lands it is refunded once; if it never shows up it goes to NEEDS_REVIEW with an alert at the end of the retry window (never a silent FAILED) | `PaymentIT.retryAfterTheOfferEndedNeverSendsAFreshDebit_unresolvedGoesToReview`, `…anEarlierAttemptLandingAfterTheOfferEndedIsRefundedNotRepeated` |
| 2 | An answer received on time (10,990 ms) but processed after the grace (12,001 ms) became a timeout | The receipt (choice + trusted arrival time) is written first, without waiting for run locks. Where the question would be timed out (any request or the worker), a recorded receipt is settled as the answer instead. Leave, block and replacement are unchanged: an answer not yet settled gives no correctness (R-59) | `RunLifecycleIT.t11b`, `t11c`, `t11d` |
| 3 | The production build dropped `configureHost`, so `host-boot.js` could not configure the bundled game | `host-boot.js` is the bundle entry; the build fails if host boot, adapter or game are missing from the bundle | build audit + browser probe (theme, token and device id reach the game) |
| 4 | After 3 answer attempts and 12 failed reads the client stayed in G03.ANSWER_PENDING | Reads continue with a capped backoff (≤ 10 s) under G10.RESUMING until the server settles the question; any authoritative run state clears the reconnecting indicator | browser probe: 14 failed reads → recovered to G03.CORRECT_FEEDBACK |
| 5 | Share, products and haptics switches never reached the player; streak-off still advertised a reward | `run_config.features` / `config.features`; the player hides what is off; the server refuses products/share (`FEATURE_DISABLED`); bootstrap sends the effective streak reward | `RewardsIT.featureSwitchesReachThePlayerAndAreEnforced` + browser probe |
| 6 | A crash between IN_PROGRESS and completion stranded the key for 24 h | Lease (30 s player, 15 min admin) with an owner token; a later retry takes over; a stale owner can neither complete nor release | `HttpIT.abandonedRequestKeyIsTakenOverAfterItsLease` |
| 7 | The daily cap overflowed in 32-bit arithmetic (3 × 1,500,000,000 + 1 → 205,032,705) | Exact 64-bit computation; the validator rejects a cap that does not fit; admin views show the exact value | `DomainRulesTest.dailyCapNeverWraps` + admin API probe |
| — | The parallel test helper swallowed worker exceptions | It now fails on any worker error or a worker still running after 30 s | all concurrency tests |
| — | The backend build depended on machine-specific Debian paths | Maven Central by default plus the Gradle wrapper; `-PofflineRepo=…` for offline machines | `./gradlew build` (team) · offline run here |
| — | `PaymentIT.t28` compared against the database's wall clock (fails from 20 Oct 2026); `RunLifecycleIT.t15` flaked on sparse catalog paths | Due time set on the test clock; t15 retries another path like the reward tests do | full suite |

## Strict review of b023340 — findings and status

That review was of b023340 (716d889 plus README edits), so it did not include the fixes above. Verdict per finding:

| # | Finding | Status | Fix | Covered by |
|---|---|---|---|---|
| 1 | Fresh debit after the offer expired | Correct; fixed earlier | see table above (#1) | `PaymentIT` |
| 2 | On-time answer lost to late processing | Correct; fixed earlier | see table above (#2). Residual: the receipt write still needs a database connection; if the pool is exhausted for longer than the grace, the `ON_TIME_ANSWER_TIMED_OUT` alert fires | `RunLifecycleIT.t11b–d` |
| 3 | Host adapter cannot be configured | Correct; fixed earlier | host-boot.js is the bundle entry | build audit |
| 4 | Checkpoint due at a leave blocks the next run's reveal | Correct | one `resetRunUi()` for every run end, boot and start; checkpoint tokens invalidated | `qa/lifecycle.py checkpoint_reset` |
| 5 | Answer recovery stops | Correct; fixed earlier | backoff never stops | browser probe |
| 6 | Publishing a draft changed by another editor | Correct | draft revision (hash of its content) required to save over, discard or publish; `DRAFT_CHANGED` conflict; a lost save response is reconciled | `RewardsIT.anEditorCannotPublish…`, `HttpIT` |
| 7 | Admission/offer/continue promise a question reveal rejects | Correct | one predicate `canReveal` (look-ahead included) for admission, offer activation and the paid-continue grant | `SelectionConsistencyIT` |
| 8 | 48-candidate sample reported as exhaustion | Correct | full-band fallback before no_products; unseen and seen pools both searched | `SelectionConsistencyIT` |
| 9 | Replacement overwrote the day's statistics | Correct | participation re-read after the replaced run is settled | `ReviewFixesIT` |
| 10 | Stale responses replace a newer screen | Correct | ownership (generation, run, screen) checked after every await | `qa/lifecycle.py stale_products, share_busy` |
| 11 | Replayed `server_time` moves the clock back | Correct | replays (`Idempotent-Replay`) are never clock samples | `qa/lifecycle.py replay_clock` |
| 12 | Timeout ends before the body is read | Correct | deadline covers the body; malformed success = `BAD_RESPONSE` | `qa/lifecycle.py body_timeout` |
| 13 | Second offer reuses the first offer's key | Correct | one activation key per missed question; copy follows the configured cap | `qa/lifecycle.py offer_per_miss` |
| 14 | Admin switches do not control the player | Correct; fixed earlier | see table above (#5) | `RewardsIT` |
| 15 | Idempotency not crash-recoverable | Correct; fixed earlier (lease). Not covered: a crash after the domain commit but before the response is stored re-evaluates the request; domain rules keep it single-effect | `HttpIT` |
| 16 | Campaign overlap and edit/end races | Correct | schedule writes serialised (advisory transaction lock); edit/end re-read under it | `ReviewFixesIT` |
| 17 | Campaign provider exceptions bypass the ceiling | Correct | exceptions are unknown outcomes; ceiling enforced at claim; admin list + audited retry (`/entitlements/review`, `/entitlements/{id}/retry`) | `ReviewFixesIT` |
| 18 | Cap overflow | Correct; fixed earlier | exact arithmetic + validation | `DomainRulesTest` |
| 19 | Empty price range throws | Correct | repository lists treated as immutable | `SelectionConsistencyIT` |
| 20 | JSON coercion and key aliases | Correct | no float/string coercion, one spelling per field, objects only, no null array elements | `HttpIT.strictJson…` |
| 21 | Answer time stamped before the body arrived | Correct | stamped when the whole body is read; request read bounded to 10 s | `HttpIT.answerTimeIsStamped…` |
| 22 | Timed-out coin calls accumulate | Correct | bounded in-flight calls, cancel on timeout, closed on shutdown. The real adapter still needs its own connect/read deadlines | `TimeLimitedCoinGatewayTest` |
| 23 | Sweeper blocks behind a busy player; serial payment retries | Correct | sweeper skips locked players; payment batches run per player in parallel | `ReviewFixesIT` |
| 24 | Kill switch scans only 100,000 recent runs | Correct | keyset scan of active runs with a partial index | `ReviewFixesIT` |
| 25 | Concurrent catalog imports collide | Correct | version allocated under the settings lock; startup seeding only if none active | `SelectionConsistencyIT` |
| 26 | Concurrency test helper hides failures | Correct; fixed earlier. QA browser checks are now hard gates too | `TestEnv.parallel`, `qa/flow.py`, `qa/states.py` |
| 27 | Admin history hides older changes | Correct | reads and draft saves filtered on the server before paging; "older" always reachable | admin QA |
| 28 | Copy feedback inaccurate and not rendered | Correct | follows the clipboard result; failure message; state reaches the view | `qa/lifecycle.py copy_state` |
| 29 | Quota countdown static, no refresh at reset | Correct | countdown ticks on every quota screen; server asked again at the reset and on return to the app | `qa/lifecycle.py quota_reset` |
| 30 | Role change ignored by live admin sessions | Correct | sessions use the account's current role | `ReviewFixesIT` |
| — | Reasons not required server-side | Correct | unblock, operation retry, entitlement retry and campaign end require a reason | `ReviewFixesIT` |
| — | History shows guessed versions and the wrong message field | Correct | publish records the version it replaced; message read from the audit | admin QA |
| — | STATUS catalog statistics | Correct | the 8 rejected rows have no displayed variant id | — |
| — | Tracked `qa/__pycache__`, unescaped reviewer values, README intro and check order | Correct | removed and ignored; escaped; restored; commands run from the root | — |

Open items from the review's project-level comments are listed under **Blockers** above and in the review response document; they need decisions or integrations, not code changes alone.

## Defects found and fixed during this phase

- `rewardStatus` showed a partially **held** reward as pending. Any held part is now "delayed", never "paid" (T35).
- **Discarding a config draft did nothing:** `retire` only matched PUBLISHED. Found by the admin QA driver; now covered in HttpIT.
- **Player payment polling stopped after the first round:** `onOperation` re-entered the polling guard. It now loops until the payment is resolved, and switches to G06.UNKNOWN after 5 s.
- A **retry after a rejected debit** reused the same idempotency key and replayed the failure. A deliberate retry now uses a new key.
- An **unresolved payment at the offer deadline** skipped straight to the result. It now shows G06.FAILED_END.UNCONFIRMED, then G06.REFUNDED when a late debit is refunded (R-43).
- G11 access screens briefly showed ghosts of an interrupted card animation; they now replace the game immediately.
- The share preview cropped the 360×640 asset; it now scales into its frame.
- **Another device's** RunView exposed the open question's product. It is now minimised (the question is null there).
- JSON payloads with **duplicate keys** were accepted. Strict duplicate detection is now on (T41).

## Measured findings for the product team

- **Catalog depth** (30 perfect players, real 1,000-product sample, R-41 bands): every run eventually ended with `no_products`.
  - Score at the end: median 74.5, maximum 315.
  - **4 of 30 runs ended at score ≤ 5** (3, 3, 3, 5). Some references have no valid band-1 (30–50 %) follow-up that also passes look-ahead.
  - This is input for the A04 playtest and for catalog size; it is not a code defect. A04 shows pair counts per band before publishing.
- **Local load baseline:** see `VERIFICATION.md`. This is not a capacity claim.
