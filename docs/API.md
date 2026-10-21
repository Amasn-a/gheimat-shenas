# Draft API contract (for engineering review — not an existing Digikala API)

Every route, field name and error code below is a **proposal implemented in this repository**. JSON uses snake_case, times are ISO-8601 UTC instants, and amounts are integers (DigiCoin, or Toman where named `*_toman`). Server time is authoritative: every run, operation and bootstrap response carries `server_time`.

## Common rules (player)

- **Auth:** `Authorization: Bearer <Super App token>`. In development the stand-in accepts `dev.<name>` and maps it to user `u_<name>`. A missing or invalid token gets `401 UNAUTHENTICATED`.
- **Device hint:** `X-Device-Id: <uuid>` is required. It decides which device may resume a run (R-28). It is never used for authentication.
- **Idempotency:** every POST requires `Idempotency-Key: <uuid>`. A key belongs to one user and **one operation** (method + path + body): it is unique per (user, key).
  - Same key and same body: the stored response is returned with `Idempotent-Replay: true`.
  - Same key with a different body, or reused on another route or object (another question, another offer): `422 IDEMPOTENCY_MISMATCH`, and **nothing is created** (brief 2026-10-20).
  - Same key while the first request is still running: `409 REQUEST_IN_PROGRESS`. The first request holds the key on a lease (proposed: 30 s for player routes, 15 min for admin writes). If its process dies, a retry with the same key and body takes the key over after the lease and executes; the domain rules keep that safe (first answer wins, one payment operation per offer).
  - Errors are not stored, so a retry is evaluated again.
  - A replayed response carries its original `server_time`; the client does not use it as a clock sample. (If the API is served from another origin, expose `Idempotent-Replay` with `Access-Control-Expose-Headers`.)
  - The client keeps one key per logical action (for example `answer:<question_id>`). It uses a new key only for a deliberately new attempt, such as a retry after a rejected debit.
- **Validation:** unknown fields, duplicate JSON keys, keys that are not the field's snake_case spelling, wrong types (no number/string coercion, no fractional integers), bodies that are not a JSON object, null array elements and bodies over 16 KiB get `422 VALIDATION_FAILED`. The client never sends a time, price, score, cost or balance.
- **Object access:** another user's id, or an unknown id, gets `404 NOT_FOUND`.
- **Rate limits:** `429 RATE_LIMITED`. The values are proposed: start 10 per minute per user; polling 4 per second per run.
- **Error body:** `{ "code", "message", ...details }`. A conflict also carries `state`, the current RunView, so the client can recover without another call.

## Player routes — prefix `/v1/gamification/price-expert`

| Method and path | Purpose (screen IDs) | Response |
|---|---|---|
| `GET /bootstrap` | G01 start, G02 help, G11 access | `BootstrapView` |
| `POST /runs` | Start, or replace another device's run (R-28) | `201 StartView` |
| `GET /runs/{run_id}` | Resume, poll, reconcile | `RunView` |
| `POST /runs/{run_id}/reveal` | «نمایش بده»: releases the next question and starts its clock. A retry returns the same question and deadlines | `RunView` (or FINISHED with `no_products`) |
| `POST /questions/{question_id}/answer` `{choice: higher\|lower}` | Answer. **Admitted by arrival:** the request's first arrival (stamped when its body has been read) must be ≤ `accept_deadline` = `t0` + **12,500 ms inclusive** (brief 2026-10-20; replaces R-45's 11,000). `input_deadline` = `t0` + 10,000 ms is the visible window only. An admitted answer is settled and returned even if processing finishes after 12,500 ms. The receipt is recorded before any run lock is taken; when the question is due to time out, a recorded receipt is settled as the answer instead, so a slow request cannot turn an on-time answer into a timeout. The first recorded receipt wins. Leave, block and replacement still end an unsettled question with no correctness (R-59) | `AnswerView` |
| `POST /runs/{run_id}/offer` | F-02: activates the continue offer after the miss feedback. The window starts at activation; eligibility is checked again | `ActivationView` (`offer` or `no_offer_reason`) |
| `POST /offers/{offer_id}/continue` | Paid continue (10 DigiCoin, limit 1). **Admitted by arrival:** first arrival ≤ the offer's `accept_deadline` = `opened_at` + **15,000 ms inclusive** (brief 2026-10-20). The arrival is recorded before any run lock (receipt-first), so a slow request or the offer's expiry sweep cannot turn an on-time purchase into an expiry. One charge operation per offer; the admitted operation may finish after the deadline, until `resolve_by`. Arrival after `accept_deadline`: `409 OFFER_EXPIRED`, nothing is charged | `202 OperationView` |
| `POST /offers/{offer_id}/skip` | «رد کردن». `409 OPERATION_PENDING` once a purchase has been admitted (or its receipt recorded) for this offer | `RunView` |
| `POST /runs/{run_id}/leave` | Leave confirm (G03.LEAVE_CONFIRM). `409 OPERATION_PENDING` while an admitted purchase is unresolved | `RunView` |
| `GET /operations/{operation_id}` | Payment status polling (G06) | `OperationView` |
| `GET /runs/{run_id}/result` | G05 loss screen (record, campaign code) | `ResultView` |
| `GET /runs/{run_id}/products` | G08 products of this run (UX-5). `409 FEATURE_DISABLED` when the run's configuration has the products switch off | `ProductsView` |
| `GET /runs/{run_id}/share` | G09 share data (no player name). `409 FEATURE_DISABLED` when the run's configuration has the share switch off | `ShareView` |
| `POST /player/onboarding` | Mandatory first-time onboarding finished (G02.ONBOARDING). Stored on the account, not the device | `{ok:true}` |
| `GET /player/daily-reward` | G01.DAILY_REWARD: «جایزه‌ی روزانه» modal | `DailyRewardView` |
| `POST /player/daily-reward/claim` | «دریافت کن». Idempotent: claims every waiting daily reward once, starts the credit, and returns the new status. A second claim (another tap, another device) claims nothing again | `DailyRewardView` |

### Main shapes

**RunView**
- Identity and progress: `run_id`, `state` (READY · QUESTION_OPEN · AWAITING_OFFER · OFFER_OPEN · PAYMENT_PENDING · FINISHED), `finish_reason` (wrong · timeout · skipped · expired · continue_failed · left · no_products · inactive · game_off · blocked · replaced), `no_offer_reason` (no_funds · limit_used · game_off · disabled · blocked · no_products), `score`, `record`, `continues_used`, `highest_checkpoint`.
- Products and questions: `reference {display_title, image_url, price}`, `question {question_id, product{display_title, image_url}, t0, input_deadline, accept_deadline}` (never a price), `last_result {question_id, result, choice, revealed_price, equal_prices, direction, settled_at}`.
- Offer and payment: `offer {offer_id, state, opened_at, deadline (visible zero, opened_at + 10 s), accept_deadline (arrival deadline, opened_at + 15 s), resolve_by (accept_deadline + payment_resolution_ms), cost}`, `operation {operation_id, status}`, `awaiting_offer_until`.
- Device, snapshot and time: `on_this_device`, and `run_config` (the run's config snapshot: `input_ms` (10,000), `accept_ms` (12,500), `offer_ms` (10,000), `offer_accept_ms` (15,000), `checkpoint_auto_continue_ms` (no longer used by the player since the redesign), `checkpoints[] {checkpoint_id, title, badge_asset_id, reward_amount, required_correct, comparison_percent}` (`comparison_percent`: the fixed copy «از {x} درصد آدما باهوش‌تری!», null = none), `continue_rule`, `features {streak, share, products, haptics}`, `pay_on_crossing` (true since the redesign: each checkpoint pays its own reward when crossed), `record_reward` and `record_reward_above` (redesign §13) — the A01 switches of that version; the client hides what is off). On another device `question` is null, which keeps the response minimal. Plus `server_time`.

**AnswerView**
- Verdict: `question_id`, `result` (correct · wrong · timeout), `choice`, `equal_prices`, `revealed_price`, `direction`.
- Progress and next step: `score`, `checkpoint_reached`, `continue_possible`, `no_offer_reason`, `run_finished`.
- Rewards registered by this answer (redesign 2026-10-21): `checkpoint_reward {amount, held_amount, status}` for the checkpoint it crossed (null when that checkpoint pays nothing), `record_reward` (same shape) when it matched the record frozen at run start. A replay of the answer returns the same values and creates nothing.
- Context: `run`, `server_time`.

**OperationView**
- Identity: `operation_id`, `offer_id`, `admitted_at` (when the purchase was admitted), `resolve_by` (after this the run ends unconfirmed and a later debit is refunded), `definitive` (false only while `pending`).
- Status: `status` (pending · succeeded · failed · refunded · expired_unconfirmed), `charge_status` (unknown · confirmed · failed), `refund_status` (none · pending · succeeded).
- Amounts: `charged_amount`, `refunded_amount`, `balance` (filled only once settled and readable).
- Context: `run`, `server_time`.

**ResultView**
- Run outcome: `score`, `reason`, `no_offer_reason`, `record`, `is_new_record`, `previous_record` (still returned; the End Screen no longer shows the record — brief 2026-10-20), `checkpoint`, `continues_used`, `continue_limit_used`, `next_goal`, `quota`, `balance`, `finished_at`.
- Rewards: `checkpoint_reward {amount, held_amount, status: pending|succeeded|delayed|none}` — since the redesign the total of the run's checkpoint and record credits (each registered at its crossing); delayed if any part is. The streak block was removed (brief 2026-10-20: the streak appears only in the daily reward modal). Any held part makes the status `delayed`; a held or unknown reward is never shown as paid.
- Campaign: `campaign_code {status: issued|pending|sold_out, code, amount_toman, terms, expires_at}`.

**ProductsView:** `items[] {position, role, question_id (null for the start product), display_title, image_url, game_price (null while unanswered), result, is_loss, link}` and `loss_product_id`. `loss_product_id` is set only when the run ended on a miss.

**BootstrapView:** `access {status: ok|blocked|game_off, message}`, `first_visit` (true until the onboarding is finished), `quota {plays_left, plays_per_day, resets_at}`, `record`, `best_checkpoint`, `daily_reward` (a `DailyRewardView`), `balance` (shown on the home header since the redesign; the real number only, null when unknown), `active_run`, `config` (same shape as `run_config`, for the active version, including `features`), `campaign`, `last_run_id` (the player's most recent finished run: the home's «کالاهای دست قبلی» reads `GET /runs/{id}/products`; null for a new player), `server_time`. The `streak` and `streak_reward` fields were removed.

**DailyRewardView:** `state` (claimable · processing · claimed · delayed · none), `amount` (of the claimable or latest claimed reward), `streak_days`, `days[] {date, attended, today}` (the last 7 business days), `operation_ids`, `claimed_at`, `server_time`. `processing` lasts up to 2 minutes after a claim (proposed); after that an unconfirmed credit is `delayed`.

**ShareView:** `score`, `checkpoint_title`, `badge_asset_id`, `game_name`, `deep_link` (`streak_days` removed).

### Error codes (player)

| Code | HTTP | Meaning |
|---|---|---|
| `DAILY_QUOTA_EXHAUSTED` | 409 | No plays left today (G01.QUOTA_EXHAUSTED) |
| `ACTIVE_RUN_EXISTS` | 409 | A run is already open on this device; the body's `state` resumes it |
| `ACTIVE_RUN_OTHER_DEVICE` | 409 | The command came from a device that does not own the run |
| `RUN_REPLACED` · `RUN_FINISHED` | 409 | The run ended; `state` explains why |
| `QUESTION_EXPIRED` | 409 | Answer received after `accept_deadline`; the question settles as a timeout |
| `OFFER_EXPIRED` | 409 | Continue whose first arrival is after the offer's `accept_deadline` (15 s); nothing is charged |
| `CONTINUE_LIMIT_USED` | 409 | Continue limit reached |
| `OPERATION_PENDING` | 409 | A purchase was admitted for this offer: skip, leave and a new start wait for it |
| `PLAYER_BLOCKED` · `GAME_UNAVAILABLE` | 409 | G11.BLOCKED · G11.GAME_OFF |
| `FEATURE_DISABLED` | 409 | Products or share requested for a run whose configuration has that A01 switch off; `feature` names it |
| `REQUEST_IN_PROGRESS` | 409 | The same key is still being processed (see Idempotency) |
| `IDEMPOTENCY_MISMATCH` | 422 | Key reused with another body, route or object; nothing is created |
| `VALIDATION_FAILED` | 422 | Bad or unknown input |
| `UNAUTHENTICATED` · `NOT_FOUND` · `RATE_LIMITED` | 401 · 404 · 429 | |

## Admin routes — prefix `/admin/v1/gamification/price-expert`

- **Session:** an opaque bearer token from `POST /session`, held in memory by the admin app, so no cookie is involved. This is a development stand-in for SSO; with SSO cookies, CSRF protection is required.
- **Writes:** need an `Idempotency-Key` and the **EDITOR** role. A VIEWER gets `403 FORBIDDEN`. Every write appends to the audit trail.
- **Audited reads:** reads of runs, operations and players are audited as `admin.read.*`.

| Method and path | Screen |
|---|---|
| `POST /session` `{username, password}` · `DELETE /session` · `GET /me` | A06 sign-in |
| `GET /config` | Active and draft versions, validation errors, diff, daily cap, bounds, game availability |
| `POST /config/versions` (GameConfig), header `If-Match: <draft_revision>` (omit when there is no draft) | Save the draft (A01–A04). Returns `revision`. A draft changed, published or discarded since it was loaded gets `409 DRAFT_CHANGED` |
| `POST /config/versions/{v}/discard`, header `If-Match: <draft_revision>` | Discard the draft (`409 DRAFT_CHANGED` if it changed) |
| `POST /config/versions/{v}/publish` `{version, expected_active_version, draft_revision, reason}` | A00.PUBLISH_CONFIRM. A stale `expected_active_version` gets `409 VERSION_CONFLICT`; a draft that is not the reviewed revision gets `409 DRAFT_CHANGED` |
| `POST /difficulty/pair-counts` `{bands}` | A04: ordered product pairs per band in the active catalog |
| `POST /game/availability` `{enabled, player_message, reason}` | A05 kill switch (R-23) |
| `GET /runs?user_id=&run_id=` · `GET /operations?user_id=&run_id=&status=` · `POST /operations/{id}/retry` `{reason}` | A05 search; retry only from NEEDS_REVIEW; a reason is required (also for unblock and campaign end) |
| `GET /players/{user_id}` · `POST /players/{user_id}/block` `{reason, review_reference}` · `POST …/unblock` `{reason}` · `POST /reviews` | A05 review and block (R-22, R-30, R-34) |
| `GET /alerts` · `GET /monitoring` | Operational counters and the integration status (fake vs real) |
| `GET /audit?area=&actor=&changes_only=1&from=&to=&before_id=` | A06 change history (100 per page). `changes_only=1` leaves out read-access and draft-save events before paging. A publish's `correlation_id` is the version it replaced |
| `GET /entitlements/review` · `POST /entitlements/{id}/retry` `{reason}` | Campaign codes whose issue exhausted its retries; audited re-queue (the provider call keeps the same entitlement id) |
| `GET /campaigns` · `POST /campaigns` · `POST /campaigns/{id}` · `POST /campaigns/{id}/end` | A07 |

## Development-only routes (never registered in production)

- `GET|POST /dev/v1/price-expert/fake-coin/{user}` reads or sets a fake wallet balance and scripts provider outcomes: `succeeded`, `failed`, `unknown_applied`, `unknown_not_applied`, `late_success` (with `late_ms`), `unreadable`. Every response carries `fake: true`.
- `POST /dev/v1/price-expert/player/{user}` `{onboarded, attended_days}`: reviewer fixture. Resets or marks the onboarding, and seeds attended days ending yesterday with an unclaimed daily reward, so the onboarding and the daily reward modal can be reviewed without waiting a day.

## Timing contract (brief 2026-10-20)

| Window | Visible decision window (client) | Arrival deadline (server) | After the deadline |
|---|---|---|---|
| Question | 10 s from `t0` (`input_deadline`); input locks at the visible zero | `accept_deadline` = `t0` + 12,500 ms, inclusive | No new answer is admitted. An answer that arrived in time is still evaluated and returned, whenever its processing ends |
| Extra life | 10 s from `opened_at` (`deadline`); the buy button locks at the visible zero | `accept_deadline` = `opened_at` + 15,000 ms, inclusive | No new purchase is admitted. An admitted purchase keeps waiting for the charge result until `resolve_by` (proposed: 60 s after `accept_deadline`) |

- The deadlines limit the **first arrival** of a request, never the end of its processing or the delivery of its response.
- Expiry never overwrites an admitted operation: the timeout and offer-expiry sweeps settle a recorded receipt as an answer or admit a recorded purchase instead. If a recorded on-time purchase is ever refused, the `ON_TIME_PURCHASE_EXPIRED` alert fires (`ON_TIME_ANSWER_TIMED_OUT` for answers).
- The timeout verdict for a question is written by the sweep at `accept_deadline` + `timeout_grace_ms` (proposed 1,000 ms) and stamped at `accept_deadline`; the offer expiry likewise at `accept_deadline` + grace.
- One operation per question (first recorded receipt wins) and one charge per offer. Retries carry the same `Idempotency-Key`; a lost response is recovered with `GET /operations/{id}` or `GET /runs/{id}`, never with a new purchase.
- A client that cannot tell whether its request arrived shows «در حال بررسی…» and **checks again** with the same key while the request could still arrive in time, then reads the run. Responses of an older screen or operation are ignored.
