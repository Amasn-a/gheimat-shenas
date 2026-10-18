#!/usr/bin/env bash
# Development backend: fake DigiCoin, dev login, local PostgreSQL. Never use these settings in production.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PE_ENV=development
export PE_DB_URL="${PE_DB_URL:-jdbc:postgresql://127.0.0.1:5432/price_expert}"
export PE_DB_USER="${PE_DB_USER:-pe}" PE_DB_PASSWORD="${PE_DB_PASSWORD:-pe}"
export PE_HTTP_PORT="${PE_HTTP_PORT:-8080}"
export PE_CATALOG_JSON="${PE_CATALOG_JSON:-$ROOT/data/qeymatshenas_products.json}"
export PE_PLAYER_DIR="${PE_PLAYER_DIR:-$ROOT/player}"
export PE_ADMIN_DIR="${PE_ADMIN_DIR:-$ROOT/admin}"
if [ -d "$ROOT/data/images" ]; then export PE_MEDIA_DIR="$ROOT/data/images" PE_MEDIA_PREFIX=/media/; fi
exec "$ROOT/backend/build/install/price-expert-backend/bin/price-expert-backend"
