#!/bin/bash
# ============================================================
# Feature Management Service - Environment Readiness Check Script
# Verify all dependent middleware services are available
# ============================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

INFRA_SERVICES=(
  "PostgreSQL:dev_postgres:5432"
  "Redis:dev_redis:6379"
  "Kafka:dev_kafka:9092"
  "ClickHouse:dev_clickhouse:8123"
  "Prometheus:dev_prometheus:9090"
  "Grafana:dev_grafana:3000"
)

all_ready=true

echo "========================================"
echo "  Infrastructure Readiness Check"
echo "========================================"
echo ""

for entry in "${INFRA_SERVICES[@]}"; do
  name="${entry%%:*}"
  rest="${entry#*:}"
  host="${rest%%:*}"
  port="${rest##*:}"

  printf "%-25s" "${name} (${host}:${port}):"

  # Check port using /dev/tcp (Bash built-in)
  timeout 3 bash -c "echo > /dev/tcp/${host}/${port}" 2>/dev/null && {
    echo -e "${GREEN}reachable${NC}"
  } || {
    echo -e "${RED}unreachable${NC}"
    all_ready=false
  }
done

echo ""

# Maven and JDK version check
echo "--- Build Tools ---"
printf "%-25s" "JDK:"
java_version=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
echo -e "${GREEN}${java_version}${NC}"

printf "%-25s" "Maven:"
maven_version=$(mvn -version 2>&1 | head -1 | cut -d' ' -f3)
echo -e "${GREEN}${maven_version}${NC}"

echo ""
if [ "$all_ready" = true ]; then
  echo -e "${GREEN}✅ All infrastructure services are ready.${NC}"
  exit 0
else
  echo -e "${YELLOW}⚠️  Some infrastructure services are not reachable.${NC}"
  echo "   (This is normal if you haven't started the middleware stack yet.)"
  exit 1
fi
