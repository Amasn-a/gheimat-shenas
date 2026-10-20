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

## Owner decisions applied where older sources conflict

| Topic | Older source | Applied |
|---|---|---|
| Checkpoint overlay | R-64 (tap only) | **F-01:** a tap anywhere after the guard (1,200 ms) **or** automatic continue at **5,000 ms (proposed)**. Whichever happens first advances once; the other is cancelled |
| Continue offer window | Handoff: the window opens at the miss | **F-02:** the window starts at a post-feedback activation call. The server checks eligibility again (game-off, funds). Wrong-answer feedback does not consume the window |
| Payout cap day | — | **F-03:** a credit counts against the cap of the run's **start** business day, even if it is paid later |
| Block during a payment | Handoff: end after the payment resolves | Contract: the run ends **immediately**. A debit confirmed afterwards is refunded once (T27) |
| Answer timing | Older 5 s spec | R-45: the label shows 10 s, input locks at 10,500 ms, and the server accepts up to 11,000 ms inclusive (receive time stamped at ingress) |
| Daily plays, continue, cap | Older spec values | 3 plays a day (R-33). Continue costs 10, limit 1 (R-20). Checkpoints 3/5/7/10 pay 5/10/20/40, highest only. Streak reward 1. Daily cap 121 with a high-water mark |
| A01 inactivity field | The A01 board draws it as an input | The owner fixed it at 3 minutes, so it is shown **read-only** to avoid changing timing without approval. This only matters if the owner wants it editable |
| Images | Opaque per-question image proxy (handoff option) | CDN URL released only **after** reveal, never before (T05). The proxy was not built |

## Proposed values (implemented, labelled, awaiting approval or tuning)

- **Timing:**
  - `checkpoint_auto_continue_ms` 5,000 (F-01, proposed).
  - `offer_activation_ms` 180,000: how long an unactivated miss waits before the run ends. This reuses the inactivity value.
  - `timeout_grace_ms` 1,000: the timeout worker waits this long after the accept deadline. Since the answer receipt is now recorded before any run lock, the grace only has to cover ingress → one single-row write, so lowering it to around 300–500 ms (to shorten the ~12.3 s timeout verdict) is safer than before. Watch the `ON_TIME_ANSWER_TIMED_OUT` alert: it fires if an on-time answer still lost to a timeout, which means the grace is too short for the real latency.
- **Payments after the offer closes:** a charge that was never sent is closed without touching the wallet. One that may have reached the provider is never sent again; it is reconciled by status query only (refunded once if it lands) and moves to NEEDS_REVIEW with an alert when the 72 h retry window ends. If DigiCoin guarantees that "not found" is final after its timeout (D06), it could be closed sooner.
- **Selection:** a reveal first tries a random sample of 48 candidates per direction, then every candidate of the band. Look-ahead uses an in-memory price index of the pinned catalog version (the last 4 versions are cached; published versions never change).
- **Workers:** coin operations of up to 4 players are processed at once (one player's in order). A provider gets at most 64 concurrent calls per instance; beyond that a call is answered "unknown" without being sent and reconciled later. Reading one request may take at most 10 s (`sun.net.httpserver.maxReqTime`, standalone HTTP edge only).
- **Quota screen:** at the reset instant the player asks the server again (every 2 s, at most 5 times) instead of trusting the local countdown.
- **Idempotency lease:** 30 s for player requests, 15 min for admin writes (game-off finishes every active run). A retry after the lease takes the key over (recovery after a crash). Rows written by an older instance without a lease age from their creation time.
- **A01 switches:** a run follows the switches of the configuration version it started with, like every other setting. Turning the streak switch off hides the streak card, row and share line and pays no streak reward; the server still counts streak days in the background (**unresolved:** should counting pause while the switch is off?).
- **Motion durations** (`player/src/durations.js`): correct hold 1,300 ms, miss hold 1,700, promotion 540, shrink 340, checkpoint guard 1,200.
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
