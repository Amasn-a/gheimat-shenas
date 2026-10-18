# Integration status, decisions applied, and open items

## Integrations — fake or real

| Integration | Status | Notes |
|---|---|---|
| DigiCoin (balance, charge, credit, refund, query) | **FAKE — in-project adapter** (R-44) | DB-backed fake with scriptable outcomes. The real or test DigiCoin service is **not connected and not called**. `UnconfiguredCoinGateway` is the placeholder for the real adapter (balance unknown, every mutation UNKNOWN). Production start-up refuses both |
| Player login (Super App token) | **Development stand-in** | `Bearer dev.<name>`. The real token-validation contract has not been supplied. Production start-up refuses the stand-in |
| Admin sign-in | **Development stand-in** | Username and password with PBKDF2, server-side roles. Organisation SSO with MFA is not available here |
| Campaign code source | **FAKE** | Codes look like `DEV-XXXX…` and are AES-encrypted at rest. The source and account-binding contract are still open. Production start-up refuses it |
| Super App host bridge (back, close, haptics, share, deep link, theme) | **Adapter only, not wired** | `player/src/host.js` defines one adapter. Production `host-boot.js` is empty, so the build shows G11.AUTH_ERROR until the integration calls `configureHost`. No bridge names were invented |
| Product catalog | **Real data, offline import** | The 1,000-product sample collected earlier from Digikala is imported. 992 rows imported (8 rejected for a zero price); 989 eligible. Live ingestion is not built: `api.digikala.com` is not reachable from this environment |
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
  - `timeout_grace_ms` 1,000: an answer received ≤ 11,000 ms but still in flight can't lose to the timeout worker. Side effect: the timeout verdict shows about 12.3 s after release (measured). Lowering it to around 300–500 ms would shorten that gap.
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
