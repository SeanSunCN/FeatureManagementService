#!/usr/bin/env bash
# ============================================================
# Feature Management Service — Server-side Deploy Script
# ============================================================
# This script runs on the docker.
# It is invoked by:
#   - GitHub Actions (.github/workflows/deploy.yml)
#   - Manually: bash deploy/scripts/deploy.sh
# ============================================================
set -euo pipefail

# The project directory (PROJECT_DIR env var from GitHub Secrets,
# or the git repo root when run manually)
PROJECT_DIR="${PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
cd "$PROJECT_DIR"

# Verify we are in a git repo
if [ ! -d ".git" ]; then
  echo "[ERROR] Not a git repository: $PROJECT_DIR"
  echo "        Run this script from the project root."
  exit 1
fi

echo ""
echo "========================================"
echo " Deploy: Feature Management Service"
echo " Dir:   $PROJECT_DIR"
echo " Date:  $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
echo "========================================"
echo ""

# ---------- 1. Pull latest code ----------
echo ""
echo "[1/5] Pulling latest code..."
git fetch origin main
git checkout main
git pull origin main

CURRENT_COMMIT=$(git rev-parse --short HEAD)
echo "      Commit: $CURRENT_COMMIT"

# ---------- 2. Build JARs ----------
echo ""
echo "[2/5] Building JARs (mvn package -DskipTests)..."

# Check for Maven wrapper, fall back to system mvn
if [ -f "./mvnw" ]; then
  MVN_CMD="./mvnw"
else
  MVN_CMD="mvn"
fi

$MVN_CMD package -DskipTests -T 4C -q

echo "      Build complete."

# ---------- 3. Build Docker images ----------
echo ""
echo "[3/5] Building Docker images..."
docker compose -f deploy/docker/docker-compose.yml build --pull

echo "      Docker images built."

# ---------- 4. Restart containers ----------
echo ""
echo "[4/5] Restarting containers..."
docker compose -f deploy/docker/docker-compose.yml up -d --remove-orphans

echo "      Containers restarted."

# ---------- 5. Health check ----------
echo ""
echo "[5/5] Verifying service health..."
echo "      Waiting 20s for services to stabilize..."
sleep 20

ALL_OK=true
for port in 8080 8081 8082 8083; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$port/actuator/health" || echo "000")
  if [ "$STATUS" = "200" ]; then
    echo "  [OK] Port $port — HEALTHY"
  else
    echo "  [WARN] Port $port — HTTP $STATUS (may still be starting)"
    docker compose -f deploy/docker/docker-compose.yml logs --tail=5 "$(docker compose -f deploy/docker/docker-compose.yml ps --services | grep -m1 .)" 2>/dev/null || true
    ALL_OK=false
  fi
done

echo ""
echo "========================================"
echo " Summary"
echo "========================================"
echo " Commit:   $CURRENT_COMMIT"
if [ "$ALL_OK" = "true" ]; then
  echo " Status:   ALL HEALTHY ✓"
else
  echo " Status:   SOME ISSUES ⚠ — check logs:"
  echo "          docker compose -f deploy/docker/docker-compose.yml logs"
fi

# Clean up dangling images
docker image prune -f 2>/dev/null || true

echo ""
echo "Deploy complete."
