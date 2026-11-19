#!/usr/bin/env bash
# Smoke test of a running tung-server: is it up, does it talk to its database,
# and does it serve what clients load. Read-only: no account is made, nothing
# is written. Run it after every deploy, and CI runs it against the Docker
# image it builds.
#
#   scripts/smoke-server.sh                         # https://tung.xuper.fun
#   scripts/smoke-server.sh http://localhost:8080   # a local or CI server
#
# Exit 0 when every check passes, 1 otherwise (each failure is printed).
set -uo pipefail

BASE="${1:-https://tung.xuper.fun}"
BASE="${BASE%/}"
failures=0

pass() { printf '  ok    %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; failures=$((failures + 1)); }

# check NAME EXPECTED_STATUS PATH [curl args…]: the status, and the body kept in $body.
body_file="$(mktemp)"
trap 'rm -f "$body_file"' EXIT
check() {
    local name="$1" expected="$2" path="$3"; shift 3
    local status
    status="$(curl -sS --max-time 20 -o "$body_file" -w '%{http_code}' "$@" "$BASE$path" 2>&1)" || true
    if [[ "$status" == "$expected" ]]; then pass "$name ($status)"; return 0; fi
    fail "$name: expected $expected, got $status"
    return 1
}
contains() { grep -q -- "$2" "$body_file" && pass "$1" || fail "$1: the body does not contain '$2'"; }

echo "tung-server smoke test against $BASE"

# The database: /healthz answers 503 database_unavailable when SELECT 1 fails.
check "GET /healthz" 200 /healthz && contains "healthz says ok" '"status":"ok"'

# The API is routed and its document is served.
check "GET /openapi.yaml" 200 /openapi.yaml && contains "openapi.yaml is the spec" 'openapi:'
check "GET /api/v1/auth/methods" 200 /api/v1/auth/methods

# Auth is on: a sync without a token is refused, not served.
check "POST /api/v1/sync without a token" 401 /api/v1/sync -X POST -H 'Content-Type: application/json' -d '{}'

# The web app's CORS preflight lets X-Tung-Device through (CLAUDE.md, "Web": without
# it every web sync fails while sign-in works).
check "CORS preflight for sync" 200 /api/v1/sync -X OPTIONS -D "$body_file.h" \
    -H 'Origin: https://example.org' -H 'Access-Control-Request-Method: POST' \
    -H 'Access-Control-Request-Headers: authorization,content-type,x-tung-device' \
    && { grep -qi '^access-control-allow-headers:.*x-tung-device' "$body_file.h" \
        && pass "preflight allows X-Tung-Device" || fail "preflight does not allow X-Tung-Device"; }
rm -f "$body_file.h"

# The site and the web app (served from TUNG_WEB_DIR in the image).
check "GET / (landing page)" 200 /
check "GET /privacy" 200 /privacy
check "GET /app/ (web app shell)" 200 /app/ && contains "the shell loads tung.js" 'tung.js'
if check "GET /app/tung.js" 200 /app/tung.js; then
    size="$(wc -c < "$body_file")"
    if (( size > 500000 )); then pass "tung.js is $size bytes"; else fail "tung.js is only $size bytes"; fi
fi
check "GET /app/sw.js" 200 /app/sw.js

if (( failures > 0 )); then
    echo "$failures check(s) failed."
    exit 1
fi
echo "All checks passed."
