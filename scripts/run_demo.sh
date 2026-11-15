#!/usr/bin/env bash



set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo 127.0.0.1)"
DB="$ROOT/backend/demo.db"
[ "${KEEP_DB:-0}" = 1 ] || rm -f "$DB"
export MOATA_DATABASE_URL="sqlite:///$DB"
export MOATA_CORS_ORIGINS="http://localhost:3000,http://127.0.0.1:3000,http://$LAN_IP:3000"
PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT INT TERM

"$ROOT/scripts/run_backend.sh" > "${TMPDIR:-/tmp}/moata-api.log" 2>&1 & PIDS+=($!)
cd "$ROOT/passenger-web"

unset NEXT_PUBLIC_API_BASE
[ -f .next/.demo-build ] || { npm run build && touch .next/.demo-build; }
npx next start -H 0.0.0.0 -p 3000 > "${TMPDIR:-/tmp}/moata-web.log" 2>&1 & PIDS+=($!)

for i in $(seq 1 40); do curl -fsS "http://127.0.0.1:8000/api/v1/health" >/dev/null 2>&1 && break; sleep 0.5; done
for i in $(seq 1 40); do curl -fsS "http://127.0.0.1:3000/" >/dev/null 2>&1 && break; sleep 0.5; done
echo "API  : http://$LAN_IP:8000/api/v1/docs   (emulator: http://10.0.2.2:8000)"
echo "WEB  : http://$LAN_IP:3000               (phones on the same Wi-Fi)"
echo "Logs : ${TMPDIR:-/tmp}/moata-api.log  ${TMPDIR:-/tmp}/moata-web.log     Ctrl-C to stop."
wait
