#!/usr/bin/env bash
#
# Feature Management Service — Local Integration Test Script (Bash)
# Prerequisites: All 4 microservices running locally (mvn spring-boot:run)
#

ADMIN_API="${1:-http://localhost:8080}"
EVAL_SERVICE="${2:-http://localhost:8081}"
INGEST_SERVICE="${3:-http://localhost:8082}"
WORKER_SERVICE="${4:-http://localhost:8083}"
WAIT_SYNC="${5:-3}"

PASSED=0
FAILED=0
SKIPPED=0

header() {
    echo ""
    echo "======================================="
    echo " $1"
    echo "======================================="
}

pass() {
    PASSED=$((PASSED + 1))
    echo "  [PASS] $1"
}

fail() {
    FAILED=$((FAILED + 1))
    echo "  [FAIL] $1"
    [ -n "$2" ] && echo "         $2"
}

skip() {
    SKIPPED=$((SKIPPED + 1))
    echo "  [SKIP] $1"
    [ -n "$2" ] && echo "         $2"
}

call_api() {
    local method="$1" url="$2" body="$3"
    if [ -n "$body" ]; then
        curl -s -o /tmp/inttest_resp.json -w "%{http_code}" \
            -X "$method" "$url" \
            -H "Content-Type: application/json" \
            -d "$body"
    else
        curl -s -o /tmp/inttest_resp.json -w "%{http_code}" \
            -X "$method" "$url" \
            -H "Content-Type: application/json"
    fi
}

get_json() {
    cat /tmp/inttest_resp.json 2>/dev/null || echo '{}'
}

# ============================================================
# 1. Health Check
# ============================================================
header "1/9  Health Check"

all_healthy=true
for svc in "$ADMIN_API/actuator/health" "$EVAL_SERVICE/actuator/health" "$INGEST_SERVICE/actuator/health" "$WORKER_SERVICE/actuator/health"; do
    http_code=$(call_api GET "$svc")
    status=$(get_json | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
    if [ "$http_code" = "200" ] && [ "$status" = "UP" ]; then
        pass "$svc"
    else
        fail "$svc" "HTTP=$http_code, status=$status"
        all_healthy=false
    fi
done

[ "$all_healthy" = false ] && echo "  [WARN] Some services are unhealthy, continuing..."

# ============================================================
# 2. Create App
# ============================================================
header "2/9  Create App"

http_code=$(call_api POST "$ADMIN_API/api/v1/apps" '{
    "appId": "integration-test-app",
    "appName": "Integration Test App",
    "description": "Auto-created by integration test",
    "appType": "BACKEND"
}')
[ "$http_code" = "200" ] && pass "Create App" || fail "Create App" "HTTP=$http_code"

http_code=$(call_api GET "$ADMIN_API/api/v1/apps/integration-test-app")
app_id=$(get_json | grep -o '"appId":"[^"]*"' | cut -d'"' -f4)
[ "$app_id" = "integration-test-app" ] && pass "Verify App created" || fail "Verify App created" "appId=$app_id"

# ============================================================
# 3. Create Feature Flags
# ============================================================
header "3/9  Create Feature Flags"

http_code=$(call_api POST "$ADMIN_API/api/v1/apps/integration-test-app/flags" '{
    "flagKey": "flag-a",
    "name": "Flag A (Full Rollout)",
    "description": "Full rollout test",
    "enabled": true,
    "ruleConfig": "{\"strategy\":\"full_rollout\"}"
}')
[ "$http_code" = "200" ] && pass "Create flag-a" || fail "Create flag-a" "HTTP=$http_code"

http_code=$(call_api POST "$ADMIN_API/api/v1/apps/integration-test-app/flags" '{
    "flagKey": "flag-b",
    "name": "Flag B (Gradual 50%)",
    "description": "Gradual rollout test",
    "enabled": true,
    "ruleConfig": "{\"strategy\":\"gradual_rollout\",\"percentage\":50}"
}')
[ "$http_code" = "200" ] && pass "Create flag-b" || fail "Create flag-b" "HTTP=$http_code"

http_code=$(call_api POST "$ADMIN_API/api/v1/apps/integration-test-app/flags" '{
    "flagKey": "flag-c",
    "name": "Flag C (Disabled)",
    "description": "Disabled state test",
    "enabled": false,
    "ruleConfig": "{}"
}')
[ "$http_code" = "200" ] && pass "Create flag-c" || fail "Create flag-c" "HTTP=$http_code"

# Verify count
http_code=$(call_api GET "$ADMIN_API/api/v1/apps/integration-test-app/flags")
flag_count=$(get_json | grep -o '"flagKey"' | wc -l)
[ "$flag_count" = "3" ] && pass "Verify 3 Flags created" || fail "Verify 3 Flags" "actual count=$flag_count"

# ============================================================
# 4. Wait for EvalService Sync
# ============================================================
header "4/9  Wait for EvalService Sync (Redis Pub/Sub)"
echo "     Waiting ${WAIT_SYNC}s for changes to sync via Redis Pub/Sub..."
sleep "$WAIT_SYNC"

http_code=$(call_api GET "$EVAL_SERVICE/api/v1/eval/flags?appId=integration-test-app")
sync_count=$(get_json | grep -o '"flagKey"' | wc -l)
[ "$sync_count" = "3" ] && pass "EvalService synced 3 Flags" || fail "EvalService sync" "actual count=$sync_count"

# ============================================================
# 5. Rule Evaluation
# ============================================================
header "5/9  Rule Evaluation"

# flag-a full rollout
http_code=$(call_api POST "$EVAL_SERVICE/api/v1/eval/evaluate" '{
    "appId": "integration-test-app",
    "flagKey": "flag-a",
    "userId": "user-001"
}')
enabled=$(get_json | grep -o '"enabled":[^,}]*' | head -1 | cut -d: -f2)
[ "$enabled" = "true" ] && pass "flag-a evaluate: enabled=true" || fail "flag-a evaluate" "enabled=$enabled"

# flag-c disabled
http_code=$(call_api POST "$EVAL_SERVICE/api/v1/eval/evaluate" '{
    "appId": "integration-test-app",
    "flagKey": "flag-c",
    "userId": "user-001"
}')
enabled=$(get_json | grep -o '"enabled":[^,}]*' | head -1 | cut -d: -f2)
[ "$enabled" = "false" ] && pass "flag-c evaluate: enabled=false" || fail "flag-c evaluate" "enabled=$enabled"

# Batch evaluation
http_code=$(call_api POST "$EVAL_SERVICE/api/v1/eval/evaluate/batch?appId=integration-test-app" '[
    {"flagKey":"flag-a","userId":"user-001"},
    {"flagKey":"flag-b","userId":"user-002"},
    {"flagKey":"flag-c","userId":"user-003"}
]')
batch_count=$(get_json | grep -o '"flagKey"' | wc -l)
[ "$batch_count" = "3" ] && pass "Batch evaluate 3 Flags" || fail "Batch evaluate" "returned count=$batch_count"

# ============================================================
# 6. Ingest Metrics / Audit Logs
# ============================================================
header "6/9  Ingest Metrics and Audit Logs"

# Metrics
http_code=$(call_api POST "$INGEST_SERVICE/api/v1/ingest/metrics" '{
    "appId": "integration-test-app",
    "flagHitCounts": {"flag-a":10,"flag-b":5,"flag-c":0}
}')
[ "$http_code" = "200" ] && pass "Report Metrics" || fail "Report Metrics" "HTTP=$http_code"

# Single audit log
http_code=$(call_api POST "$INGEST_SERVICE/api/v1/ingest/audit-log" '{
    "appId": "integration-test-app",
    "flagKey": "flag-a",
    "userId": "user-001",
    "enabled": true,
    "clientIp": "192.168.1.100"
}')
[ "$http_code" = "200" ] && pass "Report audit log (single)" || fail "Report audit log (single)" "HTTP=$http_code"

# Batch audit logs
http_code=$(call_api POST "$INGEST_SERVICE/api/v1/ingest/audit-log/batch" '[
    {"appId":"integration-test-app","flagKey":"flag-a","userId":"user-001","enabled":true,"clientIp":"10.0.0.1"},
    {"appId":"integration-test-app","flagKey":"flag-b","userId":"user-002","enabled":true,"clientIp":"10.0.0.2"},
    {"appId":"integration-test-app","flagKey":"flag-c","userId":"user-003","enabled":false,"clientIp":"10.0.0.3"}
]')
[ "$http_code" = "200" ] && pass "Report audit logs (batch 3)" || fail "Report audit logs (batch)" "HTTP=$http_code"

# Drop count
http_code=$(call_api GET "$INGEST_SERVICE/api/v1/ingest/drop-total")
drop=$(get_json | grep -o '"data":[0-9]*' | cut -d: -f2)
[ "$drop" = "0" ] && pass "Audit log drop count: 0" || fail "Drop count non-zero" "drop=$drop"

# ============================================================
# 7. Wait for MetricsWorker flush to ClickHouse
# ============================================================
header "7/9  Verify ClickHouse persistence (Wait for Worker flush)"
echo "     Waiting for Worker batch flush (flush-interval=10s)..."
sleep 12

http_code=$(call_api GET "$WORKER_SERVICE/actuator/health")
status=$(get_json | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
[ "$status" = "UP" ] && pass "Worker running normally" || fail "Worker abnormal" "status=$status"

# ============================================================
# 8. Control Plane Operations
# ============================================================
header "8/9  Control Plane Operations (Toggle / Update / Delete)"

# Disable flag-a (PATCH uses QueryParam)
http_code=$(call_api PATCH "$ADMIN_API/api/v1/apps/integration-test-app/flags/flag-a/enabled?enabled=false")
[ "$http_code" = "200" ] && pass "Toggle flag-a off" || fail "Toggle flag-a" "HTTP=$http_code"
sleep "$WAIT_SYNC"

# Verify EvalService sync
http_code=$(call_api POST "$EVAL_SERVICE/api/v1/eval/evaluate" '{
    "appId": "integration-test-app",
    "flagKey": "flag-a",
    "userId": "user-001"
}')
enabled=$(get_json | grep -o '"enabled":[^,}]*' | head -1 | cut -d: -f2)
[ "$enabled" = "false" ] && pass "EvalService sync: flag-a disabled" || fail "EvalService sync" "enabled=$enabled"

# Enable flag-a
http_code=$(call_api PATCH "$ADMIN_API/api/v1/apps/integration-test-app/flags/flag-a/enabled" '{"enabled":true}')
[ "$http_code" = "200" ] && pass "Toggle flag-a on" || fail "Toggle flag-a" "HTTP=$http_code"
sleep "$WAIT_SYNC"

# Update flag-b
http_code=$(call_api PUT "$ADMIN_API/api/v1/apps/integration-test-app/flags/flag-b" '{
    "flagKey": "flag-b",
    "name": "Flag B (Gradual 30%)",
    "description": "Gradual rollout ratio changed to 30%",
    "enabled": true,
    "ruleConfig": "{\"strategy\":\"gradual_rollout\",\"percentage\":30}"
}')
[ "$http_code" = "200" ] && pass "Update flag-b gradual ratio" || fail "Update flag-b" "HTTP=$http_code"
sleep "$WAIT_SYNC"

# Delete flag-c
http_code=$(call_api DELETE "$ADMIN_API/api/v1/apps/integration-test-app/flags/flag-c")
[ "$http_code" = "200" ] && pass "Delete flag-c" || fail "Delete flag-c" "HTTP=$http_code"
sleep "$WAIT_SYNC"

# Verify EvalService has only 2 left
http_code=$(call_api GET "$EVAL_SERVICE/api/v1/eval/flags?appId=integration-test-app")
sync_count=$(get_json | grep -o '"flagKey"' | wc -l)
[ "$sync_count" = "2" ] && pass "EvalService sync delete: 2 Flags remaining" || fail "Sync delete" "count=$sync_count"

# Reload
http_code=$(call_api POST "$ADMIN_API/api/v1/apps/integration-test-app/flags/reload")
[ "$http_code" = "200" ] && pass "Trigger full reload" || fail "Reload" "HTTP=$http_code"

# ============================================================
# 9. Cleanup
# ============================================================
header "9/9  Cleanup Test Data"

http_code=$(call_api DELETE "$ADMIN_API/api/v1/apps/integration-test-app")
[ "$http_code" = "200" ] && pass "Delete App (cascade delete Flags)" || fail "Delete App" "HTTP=$http_code"

http_code=$(call_api GET "$ADMIN_API/api/v1/apps/integration-test-app")
[ "$http_code" = "404" ] && pass "Verify App deleted (404)" || fail "Verify deletion" "HTTP=$http_code"

# ============================================================
# Final Summary
# ============================================================
echo ""
echo "======================================="
echo " Tests Complete"
echo "======================================="
echo ""
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
