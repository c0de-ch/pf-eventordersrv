#!/usr/bin/env bash
# End-to-end smoke test against a running stack. Usage: scripts/smoke-test.sh [base-url]
set -euo pipefail
BASE=${1:-${BASE_URL:-http://localhost:8080}}

fail() { echo "FAIL: $*" >&2; exit 1; }
for tool in curl jq; do command -v "$tool" >/dev/null || fail "$tool is required"; done

wait_for() { # url seconds
  for _ in $(seq 1 "$2"); do curl -sf --max-time 5 "$1" >/dev/null && return 0; sleep 1; done
  fail "$1 not reachable within $2s"
}
create() { # customerId amount -> id
  curl -sf --max-time 10 -X POST "$BASE/orders" -H 'Content-Type: application/json' \
    -d "{\"customerId\":\"$1\",\"amount\":$2}" | jq -r .id
}
wait_status() { # id status seconds
  local status=
  for i in $(seq 1 "$3"); do
    status=$(curl -sf --max-time 5 "$BASE/orders/$1" | jq -r .status)
    [ "$status" = "$2" ] && { echo "   order $1 is $2 after ${i}s"; return 0; }
    sleep 1
  done
  fail "order $1 is $status, expected $2 within $3s"
}
http_code() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@"; }

echo "1. readiness";                    wait_for "$BASE/actuator/health/readiness" 90
echo "2. order is accepted and processed"
id=$(create 12345 99.90); wait_status "$id" PROCESSED 30
history=$(curl -sf "$BASE/orders/$id" | jq -r '[.history[].to] | join(",")')
[ "$history" = "ACCEPTED,PROCESSING,PROCESSED" ] || fail "unexpected history: $history"
echo "3. failing order ends in FAILED via the dead-letter queue"
fid=$(create FAIL-1 10); wait_status "$fid" FAILED 60
reason=$(curl -sf "$BASE/orders/$fid" | jq -r '.history[-1].reason')
[[ "$reason" == *dead-lettered* ]] || fail "unexpected failure reason: $reason"
echo "4. validation error is a problem detail"
code=$(http_code -X POST "$BASE/orders" -H 'Content-Type: application/json' -d '{"customerId":"","amount":-1}')
[ "$code" = 400 ] || fail "expected 400, got $code"
echo "5. unknown order is 404"
code=$(http_code "$BASE/orders/999999999"); [ "$code" = 404 ] || fail "expected 404, got $code"
echo "6. metrics and API docs"
curl -sf "$BASE/actuator/prometheus" | grep -q '^orders_accepted_total' || fail "orders_accepted_total missing"
curl -sf "$BASE/v3/api-docs" | jq -e '.paths["/orders"]' >/dev/null || fail "OpenAPI document missing"
echo "PASS: order service smoke test against $BASE"
