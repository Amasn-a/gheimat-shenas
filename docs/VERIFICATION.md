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
