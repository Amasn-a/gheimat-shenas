# قیمت‌شناس (Price Expert) — implementation

<p align="center">
  <a href="https://claude.ai/artifact/17BYdwrzzD1Y97h56XXjHV"><img alt="▶ Play the demo · بازی کن" src="docs/play-button.svg" width="300" height="60"></a>
</p>

> **Demo, not production.** The button opens the clickable prototype: sample products, a simulated server and a fake wallet, with reviewer controls beside the phone. Nothing in it touches real DigiCoin, accounts or Digikala services. If it doesn't open, ask the repo owner for access to the demo page.

Higher-or-lower price game for the Digikala Super App: Java backend module, player web app (Persian RTL, 375×812-first), and a standalone admin app.

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

Needs Java 21, Gradle 8, PostgreSQL 16. QA also needs Python 3 with Playwright and Chromium. The build needs Node 22.

```bash
# database (once)
createuser pe -P            # password: pe
createdb -O pe price_expert && createdb -O pe price_expert_test

cd backend && gradle --offline installDist && cd ..
dev/start-bg.sh             # http://127.0.0.1:8080 — migrations, launch config and the catalog are applied on start
# player (dev host + reviewer panel):   http://127.0.0.1:8080/play/dev.html?user=alice
# admin (dev sign-in editor / editor-dev-password, viewer / viewer-dev-password):   http://127.0.0.1:8080/admin/
dev/stop.sh
```

The development backend uses the **in-project fake DigiCoin provider** and a **development login** (`Bearer dev.<name>`). With `PE_ENV=production` the server refuses to start while any of these stand-ins are configured. See `docs/STATUS.md`.

## Checks

```bash
cd backend && gradle --offline test                 # 57 tests on PostgreSQL (unit + integration + HTTP)
python3 qa/flow.py /tmp/qa light                    # complete run in Chromium at 375×812 (also: dark)
python3 qa/states.py /tmp/qa-states light           # 13 scenarios: timeout, no funds, payments, replacement, block, game-off, quota, campaign, offline, motion
python3 qa/admin_flow.py /tmp/qa-admin light        # admin A01–A07, Editor and Viewer
python3 qa/motion_video.py /tmp/qa-motion           # 30 fps frames of the card transitions
python3 qa/load.py 30 20 5                          # catalog depth + local load baseline (not a capacity claim)
npm install && npm run build                        # dist/player, dist/admin + bundle audit
```

Results of the last run are in `docs/VERIFICATION.md`.
