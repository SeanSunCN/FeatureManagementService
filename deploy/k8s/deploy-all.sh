#!/usr/bin/env bash
#
# Feature Management Service — one-click local K8s deployment script
# Prerequisites: Docker Desktop K8s enabled, kubectl context pointing to docker-desktop
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

cd "$SCRIPT_DIR"

echo "========================================"
echo " Step 0: Check kubectl connectivity"
echo "========================================"
kubectl cluster-info --request-timeout=5s || { echo "❌ K8s unreachable, please confirm Docker Desktop K8s is enabled"; exit 1; }
echo "✅ K8s cluster is reachable"

echo ""
echo "========================================"
echo " Step 1: Create Namespace"
echo "========================================"
kubectl apply -f namespace.yaml
echo "✅ flag-system namespace ready"

echo ""
echo "========================================"
echo " Step 2: Deploy ConfigMap"
echo "========================================"
kubectl apply -f configmap.yaml
echo "✅ Configuration injected"

echo ""
echo "========================================"
echo " Step 3: Create NAS middleware bridge (ExternalName)"
echo "========================================"
kubectl apply -f infra-bridge.yaml
echo "✅ Middleware DNS bridge established"

echo ""
echo "========================================"
echo " Step 4: Deploy 4 microservices + NodePort exposure"
echo "========================================"
kubectl apply -f admin-api-deployment.yaml
kubectl apply -f eval-service-deployment.yaml
kubectl apply -f ingest-service-deployment.yaml
kubectl apply -f metrics-worker-deployment.yaml
echo "✅ Deployments and Services submitted"

echo ""
echo "========================================"
echo " Step 5: Wait for Pods to be ready"
echo "========================================"
echo "Waiting (up to 120 seconds)..."
kubectl wait --for=condition=available --timeout=120s deployment/flag-admin-api -n flag-system
kubectl wait --for=condition=available --timeout=120s deployment/flag-eval-service -n flag-system
kubectl wait --for=condition=available --timeout=120s deployment/flag-ingest-service -n flag-system
kubectl wait --for=condition=available --timeout=120s deployment/flag-metrics-worker -n flag-system
echo ""
echo "✅ All services are ready!"

echo ""
echo "========================================"
echo " Access"
echo "========================================"
echo ""
echo "NodePort access (inside Docker Desktop Linux VM):"
echo "  http://localhost:30080  ← AdminAPI"
echo "  http://localhost:30081  ← EvalService"
echo "  http://localhost:30082  ← IngestService"
echo "  http://localhost:30083  ← MetricsWorker"
echo ""
echo "Port-Forward access (any local port):"
echo "  kubectl port-forward svc/flag-admin-api 8080:8080 -n flag-system"
echo "  kubectl port-forward svc/flag-eval-service 8081:8081 -n flag-system"
echo "  kubectl port-forward svc/flag-ingest-service 8082:8082 -n flag-system"
echo "  kubectl port-forward svc/flag-metrics-worker 8083:8083 -n flag-system"
echo ""
echo "Health checks:"
echo "  curl http://localhost:30080/actuator/health"
echo "  curl http://localhost:30081/actuator/health"
echo "  curl http://localhost:30082/actuator/health"
echo "  curl http://localhost:30083/actuator/health"
echo ""
