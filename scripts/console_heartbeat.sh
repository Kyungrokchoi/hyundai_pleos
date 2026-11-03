#!/usr/bin/env bash
# Keeps the Pleos Virtual Console session alive. The console expects POST /browser/session every
# second from an open tab; without it vehicle writes fail with {"error":"emulator_inactive"}.
# Needs `adb forward tcp:8088 tcp:8088`. Usage: scripts/console_heartbeat.sh [client-id]
set -u
CLIENT="${1:-moata-cli}"
BASE="${CONSOLE_BASE:-http://127.0.0.1:8088}"
trap 'curl -sS -m 2 -X POST "$BASE/browser/session?client=$CLIENT&closing=1" >/dev/null 2>&1; exit 0' INT TERM
while true; do
  curl -sS -m 2 -X POST "$BASE/browser/session?client=$CLIENT" >/dev/null 2>&1
  sleep 1
done
