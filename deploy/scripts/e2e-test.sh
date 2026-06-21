#!/bin/bash
# ============================================================
# Feature Management Service - End-to-End Automated Verification Script
# ============================================================
# Prerequisites: All containers have been started via docker compose up -d
# Usage: bash e2e-test.sh
# ============================================================

set -e

BASE_ADMIN="http://localhost:8080"
BASE_EVAL="http://localhost:8081"
BASE_INGEST="http://localhost:8082"

PASS=0
FAIL=0

green() { echo -e "\033[0;32m$1\033[0m"; }
red()   { echo -e "\033[0;31m$1\033[0m"; }

assert_eq() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    green "  ✅ PASS: $desc"
    ((PASS++))
  else
    red "  ❌ FAIL: $desc (expected=$expected, actual=$actual)"
    ((FAIL++))
  fi
}

echo "========================================"
echo "  E2E Test: Feature Management Service"
echo "========================================"
echo ""

# ---- 1. Health Check ----
echo "[1/8] Health Checks..."
for svc in admin-api eval-service ingest-service metrics-worker; do
  code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_ADMIN/actuator/health" 2>/dev/null || echo "000")
  assert_eq "$svc health" "200" "$code"
done

# ---- 2. Create App ----
echo ""
echo "[2/8] Create App..."
APP_ID=$(curl -s -X POST "$BASE_ADMIN/api/v1/apps" \
  -H "Content-Type: application/json" \
  -d '{"appId":"e2e-app","appName":"E2E Test App","description":"Auto test","appType":"BACKEND"}' | jq -r '.data.appId')
assert_eq "create app" "e2e-app" "$APP_ID"

# ---- 3. Create Feature Flag ----
echo ""
echo "[3/8] Create Feature Flag..."
FLAG_KEY=$(curl -s -X POST "$BASE_ADMIN/api/v1/apps/e2e-app/flags" \
  -H "Content-Type: application/json" \
  -d '{"flagKey":"e2e-flag","name":"E2E Flag","enabled":true,"ruleConfig":"{\"strategy\":\"boolean\",\"enabled\":true}"}' | jq -r '.data.flagKey')
assert_eq "create flag" "e2e-flag" "$FLAG_KEY"

# ---- 4. Verify EvalService Cache Sync ----
echo ""
echo "[4/8] Wait for cache sync (3s)..."
sleep 3
SYNCED=$(curl -s "$BASE_EVAL/api/v1/eval/flags?appId=e2e-app" | jq -r '.data | keys[0]')
assert_eq "eval cache sync" "e2e-flag" "$SYNCED"

# ---- 5. Execute Evaluation ----
echo ""
echo "[5/8] Evaluate Flag..."
EVAL_RESULT=$(curl -s -X POST "$BASE_EVAL/api/v1/eval/evaluate" \
  -H "Content-Type: application/json" \
  -d '{"appId":"e2e-app","flagKey":"e2e-flag","userId":"test-user"}' | jq -r '.data.enabled')
assert_eq "evaluate flag" "true" "$EVAL_RESULT"

# ---- 6. Submit Audit Log ----
echo ""
echo "[6/8] Submit Audit Log..."
AUDIT_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_INGEST/api/v1/ingest/audit-log" \
  -H "Content-Type: application/json" \
  -d '{"appId":"e2e-app","flagKey":"e2e-flag","userId":"test-user","enabled":true,"clientIp":"10.0.0.1","evalCostMs":3,"timestamp":1718700000000}')
assert_eq "submit audit log" "200" "$AUDIT_CODE"

# ---- 7. Toggle Flag State ----
echo ""
echo "[7/8] Toggle Flag Off..."
PATCH_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH "$BASE_ADMIN/api/v1/apps/e2e-app/flags/e2e-flag/enabled" \
  -H "Content-Type: application/json" \
  -d '{"enabled":false}')
assert_eq "toggle flag" "200" "$PATCH_CODE"

sleep 2
EVAL_OFF=$(curl -s -X POST "$BASE_EVAL/api/v1/eval/evaluate" \
  -H "Content-Type: application/json" \
  -d '{"appId":"e2e-app","flagKey":"e2e-flag","userId":"test-user"}' | jq -r '.data.enabled')
assert_eq "evaluate after toggle" "false" "$EVAL_OFF"

# ---- 8. Cleanup ----
echo ""
echo "[8/8] Cleanup..."
DELETE_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$BASE_ADMIN/api/v1/apps/e2e-app")
assert_eq "delete app" "200" "$DELETE_CODE"

# ---- Summary ----
echo ""
echo "========================================"
echo -e "  Results: ${PASS} passed, ${FAIL} failed"
echo "========================================"

[ "$FAIL" -eq 0 ] && exit 0 || exit 1
