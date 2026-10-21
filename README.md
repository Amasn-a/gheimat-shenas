# قیمت‌شناس (Price Expert) — implementation

Higher-or-lower price game for the Digikala Super App: a Java backend module, the player web app (Persian RTL, light only, checked at 360×640, 390×844 and 430×932) and a standalone admin app. Real player login, the Super App host bridge, DigiCoin and the campaign code source are **not connected yet**: development uses clearly labelled stand-ins, and production start-up refuses them (see `docs/STATUS.md`).

| Folder | What it is |
|---|---|
| `backend/` | Java 21 module `com.digikala.supercoin.gamification.games.priceexpert`, framework-free hexagonal core, PostgreSQL via JDBC, JDK HTTP edge for standalone runs, migrations, JUnit 5 integration tests on a real database |
| `player/` | Player app (vanilla ES modules). `index.html` is the production entry. `dev.html` + `dev/` are the **development** host and reviewer controls; they are never bundled |
| `admin/` | Standalone admin (A00–A07) with its own sign-in, navigation and shell |
| `data/` | Real 1,000-product sample (`qeymatshenas_products.json`). `data/images/` is git-ignored |
| `dev/` | Scripts to run the development backend (fake DigiCoin, dev login) |
| `qa/` | Playwright drivers (gameplay, states, admin, motion video), test-only price oracle, load and catalog-depth script |
| `scripts/build.mjs` | Production builds of player and admin into `dist/`, plus a bundle audit |
| `docs/` | Setup, draft API contract, integration status and blockers, verification log |

## Quick start (development)

Needs Java 21 and PostgreSQL 16 (Gradle comes with the wrapper). QA also needs Python 3 with Playwright and Chromium. The build needs Node 22.

```bash
# database (once)
createuser pe -P            # password: pe
createdb -O pe price_expert && createdb -O pe price_expert_test

cd backend && ./gradlew installDist && cd ..   # offline machine: add -PofflineRepo=/usr/share/maven-repo
dev/start-bg.sh             # http://127.0.0.1:8080 — migrations, launch config and the catalog are applied on start
# player (dev host + reviewer panel):   http://127.0.0.1:8080/play/dev.html?user=alice
# admin (dev sign-in editor / editor-dev-password, viewer / viewer-dev-password):   http://127.0.0.1:8080/admin/
dev/stop.sh
```

The development backend uses the **in-project fake DigiCoin provider** and a **development login** (`Bearer dev.<name>`). With `PE_ENV=production` the server refuses to start while any of these stand-ins are configured. See `docs/STATUS.md`.

## Checks

Run from the repository root (the backend tests need `price_expert_test`; the browser checks need the development backend running):

```bash
(cd backend && ./gradlew test)                      # 111 tests on PostgreSQL (unit + integration + HTTP, timing contract, daily reward, difficulty)
python3 qa/brief.py /tmp/qa-brief                   # onboarding, daily reward, gameplay, checkpoint, purchase, End Screen at 360×640, 390×844, 430×932 + DOM audit
python3 qa/states.py /tmp/qa-states [scenario…]     # 13 scenarios: timeout, no funds, payments, replacement, block, game-off, quota, campaign, offline, motion
python3 qa/lifecycle.py [check…]                    # 15 client lifecycle checks (stale responses, timing boundaries, checkpoint once, purchase recovery, Back, light only)
python3 qa/admin_flow.py /tmp/qa-admin              # admin A01–A07, Editor and Viewer
python3 qa/motion_video.py /tmp/qa-motion           # 30 fps frames of the card transitions
python3 qa/load.py 30 20 5                          # catalog depth + local load baseline (not a capacity claim)
npm install && npm run build                        # dist/player, dist/admin + bundle audit
```

Run the browser checks one at a time (game-off, block and quota scenarios affect every open run). QA scripts and tests change data: point them only at disposable databases (the test environment refuses a database whose name does not end in `_test`).

Results of the last run are in `docs/VERIFICATION.md`.
