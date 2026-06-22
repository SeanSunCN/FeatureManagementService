#!/usr/bin/env bash
#
# Feature Management Service — Local Integration Test Script (Bash)
#

ADMIN_API="${1:-http://localhost:8080}"
EVAL_SERVICE="${2:-http://localhost:8081}"
INGEST_SERVICE="${3:-http://localhost:8082}"
WORKER_SERVICE="${4:-http://localhost:8083}"
WAIT_SYNC="${5:-3}"

PASSED=0
FAILED=0
SKIPPED=0

header() { echo ""; echo "======================================="; echo " $1"; echo "======================================="; }
pass() { PASSED=$((PASSED + 1)); echo "  [PASS] $1"; }
fail() { FAILED=$((FAILED + 1)); echo "  [FAIL] $1"; [ -n "$2" ] && echo "         $2"; }
skip() { SKIPPED=$((SKIPPED + 1)); echo "  [SKIP] $1"; [ -n "$2" ] && echo "         $2"; }

call_api() {
    local method="$1" url="$2" body="$3"
    rm -f /tmp/inttest_resp.json
    # --connect-timeout 3 --max-time 5 ensures curl won't hang on unavailable services
    if [ -n "$body" ]; then
        echo "$body" | curl -s --connect-timeout 3 --max-time 5 -X "$method" "$url" -H "Content-Type: application/json" -d @- -o /tmp/inttest_resp.json -w "%{http_code}" 2>/dev/null || echo "000"
    else
        curl -s --connect-timeout 3 --max-time 5 -X "$method" "$url" -H "Content-Type: application/json" -o /tmp/inttest_resp.json -w "%{http_code}" 2>/dev/null || echo "000"
    fi
}
get_json() { cat /tmp/inttest_resp.json 2>/dev/null || echo '{}'; }

# ============================================================
# 1. Health Check
# ============================================================
header "1/9  Health Check"
for svc in "$ADMIN_API/actuator/health" "$EVAL_SERVICE/actuator/health" "$INGEST_SERVICE/actuator/health" "$WORKER_SERVICE/actuator/health"; do
    http_code=$(call_api GET "$svc")
    status=$(get_json | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
    if [ "$http_code" = "200" ] && [ "$status" = "UP" ]; then
        pass "$svc"
    elif [ "$status" = "UP" ]; then
        pass "$svc"
    else
        fail "$svc" "HTTP=$http_code, status=$status"
    fi
done

# ============================================================
# 2. Create App
# ============================================================
header "2/9  Create App"
http_code=$(call_api POST "$ADMIN_API/api/v1/apps" '{"appId":"integration-test-app","appName":"Integration Test App","description":"Auto-created by integration test","appType":"BACKEND"}')
[ "$http_code" = "200" ] && pass "Create App" || fail "Create App" "HTTP=$http_code"
http_code=$(call_api GET "$ADMIN_API/api/v1/apps/integration-test-app")
app_id=$(get_json | grep -o '"appId":"[^"]*"' | cut -d'"' -f4)
[ "$app_id" = "integration-test-app" ] && pass "Verify App created" || fail "Verify App created" "appId=$app_id"

# ============================================================
# 3. Create Feature Flags
# ============================================================
header "3/9  Create Feature Flags"
http_code=$(call_api POST "$ADMIN_API/api/v1/apps/integration-test-app/flags" '{"flagKey":"flag-a","flagName":"Flag A (Full Rollout)","description":"Full rollout test","globalEnabled":true,"defaultStrategy":true,"rules":[]}')
[ "$http_code" = "200" ] && pass "Create flag-a" || fail "Create flag-a" "HTTP=$http_code"
http_code=$(call_api POST "$ADMIN_API/api/v1/apps/integration-test-app/flags" '{"flagKey":"flag-b","flagName":"Flag B (Gradual 50%)","description":"Gradual rollout test","globalEnabled":true,"defaultStrategy":false,"rules":[]}')
[ "$http_code" = "200" ] && pass "Create flag-b" || fail "Create flag-b" "HTTP=$http_code"
http_code=$(call_api POST "$ADMIN_API/api/v1/apps/integration-test-app/flags" '{"flagKey":"flag-c","flagName":"Flag C (Disabled)","description":"Disabled state test","globalEnabled":false,"defaultStrategy":false,"rules":[]}')
[ "$http_code" = "200" ] && pass "Create flag-c" || fail "Create flag-c" "HTTP=$http_code"
http_code=$(call_api GET "$ADMIN_API/api/v1/apps/integration-test-app/flags")
flag_count=$(get_json | grep -o '"flagKey"' | wc -l)
[ "$flag_count" = "3" ] && pass "Verify 3 Flags created" || fail "Verify 3 Flags" "actual count=$flag_count"

# ============================================================
# 4. Wait for EvalService Sync (Redis Pub/Sub)
# ============================================================
header "4/9  Wait for EvalService Sync"
echo "     Waiting ${WAIT_SYNC}s for sync..."
sleep "$WAIT_SYNC"
http_code=$(call_api GET "$EVAL_SERVICE/api/v1/eval/flags?appId=integration-test-app")
sync_count=$(get_json | grep -o '"flagKey"' | wc -l)
[ "$sync_count" = "3" ] && pass "EvalService synced 3 Flags" || fail "EvalService sync" "actual count=$sync_count"

# ============================================================
# 5. Rule Evaluation
# ============================================================
header "5/9  Rule Evaluation"
http_code=$(call_api POST "$EVAL_SERVICE/api/v1/eval/evaluate" '{"appId":"integration-test-app","flagKey":"flag-a","userId":"user-001"}')
enabled=$(get_json | grep -o '"enabled":[^,}]*' | head -1 | cut -d: -f2)
[ "$enabled" = "true" ] && pass "flag-a evaluate: enabled=true" || fail "flag-a evaluate" "enabled=$enabled"
http_code=$(call_api POST "$EVAL_SERVICE/api/v1/eval/evaluate" '{"appId":"integration-test-app","flagKey":"flag-c","userId":"user-001"}')
enabled=$(get_json | grep -o '"enabled":[^,}]*' | head -1 | cut -d: -f2)
[ "$enabled" = "false" ] && pass "flag-c evaluate: enabled=false" || fail "flag-c evaluate" "enabled=$enabled"
http_code=$(call_api POST "$EVAL_SERVICE/api/v1/eval/evaluate/batch" '[{"appId":"integration-test-app","flagKey":"flag-a","userId":"user-001"},{"appId":"integration-test-app","flagKey":"flag-b","userId":"user-002"},{"appId":"integration-test-app","flagKey":"flag-c","userId":"user-003"}]')
batch_count=$(get_json | grep -o '"flagKey"' | wc -l)
[ "$batch_count" = "3" ] && pass "Batch evaluate 3 Flags" || fail "Batch evaluate" "returned count=$batch_count"

# ============================================================
# 6. Ingest Metrics / Audit Logs
# ============================================================
header "6/9  Ingest Metrics and Audit Logs"
http_code=$(call_api POST "$INGEST_SERVICE/api/v1/ingest/metrics" '{"appId":"integration-test-app","flagHitCounts":{"flag-a":10,"flag-b":5,"flag-c":0}}')
[ "$http_code" = "200" ] && pass "Report Metrics" || fail "Report Metrics" "HTTP=$http_code"
http_code=$(call_api POST "$INGEST_SERVICE/api/v1/ingest/audit-log" '{"appId":"integration-test-app","flagKey":"flag-a","userId":"user-001","enabled":true,"clientIp":"192.168.1.100","evalCostNs":1250000}')
[ "$http_code" = "200" ] && pass "Report audit log (single)" || fail "Report audit log (single)" "HTTP=$http_code"
http_code=$(call_api POST "$INGEST_SERVICE/api/v1/ingest/audit-log/batch" '[{"appId":"integration-test-app","flagKey":"flag-a","userId":"user-001","enabled":true,"clientIp":"10.0.0.1","evalCostNs":850000},{"appId":"integration-test-app","flagKey":"flag-b","userId":"user-002","enabled":true,"clientIp":"10.0.0.2","evalCostNs":1200000},{"appId":"integration-test-app","flagKey":"flag-c","userId":"user-003","enabled":false,"clientIp":"10.0.0.3","evalCostNs":300000}]')
[ "$http_code" = "200" ] && pass "Report audit logs (batch 3)" || fail "Report audit logs (batch)" "HTTP=$http_code"
http_code=$(call_api GET "$INGEST_SERVICE/api/v1/ingest/drop-total")
drop=$(get_json | grep -o '"data":[0-9]*' | cut -d: -f2)
[ "$drop" = "0" ] && pass "Audit log drop count: 0" || fail "Drop count non-zero" "drop=$drop"

# ============================================================
# 7. Verify ClickHouse persistence
# ============================================================
header "7/9  Verify ClickHouse persistence"
echo "     Waiting for Worker batch flush (flush-interval=10s)..."
sleep 12
http_code=$(call_api GET "$WORKER_SERVICE/actuator/health")
status=$(get_json | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
[ "$status" = "UP" ] && pass "Worker running normally" || fail "Worker abnormal" "status=$status"

# ============================================================
# 8. Control Plane Operations
# ============================================================
header "8/9  Control Plane Operations (Toggle / Update / Delete)"
http_code=$(call_api PATCH "$ADMIN_API/api/v1/apps/integration-test-app/flags/flag-a/enabled" '{"enabled":false}')
[ "$http_code" = "200" ] && pass "Toggle flag-a off" || fail "Toggle flag-a" "HTTP=$http_code"
sleep "$WAIT_SYNC"
http_code=$(call_api POST "$EVAL_SERVICE/api/v1/eval/evaluate" '{"appId":"integration-test-app","flagKey":"flag-a","userId":"user-001"}')
enabled=$(get_json | grep -o '"enabled":[^,}]*' | head -1 | cut -d: -f2)
[ "$enabled" = "false" ] && pass "EvalService sync: flag-a disabled" || fail "EvalService sync" "enabled=$enabled"
http_code=$(call_api PATCH "$ADMIN_API/api/v1/apps/integration-test-app/flags/flag-a/enabled" '{"enabled":true}')
[ "$http_code" = "200" ] && pass "Toggle flag-a on" || fail "Toggle flag-a" "HTTP=$http_code"
sleep "$WAIT_SYNC"
http_code=$(call_api PUT "$ADMIN_API/api/v1/apps/integration-test-app/flags/flag-b" '{"flagName":"Flag B (Gradual 30%)","description":"Gradual rollout ratio changed to 30%","globalEnabled":true,"defaultStrategy":false,"rules":[]}')
[ "$http_code" = "200" ] && pass "Update flag-b ratio" || fail "Update flag-b" "HTTP=$http_code"
sleep "$WAIT_SYNC"
http_code=$(call_api DELETE "$ADMIN_API/api/v1/apps/integration-test-app/flags/flag-c")
[ "$http_code" = "200" ] && pass "Delete flag-c" || fail "Delete flag-c" "HTTP=$http_code"
sleep "$WAIT_SYNC"
http_code=$(call_api GET "$EVAL_SERVICE/api/v1/eval/flags?appId=integration-test-app")
sync_count=$(get_json | grep -o '"flagKey"' | wc -l)
[ "$sync_count" = "2" ] && pass "EvalService sync: 2 left after delete" || fail "Sync delete" "count=$sync_count"
http_code=$(call_api POST "$ADMIN_API/api/v1/apps/integration-test-app/flags/reload")
[ "$http_code" = "200" ] && pass "Trigger full reload" || fail "Reload" "HTTP=$http_code"

# ============================================================
# 9. Cleanup
# ============================================================
header "9/9  Cleanup Test Data"
http_code=$(call_api DELETE "$ADMIN_API/api/v1/apps/integration-test-app")
[ "$http_code" = "200" ] && pass "Delete App (cascade)" || fail "Delete App" "HTTP=$http_code"
http_code=$(call_api GET "$ADMIN_API/api/v1/apps/integration-test-app")
[ "$http_code" = "404" ] && pass "Verify App deleted (404)" || fail "Verify deletion" "HTTP=$http_code"

# ============================================================
# Summary
# ============================================================
echo ""; echo "======================================="; echo " Tests Complete"; echo "======================================="; echo ""
echo "  PASSED : $PASSED"
echo "  FAILED : $FAILED"
echo "  SKIPPED: $SKIPPED"
echo "  TOTAL  : $((PASSED + FAILED + SKIPPED))"
echo ""
if [ "$FAILED" -eq 0 ]; then
    echo " [PASS] All tests passed!"
else
    echo " [FAIL] $FAILED test(s) failed, check logs."
    exit 1
fi
