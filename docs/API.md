# Draft API contract (for engineering review — not an existing Digikala API)

Every route, field name and error code below is a **proposal implemented in this repository**. JSON uses snake_case, times are ISO-8601 UTC instants, and amounts are integers (DigiCoin, or Toman where named `*_toman`). Server time is authoritative: every run, operation and bootstrap response carries `server_time`.

## Common rules (player)

- **Auth:** `Authorization: Bearer <Super App token>`. In development the stand-in accepts `dev.<name>` and maps it to user `u_<name>`. A missing or invalid token gets `401 UNAUTHENTICATED`.
- **Device hint:** `X-Device-Id: <uuid>` is required. It decides which device may resume a run (R-28). It is never used for authentication.
- **Idempotency:** every POST requires `Idempotency-Key: <uuid>`, scoped to (user, method, path, key).
  - Same key and same body: the stored response is returned with `Idempotent-Replay: true`.
  - Same key with a different body: `422 IDEMPOTENCY_MISMATCH`.
  - Same key while the first request is still running: `409 REQUEST_IN_PROGRESS`. The first request holds the key on a lease (proposed: 30 s for player routes, 15 min for admin writes). If its process dies, a retry with the same key and body takes the key over after the lease and executes; the domain rules keep that safe (first answer wins, one payment operation per offer).
  - Errors are not stored, so a retry is evaluated again.
  - The client keeps one key per logical action (for example `answer:<question_id>`). It uses a new key only for a deliberately new attempt, such as a retry after a rejected debit.
- **Validation:** unknown fields, duplicate JSON keys, wrong types and bodies over 16 KiB get `422 VALIDATION_FAILED`. The client never sends a time, price, score, cost or balance.
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
| `POST /questions/{question_id}/answer` `{choice: higher\|lower}` | Answer. Accepted while server receive time ≤ `accept_deadline` (11,000 ms inclusive, R-45). The receipt is recorded before any run lock is taken; when the question is due to time out, a recorded receipt is settled as the answer instead, so a slow request cannot turn an on-time answer into a timeout. The first recorded receipt wins. Leave, block and replacement still end an unsettled question with no correctness (R-59) | `AnswerView` |
| `POST /runs/{run_id}/offer` | F-02: activates the continue offer after the miss feedback. The window starts at activation; eligibility is checked again | `ActivationView` (`offer` or `no_offer_reason`) |
| `POST /offers/{offer_id}/continue` | Paid continue (10 DigiCoin, limit 1) | `202 OperationView` |
| `POST /offers/{offer_id}/skip` | «رد کردن» | `RunView` |
| `POST /runs/{run_id}/leave` | Leave confirm (G03.LEAVE_CONFIRM) | `RunView` |
| `GET /operations/{operation_id}` | Payment status polling (G06) | `OperationView` |
| `GET /runs/{run_id}/result` | G07 result | `ResultView` |
| `GET /runs/{run_id}/products` | G08 products of this run (UX-5). `409 FEATURE_DISABLED` when the run's configuration has the products switch off | `ProductsView` |
| `GET /runs/{run_id}/share` | G09 share data (no player name). `409 FEATURE_DISABLED` when the run's configuration has the share switch off | `ShareView` |
| `POST /player/onboarding` | First help seen | `{ok:true}` |

### Main shapes

**RunView**
- Identity and progress: `run_id`, `state` (READY · QUESTION_OPEN · AWAITING_OFFER · OFFER_OPEN · PAYMENT_PENDING · FINISHED), `finish_reason` (wrong · timeout · skipped · expired · continue_failed · left · no_products · inactive · game_off · blocked · replaced), `no_offer_reason` (no_funds · limit_used · game_off · disabled · blocked · no_products), `score`, `record`, `continues_used`, `highest_checkpoint`.
- Products and questions: `reference {display_title, image_url, price}`, `question {question_id, product{display_title, image_url}, t0, input_deadline, accept_deadline}` (never a price), `last_result {question_id, result, choice, revealed_price, equal_prices, direction, settled_at}`.
- Offer and payment: `offer {offer_id, state, opened_at, deadline, cost}`, `operation {operation_id, status}`, `awaiting_offer_until`.
- Device, snapshot and time: `on_this_device`, and `run_config` (the run's config snapshot: `input_ms`, `accept_ms`, `offer_ms`, `checkpoint_auto_continue_ms`, `checkpoints[]`, `continue_rule`, `features {streak, share, products, haptics}` — the A01 switches of that version; the client hides what is off). On another device `question` is null, which keeps the response minimal. Plus `server_time`.

**AnswerView**
- Verdict: `question_id`, `result` (correct · wrong · timeout), `choice`, `equal_prices`, `revealed_price`, `direction`.
- Progress and next step: `score`, `checkpoint_reached`, `continue_possible`, `no_offer_reason`, `run_finished`.
- Context: `run`, `server_time`.

**OperationView**
- Status: `status` (pending · succeeded · failed · refunded · expired_unconfirmed), `charge_status` (unknown · confirmed · failed), `refund_status` (none · pending · succeeded).
- Amounts: `charged_amount`, `refunded_amount`, `balance` (filled only once settled and readable).
- Context: `run`, `server_time`.

**ResultView**
- Run outcome: `score`, `reason`, `no_offer_reason`, `record`, `is_new_record`, `previous_record`, `checkpoint`, `continues_used`, `continue_limit_used`, `next_goal`, `quota`, `balance`, `finished_at`.
- Rewards: `checkpoint_reward {amount, held_amount, status: pending|succeeded|delayed}` and `streak {days, reward, lost_notice}`. Any held part makes the status `delayed`; a held or unknown reward is never shown as paid.
- Campaign: `campaign_code {status: issued|pending|sold_out, code, amount_toman, terms, expires_at}`.

**ProductsView:** `items[] {position, role, display_title, image_url, game_price (null while unanswered), result, is_loss, link}` and `loss_product_id`. `loss_product_id` is set only when the run ended on a miss.

**BootstrapView:** `access {status: ok|blocked|game_off, message}`, `first_visit`, `quota {plays_left, plays_per_day, resets_at}`, `record`, `best_checkpoint`, `streak {days, reward_today, lost_notice}`, `balance`, `active_run`, `config` (same shape as `run_config`, for the active version, including `features`), `streak_reward` (the reward actually paid: 0 while the streak switch is off), `campaign`, `server_time`.

### Error codes (player)

| Code | HTTP | Meaning |
|---|---|---|
| `DAILY_QUOTA_EXHAUSTED` | 409 | No plays left today (G01.QUOTA_EXHAUSTED) |
| `ACTIVE_RUN_EXISTS` | 409 | A run is already open on this device; the body's `state` resumes it |
| `ACTIVE_RUN_OTHER_DEVICE` | 409 | The command came from a device that does not own the run |
| `RUN_REPLACED` · `RUN_FINISHED` | 409 | The run ended; `state` explains why |
| `QUESTION_EXPIRED` | 409 | Answer received after `accept_deadline`; the question settles as a timeout |
| `OFFER_EXPIRED` | 409 | Continue after the offer deadline |
| `CONTINUE_LIMIT_USED` | 409 | Continue limit reached |
| `OPERATION_PENDING` | 409 | A payment is already being processed for this offer |
| `PLAYER_BLOCKED` · `GAME_UNAVAILABLE` | 409 | G11.BLOCKED · G11.GAME_OFF |
| `FEATURE_DISABLED` | 409 | Products or share requested for a run whose configuration has that A01 switch off; `feature` names it |
| `REQUEST_IN_PROGRESS` | 409 | The same key is still being processed (see Idempotency) |
| `IDEMPOTENCY_MISMATCH` | 422 | Key reused with another body |
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
| `POST /config/versions` (GameConfig) | Save the draft (A01–A04) |
| `POST /config/versions/{v}/discard` | Discard the draft |
| `POST /config/versions/{v}/publish` `{version, expected_active_version, reason}` | A00.PUBLISH_CONFIRM. A stale `expected_active_version` gets `409 VERSION_CONFLICT` |
| `POST /difficulty/pair-counts` `{bands}` | A04: ordered product pairs per band in the active catalog |
| `POST /game/availability` `{enabled, player_message, reason}` | A05 kill switch (R-23) |
| `GET /runs?user_id=&run_id=` · `GET /operations?user_id=&run_id=&status=` · `POST /operations/{id}/retry` | A05 search; retry only from NEEDS_REVIEW |
| `GET /players/{user_id}` · `POST /players/{user_id}/block` `{reason, review_reference}` · `POST …/unblock` `{reason}` · `POST /reviews` | A05 review and block (R-22, R-30, R-34) |
| `GET /alerts` · `GET /monitoring` | Operational counters and the integration status (fake vs real) |
| `GET /audit?area=&actor=&from=&to=&before_id=` | A06 change history (100 per page) |
| `GET /campaigns` · `POST /campaigns` · `POST /campaigns/{id}` · `POST /campaigns/{id}/end` | A07 |

## Development-only routes (never registered in production)

`GET|POST /dev/v1/price-expert/fake-coin/{user}` reads or sets a fake wallet balance and scripts provider outcomes: `succeeded`, `failed`, `unknown_applied`, `unknown_not_applied`, `late_success` (with `late_ms`), `unreadable`. Every response carries `fake: true`.
