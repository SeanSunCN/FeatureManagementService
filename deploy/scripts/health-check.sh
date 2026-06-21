#!/bin/bash
# ============================================================
# Feature Management Service - Health Check Script
# For local Docker Compose development environment
# ============================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

SERVICES=(
  "flag-admin-api:8080"
  "flag-eval-service:8081"
  "flag-ingest-service:8082"
  "flag-metrics-worker:8083"
)

all_healthy=true

echo "========================================"
echo "  Feature Management Service Health Check"
echo "========================================"
echo ""

for svc in "${SERVICES[@]}"; do
  name="${svc%%:*}"
  port="${svc##*:}"

  printf "%-25s" "${name} (port ${port}):"

  # CURL check actuator/health
  response=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 --max-time 5 "http://localhost:${port}/actuator/health" 2>/dev/null || echo "000")

  if [ "$response" = "200" ]; then
    echo -e "${GREEN}healthy (HTTP ${response})${NC}"
  elif [ "$response" = "000" ]; then
    echo -e "${RED}unreachable${NC}"
    all_healthy=false
  else
    echo -e "${YELLOW}unexpected status (HTTP ${response})${NC}"
    all_healthy=false
  fi
done

echo ""
if [ "$all_healthy" = true ]; then
  echo -e "${GREEN}✅ All services are healthy.${NC}"
  exit 0
else
  echo -e "${RED}❌ Some services are unhealthy.${NC}"
  exit 1
fi
