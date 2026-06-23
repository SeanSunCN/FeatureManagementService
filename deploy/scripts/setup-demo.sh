#!/usr/bin/env bash
# ============================================================
# Setup Demo Data — creates demo-app with complex rules
# Run after deploy: bash deploy/scripts/setup-demo.sh
# ============================================================
set -euo pipefail

ADMIN="${1:-http://localhost:8080}"

# Create demo-app if not exists
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$ADMIN/api/v1/apps/demo-app")
if [ "$STATUS" = "404" ]; then
  curl -s -X POST "$ADMIN/api/v1/apps" \
    -H 'Content-Type: application/json' \
    -d '{"appId":"demo-app","appName":"Demo App","appType":"BACKEND"}' > /dev/null
  echo "[setup] Created demo-app"
else
  echo "[setup] demo-app already exists"
fi

# Create or update 5 demo flags with complex rules
# Use POST if flag doesn't exist, PUT if it does
apply_flag() {
  local KEY="$1"
  local DATA="$2"
  local EXISTS
  EXISTS=$(curl -s -o /dev/null -w "%{http_code}" "$ADMIN/api/v1/apps/demo-app/flags/$KEY")
  if [ "$EXISTS" = "404" ]; then
    curl -s -X POST "$ADMIN/api/v1/apps/demo-app/flags" \
      -H 'Content-Type: application/json' -d "$DATA" > /dev/null
  else
    curl -s -X PUT "$ADMIN/api/v1/apps/demo-app/flags/$KEY" \
      -H 'Content-Type: application/json' -d "$DATA" > /dev/null
  fi
  echo "[setup] $KEY done"
}

echo "[setup] Updating demo flags with complex rules..."

sleep 2

apply_flag "new-ui-portal" '{
  "flagName":"New UI Portal","globalEnabled":true,"defaultStrategy":false,"safeForClient":true,
  "rules":[
    {"ruleId":"r1","ruleName":"US Premium","serveValue":true,
     "conditions":[
       {"attribute":"country","operator":"EQUALS","values":["US"]},
       {"attribute":"plan","operator":"IN","values":["pro","enterprise"]}]},
    {"ruleId":"r2","ruleName":"EU Localized","serveValue":true,
     "conditions":[
       {"attribute":"country","operator":"IN","values":["DE","FR","UK"]},
       {"attribute":"beta_tester","operator":"EQUALS","values":["true"]}]}
  ]}'

apply_flag "dark-mode-v2" '{
  "flagName":"Dark Mode v2","globalEnabled":true,"defaultStrategy":false,"safeForClient":true,
  "rules":[
    {"ruleId":"r1","ruleName":"Advanced Analytics","serveValue":true,
     "conditions":[
       {"attribute":"eval_count","operator":"GREATER_THAN","values":["100"]}]},
    {"ruleId":"r2","ruleName":"Enterprise Suite","serveValue":true,
     "conditions":[
       {"attribute":"plan","operator":"EQUALS","values":["enterprise"]}]}
  ]}'

apply_flag "new-search-portal" '{
  "flagName":"New Search Portal","globalEnabled":true,"defaultStrategy":false,"safeForClient":true,
  "rules":[
    {"ruleId":"r1","ruleName":"Enterprise Search","serveValue":true,
     "conditions":[
       {"attribute":"country","operator":"IN","values":["US","DE","UK"]},
       {"attribute":"plan","operator":"IN","values":["enterprise"]}]},
    {"ruleId":"r2","ruleName":"Pro Search Beta","serveValue":true,
     "conditions":[
       {"attribute":"beta_tester","operator":"EQUALS","values":["true"]},
       {"attribute":"eval_count","operator":"GREATER_THAN","values":["50"]}]},
    {"ruleId":"r3","ruleName":"Basic Search","serveValue":true,
     "conditions":[
       {"attribute":"plan","operator":"IN","values":["pro"]}]}
  ]}'

apply_flag "new-pricing-page" '{
  "flagName":"New Pricing Page","globalEnabled":true,"defaultStrategy":false,"safeForClient":true,
  "rules":[
    {"ruleId":"r1","ruleName":"Enterprise Pricing","serveValue":true,
     "conditions":[
       {"attribute":"plan","operator":"EQUALS","values":["enterprise"]}]},
    {"ruleId":"r2","ruleName":"Regional Pricing","serveValue":true,
     "conditions":[
       {"attribute":"country","operator":"IN","values":["DE","FR","UK"]},
       {"attribute":"role","operator":"NOT_IN","values":["guest"]}]},
    {"ruleId":"r3","ruleName":"Default Pricing","serveValue":false,"conditions":[]}
  ]}'

apply_flag "quick-export" '{
  "flagName":"Quick Export","globalEnabled":true,"defaultStrategy":false,"safeForClient":true,
  "rules":[
    {"ruleId":"r1","ruleName":"Beta Rollout","serveValue":true,
     "conditions":[
       {"attribute":"beta_tester","operator":"EQUALS","values":["true"]}]},
    {"ruleId":"r2","ruleName":"Gradual Rollout","serveValue":true,
     "conditions":[
       {"attribute":"country","operator":"IN","values":["US","DE","UK"]},
       {"attribute":"plan","operator":"NOT_IN","values":["free"]}]}
  ]}'

echo "[setup] All demo flags configured with complex rules"
echo "[setup] Open http://localhost:8084/index.html"
