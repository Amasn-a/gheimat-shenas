# Verification log — 2026-10-22 (redesign of 2026-10-21)

## Backend — `gradle -PofflineRepo=/usr/share/maven-repo --offline test`
- **117 tests, 0 failures** on PostgreSQL 16 (run twice in a row). New `RedesignRewardsIT`: each checkpoint paid once at its crossing (0/1/3/5, no row for 0, 9 at 20), answer replays create nothing; the owner's record-reward table (record 20 → none at 20/21; 21 → one credit at 21; 27 → one at 27, none at 28; re-reading creates nothing); a run pinned to a legacy configuration still pays the highest checkpoint at run end. `PaymentIT.r50_…`: a delivered crossing reward of the same run cannot fund its continue. Existing suites updated to 5/10/15/20, continue 5, cap 31 and band starts 0/5/10/15/20 (intent of each test kept).
- Start-up migration checked on the development database that still had the old launch configuration: V5 applied, the old version back-filled (`pay_on_crossing: false`), and a new version published with checkpoints 5/10/15/20 and continue 5.
- `dev/smoke.py` (API smoke against the running backend): passes.

## Player — browser QA against the running backend (Chromium)
- **`qa/redesign.py`** at **360×640, 390×844 and 430×932**: 29 screenshots per size (87), **0 audit problems** and no console errors. Audit on every screenshot: RTL, no horizontal page scroll, buttons inside the viewport and not covered, touch targets ≥ 44 px, back/close at the physical right, button icons at the physical left, no text past the edge, every rendered element and every loaded font face is Digikala.
- **`qa/lifecycle.py`: 23/23** — checkpoint celebration mounted once (re-render, reconnect, reload), no tap-anywhere or auto-continue; a tap at the visible zero sends nothing and the server's timeout follows; a pick just before zero stays pending, never a loss; daily reward double tap = one claim, modal closes to the home; loss-home declines the offer once (UI and device Back); purchase response lost / reconciled; payment states failed-retry, failed-end, unknown-applied, late success, not applied, refunded; offline/refresh keeps the same question and deadline and shows the verdict once; quota reset enabled only by the server; light only.
- **`qa/states.py`: 5/5** — other-device replacement, block, game off, offline before release, campaign code on the loss screen.
- **`qa/admin_flow.py`**: 21 screens, no page errors (A02 with «درصد متن» and the record reward).
- `npm run build`: bundle audit passes; the player bundle carries the Digikala font files and `assets/` (badges, trophy).

## Not verified here
- Real devices / Super App WebView (haptics at 3 s and 1 s, safe areas, font loading on slow networks). The haptic calls go through the host bridge, which is not wired yet.
- Zoom levels beyond the three sizes; very long product names were covered only by the real catalog sample.

# Verification log — 2026-10-21 (owner brief of 2026-10-20)

Cloud Linux container, PostgreSQL 16, Java 21, headless Chromium. Development mode: fake DigiCoin, dev login, a 150-product QA catalog subset with locally served images. **Not real devices or real WebViews.** Run and passed here unless stated otherwise.

## Backend — `gradle -PofflineRepo=/usr/share/maven-repo --offline test`

**111 tests, 0 failures:** DomainRulesTest 8, RunLifecycleIT 20, SelectionIT 3, SelectionConsistencyIT 4, PaymentIT 14, RewardsIT 12, HttpIT 14, ReviewFixesIT 7, TimeLimitedCoinGatewayTest 2, **TimingContractIT 15, DailyRewardIT 5, DifficultyCurveIT 6, DifficultyPerformanceIT 1** (new).

- **TimingContractIT** — answers: click 9.9 s / arrival 12.0 s / response 14.0 s is evaluated; arrival after the visible window is not a rejection reason; 12,499 and 12,500 ms admitted, 12,501 ms expired; a new request at 12,600 ms is rejected and the timeout is stamped at 12,500; admitted-then-retried after the deadline returns the same result; an admitted request that died before its run lock is settled as the answer; admitted answer racing the expiry sweep settles once. Purchases: 10,001 / 14,999 / 15,000 ms admitted, 15,001 ms expired; click 9.8 s / admitted 14 s / charge confirmed 18 s applies the life; a receipt recorded on time is admitted by the expiry path; an on-time receipt that still loses raises `ON_TIME_PURCHASE_EXPIRED`; an unanswered offer expires at the arrival deadline, not at the visible zero; a definitive failure after the arrival deadline ends the run with no life; skip and leave are refused while an admitted purchase is processed; start-up migrates an older active configuration once, audited, and pinned runs keep their timing.
- **DailyRewardIT** — opening the game is not attendance and the first finished run records one claimable reward; opening the modal claims nothing; taps, retries and a second device credit once; an unconfirmed claim is `processing` then `claimed`, a rejected one is `delayed`, never lost; streak days and attended days follow the existing rules; a blocked player cannot claim and the reward waits.
- **DifficultyCurveIT** — every question is in its R-41 band before, at and after each checkpoint (checked by an independent oracle); the comparison is chosen at reveal, a retry keeps it; prices come from the run's pinned catalog snapshot after a new catalog is published; invalid rows are filtered at import; extreme easy pairs stay allowed and the seen-today fallback keeps the band; both directions are drawn and the band follows the server score only. **No discrepancy** was found between R-41, `LaunchConfig` and A04, so the curve was not changed.
- **DifficultyPerformanceIT** — 20,000 generated products, 480 reveals reaching the late bands: p50 8 ms, p95 70–84 ms over runs, max 108 ms (assertion: p95 < 250 ms). Local figure, not a capacity claim.
- **HttpIT** (+3) — a key reused on another route/object gets `422 IDEMPOTENCY_MISMATCH` and creates nothing; an answer replay after the deadline returns the stored result; the daily reward status/claim over HTTP.

## Player — browser QA against the running backend

- **`qa/brief.py`** at **360×640, 390×844 and 430×932**: 30 screenshots per size (90 total), with a DOM audit on each (RTL, horizontal scroll, buttons under 44×40 px, covered buttons, text past the edge, clipped text, nested vertical scroll). **0 problems.** It also checks the End Screen history: chronological, question ids present, the losing card in view on open (scrollLeft preserved when scrolled back).
- **`qa/lifecycle.py`**: **15/15** — the 8 earlier checks plus `cp_once` (one celebration mount; re-renders, a reconnect, a visibility recovery and a reload never replay it), `answer_at_zero` (a pick at the visible zero stays «در حال بررسی پاسخ…» and is evaluated), `pick_vs_expiry` (one transition at the boundary), `daily_double_tap` (one claim request), `end_back` (UI and device Back go to the start screen; no POST), `light_only` (a dark system theme still renders light), `purchase_reconcile` (a run read while the purchase request is in flight keeps G06.PENDING, then G06.SUCCESS; this check **fails on 54304a8** and passes after the fix).
- **`qa/states.py`**: **13/13**, light only, no page errors (timeout, no funds, charge rejected then retried, charge unknown → unconfirmed at `resolve_by` → refunded once, charge confirmed 12 s later → life granted with one debit, device replacement, block, game off, quota, campaign, offline after/before release, motion).
- **`qa/admin_flow.py`**: 21 screens, no page errors (A01 shows the timing as «۱۰ / ۱۲٫۵» and «۱۰ / ۱۵» ثانیه).
- **`dev/smoke.py`**: full HTTP path including `offer.accept_deadline` / `resolve_by` and the daily reward state after a run.
- **Builds:** `npm run build` — player and admin built; bundle audit passed; no `prefers-color-scheme` rule in the player bundle.

## Duplicate checkpoint celebration — before/after probe

On 94ea064 the overlay was part of the screen markup, so the checkpoint guard timer's `render()` at 1.2 s (and any toast, image load or reconnect) rebuilt it and replayed its entrance: **2 mounts, 4 entrance animations** per checkpoint. Now it is mounted once into its own host outside the re-rendered screen: **1 mount, 2 entrance animations** (card + character), and `cp_once` guards it.

## Not verified here

- Real Android/iOS WebViews, safe areas on devices, the Super App host bridge (Back, haptics, native share).
- The real DigiCoin service (fake by instruction), production login, admin SSO.
- Network latency between a real WebView and the server: the 2.5 s (answer) and 5 s (purchase) arrival margins were tested with a controlled clock and simulated delays only.
- A screen-reader pass of the onboarding spotlight and the daily reward modal (accessible labels exist; not tested with TalkBack or VoiceOver).
- Rolling deploy of V4 (documented as stop-the-world in `SETUP.md`).

# Verification log — 2026-10-20 (second round: strict review of b023340)

Same environment kind as below. Run and passed here unless stated otherwise.

- **Backend:** 81 tests, 0 failures, three consecutive fresh runs: DomainRulesTest 8, RunLifecycleIT 20, SelectionIT 3, SelectionConsistencyIT 4, PaymentIT 14, RewardsIT 12, HttpIT 11, ReviewFixesIT 7, TimeLimitedCoinGatewayTest 2.
- **New client lifecycle driver** `qa/lifecycle.py`: all 8 checks pass on this branch; the 7 checks for findings 4, 10, 11, 12, 13, 28 and 29 were also run against the b023340 player and **all 7 failed there** (reveal blocked, stale products list shown over the new run, clock moved −3,018 ms on a replay, stalled body never timed out, second miss replayed the first offer, copy shown as copied after a denied clipboard, countdown static).
- **QA drivers:** `qa/flow.py` (light and dark), `qa/states.py` 13/13, `qa/admin_flow.py` 21 screens — no page errors. `qa/flow.py`'s early-tap check now taps before taking the screenshot (a slow screenshot had pushed the tap past the 1.2 s guard).
- **Admin probes (scratch):** a save whose response is lost is reconciled without a false "another editor" conflict; history shows "3 → 5" after version 4 was discarded.
- **Independent review** of the diff by a second agent: its six remarks were applied (share button reset, lost save response, quota retry, fallback cost and randomness, poll continuation, copy reset).
- **Not done:** Maven Central/wrapper build path (blocked here), real WebViews, sustained load, PostgreSQL process-kill tests.

# Verification log — 2026-10-20 (fixes for the review of 716d889)

Same kind of environment as below (cloud Linux container, PostgreSQL 16, Java 21, headless Chromium at 375×812). Run and passed here unless stated otherwise.

- **Backend:** 65 tests, 0 failures, three consecutive runs (`gradle -PofflineRepo=/usr/share/maven-repo test`): DomainRulesTest 8, RunLifecycleIT 20, SelectionIT 3, PaymentIT 14, RewardsIT 11, HttpIT 9. Two tests that failed on 716d889 for reasons unrelated to the review were fixed: `PaymentIT.t28` compared the test clock with the database's wall clock (it fails from 20 Oct 2026 on); `RunLifecycleIT.t15` flaked when a random path ran out of products.
- **The new regression tests fail on 716d889 and pass now** (checked for findings 1 and 2 by running them against the old code: a debit was sent after the offer ended; the 10,990 ms answer was rejected as `QUESTION_EXPIRED`).
- **Builds:** player and admin built, bundle audit passed; the build checks the player bundle contains host boot, host adapter and game, and that `index.html` loads only that entry.
- **Browser probes** (scratch scripts, not committed): a host configured in `host-boot.js` reaches the bundled game (theme, token, device id); a lost answer response plus 14 failed run reads recovers to G03.CORRECT_FEEDBACK through G10.RESUMING; with all A01 switches off, G01 has no streak card and G07 has no share, products or streak row; an oversized cap draft returns a validation error and cannot be published.
- **QA drivers:** `qa/flow.py` complete run (light and dark), `qa/states.py` all 13 scenarios, `qa/admin_flow.py` 21 screens — no page errors (with product images served locally).
- **Independent review** of the diff by a second agent: no deadlock paths (every `applyDue` caller holds the player and run locks before the question lock; the receipt write holds only the question row); its four remarks were applied (late debit → review instead of FAILED, R-59 kept, longer admin lease and lease-less rows, reconnecting indicator cleared).
- **Not done:** the Maven Central/wrapper build path (Maven Central and services.gradle.org are blocked here), real WebViews, sustained load.

# Verification log — 2026-10-18

**Environment:** cloud Linux container, 2 vCPU, 7 GB RAM. PostgreSQL 16 (local), Java 21, Chromium (Playwright, headless). The backend ran in development mode: fake DigiCoin, dev login, product images served locally.

Everything below was **run and passed in this environment** unless it says otherwise. Visual checks are of a fixed-size headless browser at 375×812; **they are not real-device or real-WebView results**.

## 1. Backend automated tests — `cd backend && gradle --offline test`

57 tests, 0 failures, on a fresh PostgreSQL schema per class, with a controllable clock and a seeded random source.

| Class | Tests | Scenario coverage (test IDs from the instruction's matrix) |
|---|---|---|
| `DomainRulesTest` | 7 | T17 exact band boundaries (just below, at, just above 3/8/15/30/50 %); config invariants; streak policy |
| `RunLifecycleIT` | 17 | T01–T11, T13–T16, T21 |
| `SelectionIT` | 3 | T18 direction 50/50 (400 draws); T19 unseen first, then least-recently-seen in the same band; T20 look-ahead and terminal `no_products` |
| `PaymentIT` | 12 | T22–T29, T51 |
| `RewardsIT` | 10 | T30–T38, T40 (streak and quota across midnight), T53 |
| `HttpIT` | 8 | T01 over HTTP (replay, mismatch, key scope); T39; T41; T42 |

What each integration class checks:

- **RunLifecycleIT:**
  - Start, quota and duplicate start; resume, replacement and settle-once.
  - Concurrent starts; reveal retry returns the same deadlines.
  - The inclusive 11,000 ms cutoff (10,999, 11,000 and 11,001 ms).
  - Slow image or late response never extends a deadline; duplicate and conflicting answers yield one result.
  - Answer vs timeout race, and recovery after a restart.
  - Lost-response recovery; reload is not leaving.
  - Leaving during an open question; equal prices accept both choices; config snapshot per run.
- **PaymentIT:**
  - One continue and one debit.
  - No or unknown balance gives no offer.
  - The limit cannot be bypassed with new keys.
  - Unconfirmed payment at expiry; late confirmation refunded once.
  - Confirmed debit with game-off, block or no products is refunded.
  - The deadline boundary race.
  - Crash after debit, after outbox write, and after refund.
  - F-02 activation.
- **RewardsIT:**
  - Highest checkpoint only, finalised once.
  - Flagged accounts are paid; a block voids that run.
  - Game-off; record; streak.
  - Cap 121, high-water mark, concurrent credits, held excess.
  - Refunds sit outside the cap.
  - Campaign codes: one per account, cap and budget races.
  - Midnight; delayed credit on the start-day cap.
- **HttpIT:**
  - T39: Viewer cannot mutate; Editor changes audited; audit table append-only; admin replay.
  - T41: foreign ids → 404; tampered time, price, cost or score fields → 422; duplicate keys; oversized body; auth and device headers; stale device → 409; stale config version → 409; hidden comparison price never in any response; no tokens or passwords in logs; password and session hashes at rest.
  - T42: monitoring reports fake providers as fake; production start-up refuses fake, unconfigured or dev stand-ins; the unconfigured adapter never reports paid or affordable.

## 2. Gameplay in the browser — `qa/flow.py` (light and dark), 375×812, Chromium

The whole run is played against the real backend:

- help → start → big reference (UX-6) → reveal → correct ×3 → checkpoint 3;
- an early tap inside the guard does **not** close the checkpoint; the automatic continue closed it 4.4 s after the post-screenshot mark, about 5 s from opening (F-01);
- wrong answer → miss hold → offer appears about 1.7 s after the verdict (F-02 activation after feedback);
- paid continue → G06.SUCCESS → next miss with the limit used → result (new record, rewards paid) → products list with the «اینجا باختی» tag on the losing product (UX-5).

No page errors. The product list was the only scroll container.

## 3. Scenario QA — `qa/states.py` (13 scenarios, light and dark), 0 failures, 0 page errors

A scroll audit ran on every screenshot (UX-4/T45): no page scroll, and no nested scroll except the product list.

| Scenario | Verified result |
|---|---|
| Timeout | LOW_TIME state at about 7.3 s. Keys lock at 10.5 s. Timeout verdict about 12.3 s after release (server accept 11.0 s + proposed 1.0 s grace + poll). Then offer → skip → result |
| Share (T48) | Name-free 360×640 asset previewed. The dev host has no native share, so the honest fallback G09.FAILED "not supported" is shown |
| No funds (T23) | Balance 3 → G05.NO_FUNDS, no offer, then the result |
| Charge rejected | G06.FAILED_RETRY → retry with a **new** key succeeds. Wallet 120 → **110** (one debit) |
| Charge unknown, never applied | G06.PENDING → G06.UNKNOWN after 5 s → G06.FAILED_END.UNCONFIRMED at the deadline. Wallet never debited (121 = 120 + streak reward) |
| Late confirmation (T25/T26) | Unconfirmed at the deadline → G06.REFUNDED when the late debit is refunded. Wallet back to 121 (refunded once) |
| Device replacement (T03) | Second device sees G01.READY.OTHER_DEVICE_RUN → starts → plays. The old device gets G11.OTHER_DEVICE |
| Admin block (T31) | Next action → G11.BLOCKED |
| Game off (T32) | G11.GAME_OFF |
| Quota (T02) | 3 runs used → G01.QUOTA_EXHAUSTED with the reset countdown |
| Campaign (T37) | G01.READY.CAMPAIGN → checkpoint 3 → leave confirm → result with the issued `DEV-…` code → copy (G07.CAMPAIGN_CODE.COPIED) |
| Offline after release (T12) | Keys lock (G10.OFFLINE_AFTER_RELEASE) while the clock keeps running. Back before the cutoff → same question → answered. Offline past the cutoff → timeout; the question is not reopened |
| Offline before release | G10.OFFLINE_BEFORE_RELEASE; no question released; recovers to READY |
| Motion and reduced motion | Filmstrips saved for both |

## 4. Motion — `qa/motion_video.py`

The video was recorded at 375×812 and frames extracted at 30 fps around each transition: start, reveal, answer → verdict → promotion, checkpoint, and the checkpoint tap.

- Reveal: the large reference shrinks into the ticket, then the comparison enters. No layout snaps were seen.
- Answer: about 200 ms of "در حال بررسی پاسخ…", then the verdict and the price plate.
- One frame (33 ms) shows the title cross-fade overlapping during promotion.

Reduced motion removes the movement and keeps the holds. Inspection was by eye on the extracted frames; there is no automated jank metric.

## 5. Admin — `qa/admin_flow.py` (light and dark), 1280×800, 21 screens each, 0 page errors

- Sign-in error.
- A01 draft (دست روزانه ۳ → ۴) → A02 invalid step ("باید بیشتر از ۷ باشد", publish disabled) → fixed → publish confirm with the diff and daily cap → published → restored to 3.
- A03 preview; A04 pair counts per band.
- A05: search empty state; player signals; review; block (reason required) → blocked → unblock; kill switch off/on with confirm.
- A07: validation errors → create → end. A06 history and review filter.
- Viewer: inputs disabled, no version bar, no write buttons.
- No horizontal overflow.

## 6. Production builds — `npm run build`

| Build | Files |
|---|---|
| `dist/player` | `main-*.js` 98.6 KB, `app-*.css` 52.3 KB, fonts, `host-boot.js` |
| `dist/admin` | `app-*.js` 86.1 KB, `app-*.css` 19.6 KB, fonts |

- **Bundle audit passed:** no `/dev/v1`, fake-coin, reviewer, `dev.html`, dev tokens or passwords, local hosts or source maps.
- **Smoke test:** a second backend instance on port 8081 served `dist/`.
  - The production player showed **G11.AUTH_ERROR**, with no console errors. This is correct, because no host bridge is configured.
  - The production admin signed in.
  - Two backend instances shared the same database, and their workers coexisted.

## 7. Catalog depth and load baseline — `qa/load.py 30 20 5`

- **Catalog depth:** 30 perfect players. All ended with `no_products`. Score at the end: min 3, median 74.5, max 315; 4 of 30 ended at score ≤ 5. See `STATUS.md`.
- **Load baseline:** 60 players, 20 concurrent, each doing start → (reveal, poll, answer) ×5 → leave → result.
  - 1,073 requests in 5.9 s, about **181 requests per second**.
  - 2 errors, both `429 RATE_LIMITED` on polls (the proposed 4 per second per-run limit).

| Endpoint | p50 | p95 | max |
|---|---|---|---|
| start | 77 ms | 163 ms | 213 ms |
| reveal | 58 ms | 117 ms | 168 ms |
| poll | 31 ms | 84 ms | 116 ms |
| answer | 45 ms | 107 ms | 180 ms |
| leave | 55 ms | 114 ms | 182 ms |
| result | 28 ms | 81 ms | 157 ms |

**This is a local baseline on a 2-vCPU sandbox with the database on the same host. It is not a production capacity figure (T50).**

## Not verified here

- Real Android or iOS WebViews, safe areas on devices, and the actual Super App host bridge: haptics, native share, deep link, back and close.
- The real DigiCoin service (not connected, by instruction).
- Production login, admin SSO, and the campaign code source.
- Live Digikala catalog ingestion.
- **T47 (partly verified):** answer keys carry accessible labels with the reference price, reduced motion was exercised, and there is no sound. A full keyboard, screen-reader and contrast audit was not done.
- **T49:** memory and listener growth over many runs was not measured.
- Sustained or long-running load, failover, and dependency-failure load tests.
