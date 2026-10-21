# Setup and configuration

## Stack

- **Backend:** Java 21, no framework in the domain or application layers. Ports (`application/port/out`) are implemented by JDBC repositories, the coin adapter, the code provider and the authenticator. `PriceExpertModule` is the composition root.
  - For standalone runs and tests, `PriceExpertServer` puts a small JDK `HttpServer` edge (virtual threads) in front of it.
  - Inside supercoin, the Spring application would construct `PriceExpertModule` and map the same routes. Spring was not available in this environment because Maven Central was blocked, so the HTTP edge is the stand-in.
- **Database:** PostgreSQL 16. Migrations live in `backend/src/main/resources/db/migration` and use Flyway file names (`V1__…sql`). A minimal built-in migrator applies them, so Flyway can take over unchanged.
- **Libraries:** postgresql 42.7.2, HikariCP 2.7.9, Jackson 2.14, slf4j 1.7, JUnit 5.10. The team build (`backend/gradlew`, Gradle 8.14.3) resolves them from Maven Central or the company mirror. A machine without Maven Central can use a local Maven-layout repository: `./gradlew build -PofflineRepo=/usr/share/maven-repo` (the Debian/Ubuntu `lib*-java` packages; JUnit then runs through the console standalone jar).
- **Player and admin:** vanilla ES modules, no runtime dependencies, bundled with esbuild 0.28.2 (`npm run build`). The build target is `es2021`. The WebView floor is not known yet (it depends on the host docs).
- **QA:** Python 3, Playwright with Chromium, psycopg2, ffmpeg (for frames from the motion video).

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `PE_ENV` | `development` | Set to `production` to refuse every development stand-in: fake or unconfigured coin, fake code provider, dev login, missing `PE_CODE_KEY` |
| `PE_DB_URL` / `PE_DB_USER` / `PE_DB_PASSWORD` / `PE_DB_POOL` | `jdbc:postgresql://127.0.0.1:5432/price_expert`, `pe`, `pe`, `20` | Database connection |
| `PE_HTTP_HOST` / `PE_HTTP_PORT` | `0.0.0.0` / `8080` | Standalone HTTP edge |
| `PE_COIN_PROVIDER` | `fake` in development, `unconfigured` in production | `fake` is the in-project DigiCoin stand-in (R-44). `unconfigured` is the placeholder for the real adapter: balance unknown, every mutation UNKNOWN |
| `PE_COIN_TIMEOUT_MS` | `2000` | Deadline per coin call; a timeout is recorded as UNKNOWN and reconciled by query |
| `PE_FAKE_START_BALANCE` | `120` | Development only: starting balance of a fake wallet |
| `PE_CODE_KEY` | dev-derived key in development | Base64 AES key for campaign codes at rest. **Required** in production |
| `PE_CATALOG_JSON` | — | Product database JSON to import and publish when no catalog is active yet |
| `PE_MEDIA_PREFIX` / `PE_MEDIA_DIR` | — | Development only: serve product images from a local folder under `/media/` instead of the CDN |
| `PE_PLAYER_DIR` / `PE_ADMIN_DIR` | — | Static folders served under `/play/` and `/admin/` (the source folder in development, `dist/…` for a production build) |
| `PE_CSP` | strict default (self, Digikala image CDN, Google fonts) | Content-Security-Policy for the HTML pages |
| `PE_RATE_LIMIT` | on | `off` disables the proposed per-user and per-run limits (tests only) |
| `PE_DEV_ADMINS` | `editor:EDITOR:editor-dev-password,viewer:VIEWER:viewer-dev-password` | Development admin accounts (PBKDF2). Never seeded in production |
| `PE_BOUND_MAX_*` | unset | Admin numeric bounds, which are still pending owner values. Only structural invariants are enforced while these are unset |

The defaults for local development are in `.env.example`. `dev/run-backend.sh` sets them.

## Database notes

- Schema migrations `V1`–`V4`. Every table is prefixed `pe_`. The development fake-wallet tables (`pe_fake_*`) are created too; production never writes them, and they can be dropped there.
- **Financial uniqueness is held by partial unique indexes, not by application memory.** Each of these can exist only once:
  - an active run per user;
  - a checkpoint reward per run;
  - a streak reward per user and cap day;
  - a refund per charge;
  - a live charge per offer.
- `pe_audit_event` is append-only, enforced by a trigger that rejects UPDATE and DELETE.
- `pe_idempotency` rows are purged hourly by a worker (24 h retention, proposed).
- `V4__timing_contract_daily_claim.sql` (brief 2026-10-20) adds the offer's `accept_deadline` (back-filled with the old `deadline`) and `purchase_received_at`, the `CLAIMABLE` status and `claimed_at` for daily rewards, `offer_accept_ms` / `payment_resolution_ms` in stored configurations, and a unique index on (`scope`, `idem_key`) for idempotency keys. At start-up the backend publishes, once, a copy of the active configuration with the launch timing (10 s / 12.5 s, 10 s / 15 s, 60 s resolution) when the active one lacks it; the publish is audited as `system:migration`, and running games keep the version they started with.

### Deploying V4

**Stop every backend instance before applying V4, then start the new version.** Do not run old and new instances side by side:
- the unique index on (`scope`, `idem_key`) fails to build if duplicate keys exist across routes (none are expected; check with `SELECT scope, idem_key FROM pe_idempotency GROUP BY 1,2 HAVING count(*) > 1` and purge expired rows first);
- an old instance would read and write offers without `accept_deadline` / `purchase_received_at` and would ignore `CLAIMABLE` rewards;
- an offer opened by an old instance gets `accept_deadline = deadline` (no extra arrival margin); this only affects offers open during the deploy.

## Running in production (what is still missing)

Production start-up is refused until these are replaced (see `docs/STATUS.md`):

- the real DigiCoin adapter;
- the Super App player authenticator;
- the campaign code source.

Admin sign-in is a development stand-in for organisation SSO. The player's `host-boot.js` has to call `configureHost({...})` with the real bridge. Without it the production player shows G11.AUTH_ERROR, which is the expected, truthful behaviour. `host-boot.js` is the production entry point: the build bundles it together with `host.js` and the game, so its `configureHost` call configures the same adapter the game uses (it loads the game only after configuring). Replace the empty object there and rebuild; do not load a separate copy of `host.js`.
