#!/usr/bin/env bash
set -euo pipefail

ADMIN=http://localhost:8080
EVAL=http://localhost:8081
INGEST=http://localhost:8082

call() {
  local m=$1 u=$2 b=$3
  if [ -n "$b" ]; then
    curl -sf -X "$m" "$u" -H 'Content-Type: application/json' -d "$b"
  else
    curl -sf "$u"
  fi
}

ok() { echo "  PASS  $1"; }
fail() { echo "  FAIL  $1"; exit 1; }

echo '=== 1. Health'
for p in 8080 8081 8082 8083; do
  curl -sf http://localhost:$p/actuator/health | grep -q '"status":"UP"' || fail "$p"
done
ok 'All 4 services UP'

echo '=== 2. Create App'
call POST "$ADMIN/api/v1/apps" '{"appId":"clean-test","appName":"Clean Test","appType":"BACKEND"}' | grep -q '"code":0' || fail 'create app'
ok 'App created'

echo '=== 3. Create 3 Flags'
for f in f1 f2 f3; do
  call POST "$ADMIN/api/v1/apps/clean-test/flags" '{"flagKey":"eated'\n\necho '=== 3. Create 3 Flags'\nfor f in f1 f2 f3; do\n  call POST \"$ADMIN/api/v1/apps/clean-test/flags\" '{\"flagKey\":\"'"$f\"'\",\"name\":\"'"$f\"'\",\"enabled\":true,\"ruleConfig\":\"{}\"}' | grep -q '\"code\":0' || fail \"create $f\"\ndone\nok '3 Flags created'\n\necho '=== 4. Reload + Sync'\ncall POST \"$ADMIN/api/v1/apps/clean-test/flags/reload\" '' > /dev/null\nsleep 4\ncnt=$(curl -sf \"$EVAL/api/v1/eval/flags?appId=clean-test\" | grep -o '\"flagKey\"' | wc -l)\n[ \"$cnt\" -eq 3 ] || fail \"expected 3 got $cnt\"\nok 'EvalService synced 3 flags'\n\necho '=== 5. Evaluate'\ncall POST \"$EVAL/api/v1/eval/evaluate\" '{\"appId\":\"clean-test\",\"flagKey\":\"f1\",\"userId\":\"u1\"}' | grep -q '\"enabled\":true' || fail 'eval f1'\ncall POST \"$EVAL/api/v1/eval/evaluate\" '{\"appId\":\"clean-test\",\"flagKey\":\"f3\",\"userId\":\"u1\"}' | grep -q '\"enabled\":false' || fail 'eval f3 (disabled)'\nok 'Evaluations OK'\n\necho '=== 6. Ingest Metrics'\ncall POST \"$INGEST/api/v1/ingest/metrics\" '{\"appId\":\"clean-test\",\"flagHitCounts\":{\"f1\":10,\"f2\":5}}' | grep -q '\"code\":0' || fail 'metrics'\nok 'Metrics reported'\n\necho '=== 7. Ingest Audit Log'\ncall POST \"$INGEST/api/v1/ingest/audit-log\" '{\"appId\":\"clean-test\",\"flagKey\":\"f1\",\"userId\":\"u1\",\"enabled\":true,\"clientIp\":\"1.2.3.4\"}' | grep -q '\"code\":0' || fail 'audit'\ncall POST \"$INGEST/api/v1/ingest/audit-log/batch\" '[{\"appId\":\"clean-test\",\"flagKey\":\"f1\",\"userId\":\"u1\",\"enabled\":true,\"clientIp\":\"1.2.3.4\"}]' | grep -q '\"code\":0' || fail 'batch audit'\nok 'Audit logs reported'\n\necho '=== 8. Toggle Flag (JSON Body)'\ncall PATCH \"$ADMIN/api/v1/apps/clean-test/flags/f1/enabled\" '{\"enabled\":false}' | grep -q '\"code\":0' || fail 'toggle f1 off'\ncall POST \"$ADMIN/api/v1/apps/clean-test/flags/reload\" '' > /dev/null\nsleep 3\ncall POST \"$EVAL/api/v1/eval/evaluate\" '{\"appId\":\"clean-test\",\"flagKey\":\"f1\",\"userId\":\"u1\"}' | grep -q '\"enabled\":false' || fail 'verify f1 disabled'\nok 'Toggle + verify OK'\n\necho '=== 9. Cleanup'\ncurl -sf -X DELETE \"$ADMIN/api/v1/apps/clean-test\" | grep -q '\"code\":0' || fail 'delete app'\nok 'App deleted'\n\necho ''\necho '=== ALL 19/19 PASSED ==='\n"}