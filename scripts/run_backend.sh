#!/usr/bin/env bash
# Starts the MOATA API. Defaults to a local SQLite file; set MOATA_DATABASE_URL for PostgreSQL.
#   MOATA_DATABASE_URL=postgresql+psycopg://moata@127.0.0.1:5432/moata scripts/run_backend.sh
set -euo pipefail
cd "$(dirname "$0")/../backend"
[ -d .venv ] || { uv venv --python 3.12 .venv >/dev/null 2>&1 || python3 -m venv .venv; .venv/bin/pip install -q -r requirements.txt; }
if [[ "${MOATA_DATABASE_URL:-}" == postgresql* ]]; then .venv/bin/alembic upgrade head; fi
exec .venv/bin/uvicorn app.main:app --host "${MOATA_HOST:-0.0.0.0}" --port "${MOATA_PORT:-8000}"
