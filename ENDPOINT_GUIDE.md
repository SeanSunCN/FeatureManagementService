# Feature Management Service — Docker Compose End-to-End Startup Guide

## Prerequisites

### ✅ Middleware Ready

| Service | Hostname | Port | Verification Command |
|---------|----------|------|----------------------|
| PostgreSQL | `dev_postgres` | 5432 | `docker exec -i dev_postgres psql -U postgres -c 'SELECT 1'` |
| Redis | `dev_redis` | 6379 | `docker exec -i dev_redis redis-cli PING` |
| Kafka | `dev_kafka` | 9092 | `docker exec -i dev_kafka kafka-topics.sh --bootstrap-server localhost:9092 --list` |
| ClickHouse | `dev_clickhouse` | 8123 | `docker exec -i dev_clickhouse clickhouse-client --query 'SELECT 1'` |

> All middleware must run on the same Docker external network `infra_net`.
> If the network does not exist yet, create it first: `docker network create infra_net`

### ✅ Project Build

```bash
cd /path/to/project
mvn clean package -DskipTests
```

---

## Step 1: Initialize the Database (Optional)

JPA's `ddl-auto: update` will create tables automatically, so manual initialization is generally unnecessary.
If needed, execute:

```bash
# PostgreSQL table creation (JPA ddl-auto: update handles this automatically, generally not needed)
docker exec -i dev_postgres psql -U postgres -d flag_db < deploy/scripts/postgres-init.sql

# ClickHouse table creation (must be executed once)
docker exec -i dev_clickhouse clickhouse-client < deploy/scripts/clickhouse-init.sql
```

---

## Step 2: Start the Services

```bash
cd deploy/docker
docker compose up -d --build
```

Wait approximately 30 seconds for all services to complete initialization. Verify with health checks:

```bash
# Check service status one by one
curl -s http://localhost:8080/actuator/health | jq .
curl -s http://localhost:8081/actuator/health | jq .
curl -s http://localhost:8082/actuator/health | jq .
curl -s http://localhost:8083/actuator/health | jq .

# Or use the one-shot health check script
bash deploy/scripts/health-check.sh
```

Expected output (each service):
```json
{"status":"UP"}
```

> If `docker compose` fails, try `docker-compose` (with hyphen).

---

## Step 3: End-to-End Verification — Full Flow

### 3.1 Create an App

```bash
curl -s -X POST http://localhost:8080/api/v1/apps \
  -H "Content-Type: application/json" \
  -d '{
    "appId": "demo-app",
    "appName": "Demo Application",
    "description": "End-to-end test app",
    "appType": "BACKEND"
  }' | jq .
```

Expected response:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "appId": "demo-app",
    "appName": "Demo Application",
    ...
  }
}
```

Verify:
```bash
# Query all applications
curl -s http://localhost:8080/api/v1/apps | jq .
```

---

### 3.2 Create a Feature Flag

```bash
curl -s -X POST http://localhost:8080/api/v1/apps/demo-app/flags \
  -H "Content-Type: application/json" \
  -d '{
    "flagKey": "new-checkout-flow",
    "name": "New Checkout Flow",
    "description": "Gradual rollout for new checkout flow",
    "enabled": true,
    "ruleConfig": "{\"strategy\":\"gradual_rollout\",\"percentage\":50}"
  }' | jq .
```

Expected response:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "flagKey": "new-checkout-flow",
    "enabled": true,
    ...
  }
}
```

---

### 3.3 Observe EvalService Rule Sync

After creating or updating a flag, AdminAPI notifies EvalService in real-time via Redis Pub/Sub.
Wait 2-3 seconds, then query the EvalService snapshot endpoint:

```bash
# View the full rule snapshot from EvalService
curl -s "http://localhost:8081/api/v1/eval/flags?appId=demo-app" | jq .
```

Expected output, confirming the flag has been synced to memory:
```json
{
  "code": 0,
  "data": {
    "new-checkout-flow": {
      "flagKey": "new-checkout-flow",
      "name": "New Checkout Flow",
      "enabled": true,
      "ruleConfig": "{\"strategy\":\"gradual_rollout\",\"percentage\":50}",
      "version": 0
    }
  }
}
```

> If empty, check the EvalService logs: `docker logs flag-eval-service`

---

### 3.4 Execute Rule Evaluation

#### Single Evaluation
```bash
# Check whether user-1001 hits the canary rollout (50% rollout, determined by userId hash)
curl -s -X POST http://localhost:8081/api/v1/eval/evaluate \
  -H "Content-Type: application/json" \
  -d '{
    "appId": "demo-app",
    "flagKey": "new-checkout-flow",
    "userId": "user-1001"
  }' | jq .
```

Expected response:
```json
{
  "code": 0,
  "data": {
    "flagKey": "new-checkout-flow",
    "enabled": true,
    "matchedRule": "rule-matched",
    "evalCostMs": 0
  }
}
```

Try different userId values to observe the canary effect:
```bash
curl -s -X POST http://localhost:8081/api/v1/eval/evaluate \
  -H "Content-Type: application/json" \
  -d '{"appId":"demo-app","flagKey":"new-checkout-flow","userId":"user-9999"}' | jq .
```

#### Batch Evaluation
```bash
curl -s -X POST "http://localhost:8081/api/v1/eval/evaluate/batch?appId=demo-app" \
  -H "Content-Type: application/json" \
  -d '[
    {"flagKey":"new-checkout-flow","userId":"user-1001"},
    {"flagKey":"new-checkout-flow","userId":"user-9999"}
  ]' | jq .
```

---

### 3.5 Submit Audit Log

Submit an audit record through IngestService.

> Note: The `timestamp` field is generated server-side by IngestService — the client must not and cannot pass it,
> preventing client clock skew or malicious tampering from causing ClickHouse partition explosion.

```bash
curl -s -X POST http://localhost:8082/api/v1/ingest/audit-log \
  -H "Content-Type: application/json" \
  -d '{
    "appId": "demo-app",
    "flagKey": "new-checkout-flow",
    "userId": "user-1001",
    "enabled": true,
    "clientIp": "192.168.1.100",
    "evalCostMs": 5
  }' | jq .
```

Expected response (IngestService writes asynchronously via Kafka producer, returns success):
```json
{
  "code": 0,
  "message": "success"
}
```

Batch submission is also supported:
```bash
curl -s -X POST http://localhost:8082/api/v1/ingest/audit-log/batch \
  -H "Content-Type: application/json" \
  -d '[
    {"appId":"demo-app","flagKey":"new-checkout-flow","userId":"user-1001","enabled":true,"clientIp":"10.0.0.1","evalCostMs":3},
    {"appId":"demo-app","flagKey":"new-checkout-flow","userId":"user-2002","enabled":false,"clientIp":"10.0.0.2","evalCostMs":7}
  ]' | jq .
```

---

### 3.6 Query Audit Log Drop Count

```bash
curl -s http://localhost:8082/api/v1/ingest/drop-total | jq .
```

Expected response (if no drops):
```json
{
  "code": 0,
  "data": 0
}
```

---

### 3.7 Confirm Data Persisted to ClickHouse

MetricsWorker runs two independent data pipelines internally, each writing to a different ClickHouse table:

| Pipeline | Data Source | Consumption Method | Target Table |
|----------|-------------|--------------------|--------------|
| **Audit Log** | Kafka topic `flag-audit-log` | `@KafkaListener` streaming consumption, near real-time persistence | `flag_audit_log` |
| **Hit Metrics** | Redis high-frequency counters `flag:metrics:*` | `@Scheduled(fixedDelay=10000)` batch scan every 10 seconds → merge → write → clean up Redis keys | `flag_hit_metrics` |

#### Audit Log (Kafka Streaming Consumption → Near Real-Time Persistence)

IngestService writes audit logs to the Kafka topic `flag-audit-log`,
and MetricsWorker's `KafkaAuditConsumer` consumes the stream and writes directly to ClickHouse.
Since this is streaming, audit logs typically persist within seconds without additional waiting:

```bash
docker exec -i dev_clickhouse clickhouse-client --query "
SELECT 
    app_id,
    flag_key,
    user_id,
    enabled,
    client_ip,
    eval_cost_ms,
    recorded_at
FROM flag.flag_audit_log
WHERE app_id = 'demo-app'
ORDER BY recorded_at DESC
FORMAT PrettyCompact
"
```

Expected output:
```
┏──────────┬───────────────────┬──────────┬─────────┬────────────┬──────────────┬─────────────────────────┐
┃ app_id   │ flag_key          │ user_id  │ enabled │ client_ip  │ eval_cost_ms  │ recorded_at             │
┡──────────╇───────────────────╇──────────╇─────────╇────────────╇──────────────╇─────────────────────────┩
│ demo-app │ new-checkout-flow │ user-1001│       1 │ 10.0.0.1   │            3 │ 2026-06-19 00:00:00     │
│ demo-app │ new-checkout-flow │ user-2002│       0 │ 10.0.0.2   │            7 │ 2026-06-19 00:00:00     │
└──────────┴───────────────────┴──────────┴─────────┴────────────┴──────────────┴─────────────────────────┘
```

#### Hit Metrics (Redis High-Frequency Counters → Scheduled Batch Flush)

After the SDK/application calls `/evaluate`, EvalService reports hit counts to IngestService,
and `MetricsChannel` accumulates them into Redis counters via Fire & Forget (`HINCRBY flag:metrics:{appId}:{flagKey} hits N`).
MetricsWorker's `MetricsFlushService` runs every 10 seconds (`FLUSH_INTERVAL_MS=10000`):

1. Scan all `flag:metrics:*` keys
2. Batch read and merge counters
3. Batch INSERT into ClickHouse `flag_hit_metrics` table
4. Clean up Redis keys that have been persisted

Wait at most 10 seconds for the scheduled task to trigger, then query:

```bash
docker exec -i dev_clickhouse clickhouse-client --query "
SELECT 
    app_id,
    flag_key,
    sum(hits) AS total_hits,
    sum(eval_count) AS total_eval_count
FROM flag.flag_hit_metrics
WHERE app_id = 'demo-app'
GROUP BY app_id, flag_key
FORMAT PrettyCompact
"
```

---

### 3.8 Control Plane Operation: Toggle Flag Status

Disable the flag:
```bash
curl -s -X PATCH http://localhost:8080/api/v1/apps/demo-app/flags/new-checkout-flow/enabled \
  -H "Content-Type: application/json" \
  -d '{"enabled": false}' | jq .
```

Wait 2-3 seconds for the change to sync to EvalService via Redis Pub/Sub, then verify:
```bash
curl -s -X POST http://localhost:8081/api/v1/eval/evaluate \
  -H "Content-Type: application/json" \
  -d '{"appId":"demo-app","flagKey":"new-checkout-flow","userId":"user-1001"}' | jq .
```

Expected `enabled: false`.

---

## Step 4: Troubleshooting

| Symptom | Possible Cause | Diagnostic Command |
|---------|---------------|-------------------|
| Container exits immediately after startup | Database connection failure | `docker logs flag-admin-api` |
| `/flags` returns empty | EvalService did not receive Redis notification | `docker logs flag-eval-service` |
| Audit logs not persisting to ClickHouse | Kafka topic not created | `docker exec dev_kafka kafka-topics.sh --bootstrap-server localhost:9092 --create --topic flag-audit-log --partitions 1 --replication-factor 1` |
| Health check fails | Not enough startup time | Increase `start_period` |
| `infra_net` does not exist | External network not created | `docker network create infra_net` |

---

## Step 5: Cleanup

```bash
cd deploy/docker
docker compose down

# If you also need to remove container networks (does not affect middleware)
docker compose down --remove-orphans
```