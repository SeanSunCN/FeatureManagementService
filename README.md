# Feature Management Service

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│ Client Layer (SDK)                                          │
│  Heavy SDK (Backend Microservices · SSE Long Connection ·   │
│            Local Cache Evaluation)                          │
│  Light SDK (Mobile/Web · HTTP Blind Query · In-Memory       │
│            Counting & Batching)                             │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│ Edge & Gateway Layer                                        │
│  K8s Ingress / NodePort · CDN Cache · Unified Push Center  │
└──────┬───────────────────────────────────────────┬──────────┘
       │                                          │
┌──────▼──────────┐                  ┌────────────▼──────────┐
│ Control Plane   │                  │ Data Plane             │
│ (Admin)         │                  │ (EvalService)          │
│ Admin API       │◄────Redis──────►│ Pure In-Memory         │
│ PostgreSQL      │   Pub/Sub       │ Evaluation Engine      │
└─────────────────┘                 │ SSE Long Connection    │
                                    │ Push                   │
                                    └────────────┬────────────┘
                                                  │
┌─────────────────────────────────────────────────▼────────────┐
│ IngestService (Data Ingestion)                               │
│  Pool A: Fire & Forget → Redis High-Frequency Counter        │
│  Pool B: Timeout Degradation → Kafka Audit Log               │
└──────────────┬──────────────────────────────────┬────────────┘
               │                                  │
┌──────────────▼──────────┐         ┌─────────────▼──────────┐
│  MetricsWorker           │         │  Kafka → ClickHouse     │
│  Redis Periodic Flush    │         │  Audit Log Table        │
│  Hit Metrics Table       │         │                         │
└─────────────────────────┘         └─────────────────────────┘
```

## Project Structure

```
feature-management-service/
├── pom.xml                              # Maven Parent POM
├── flag-common/                         # Shared DTOs, Unified Responses, Exceptions, Constants
│
├── flag-sdk-api/                        # [Pure Interface Layer] FlagClient Interface + DTOs
│                                        #   2.5 KB · Zero External Dependencies
├── flag-sdk-light-client/               # [Lightweight Implementation] Java 11+ HttpClient
│   └── LightFlagClient.java             #   8.5 KB · Daemon Thread Batching · No Third-Party Libs
├── flag-sdk-heavy-client/               # [Heavy Implementation] SSE + Local Cache + WebFlux
│   └── HeavyFlagClient.java             #   18 KB · WebClient · Read/Write Lock
│
├── flag-admin-api/                      # Control Plane: Application/Rule CRUD + Redis Pub/Sub Notifications
├── flag-eval-service/                   # Data Plane: Pure In-Memory Evaluation Engine + SSE Push
├── flag-ingest-service/                 # Data Plane: Dual-Channel Isolated Metrics/Audit Ingestion
├── flag-metrics-worker/                 # Async Worker: Redis/ClickHouse Persistence
│
└── deploy/
    ├── docker/
    │   ├── docker-compose.yml            # Microservice Orchestration (connects to existing middleware network)
    │   ├── Dockerfile.*                  # 4 Microservice Dockerfiles
    │   └── docker-compose.nas.yml        # NAS Local Deployment Configuration
    └── k8s/
        ├── namespace.yaml
        ├── configmap.yaml               # Infrastructure Connection Config (ExternalName Bridging NAS)
        ├── infra-bridge.yaml            # Headless Service + Endpoints → NAS Middleware
        ├── deploy-all.sh                # One-Click Deployment Script
        ├── *-deployment.yaml            # 4 Microservice Deployments + NodePort Services
        └── ingress.yaml                 # Ingress-Nginx Configuration (Optional)
```

## SDK Three-Module Topology

| Module | Size | External Dependencies | Use Case |
|--------|------|----------------------|----------|
| **flag-sdk-api** | 2.5 KB | Zero | Pure interface — any microservice/mobile client |
| **flag-sdk-light-client** | 8.5 KB | sdk-api only | Frontend / mobile / external-facing API |
| **flag-sdk-heavy-client** | 18 KB | WebFlux, Jackson | Embedded in backend microservices |

### LightFlagClient Design Pillars

1. **Synchronous Evaluation + Fallback** — Java 11+ HttpClient calls EvalService, `try-catch` degrades to default false
2. **Async In-Memory Accumulation** — `ConcurrentHashMap<String, AtomicLong>` atomic accumulation, zero blocking on main thread
3. **Daemon Thread Periodic Batching** — `ScheduledExecutorService` (daemon=true), fetches snapshot every 5s via `getAndSet(0)` and reports
4. **Server-Side Clock to Prevent Partition Explosion** — Report payloads must not carry a timestamp, forcing the server to inject now()
5. **Graceful Shutdown** — `AutoCloseable.close()` → one final synchronous flush → shutdown

```java
// Programming to the interface
FlagSdkClient client = new LightFlagClient("my-app",
    "http://localhost:8081", "http://localhost:8082");

boolean enabled = client.isEnabled("my-app", "new-feature", "user-123");
// Async accumulation → auto-flush to IngestService every 5s
client.close();   // Graceful shutdown — final flush prevents data loss
```

## Prerequisites

### Middleware (Synology NAS 192.168.5.66 — accessed via K8s Headless Service / Docker infra_net)

| Service     | Internal Domain              | Port  | Purpose                |
|-------------|------------------------------|-------|------------------------|
| PostgreSQL  | dev-postgres.flag-system.svc | 15432 | Rule metadata storage  |
| Redis       | dev-redis.flag-system.svc    | 6379  | Pub/Sub + Metric counting |
| Kafka       | dev-kafka.flag-system.svc    | 9092  | Audit log buffering    |
| ClickHouse  | dev-clickhouse.flag-system.svc | 8123 | Metrics/audit analytics storage |

### Build Environment

- JDK 21+
- Maven 3.9+
- Docker Desktop (local development)
- kind + kubectl (local K8s)

## Quick Start

### 1. Build All Modules

```bash
mvn clean package -DskipTests
```

### 2. Local Docker Compose (Docker Desktop)

```bash
cd deploy/docker
docker compose -f docker-compose.yml -f docker-compose.nas-override.yml up -d
```

### 3. Local Kind K8s One-Click Deployment

```bash
# Prerequisite: kind cluster created, images loaded
bash deploy/k8s/deploy-all.sh

# Access (NodePort → localhost)
curl http://localhost:8080/actuator/health   # AdminAPI
curl http://localhost:8081/actuator/health   # EvalService
curl http://localhost:8082/actuator/health   # IngestService
curl http://localhost:8083/actuator/health   # MetricsWorker
```

### 4. NAS Local Docker Deployment (SSH + docker run)

```bash
# Transfer images to NAS via docker save/load
ssh nas 'docker run -d --network infra_net ... docker-flag-admin-api:latest'
# See deploy/scripts/ for details
```

### 5. End-to-End Verification

```bash
# PowerShell integration test script
.\deploy\scripts\integration-test.ps1

# Or manual curl verification (see ENDPOINT_GUIDE.md)
```

## API Reference

### Control Plane — Admin API (Port 8080)

| Method | Path                                          | Description                      |
|--------|-----------------------------------------------|----------------------------------|
| GET    | /api/v1/apps                                  | List all applications            |
| POST   | /api/v1/apps                                  | Create an application            |
| GET    | /api/v1/apps/{appId}                          | Get a single application         |
| PUT    | /api/v1/apps/{appId}                          | Update an application            |
| DELETE | /api/v1/apps/{appId}                          | Delete an application (cascading flag delete) |
| GET    | /api/v1/apps/{appId}/flags                    | List all flags for an application |
| POST   | /api/v1/apps/{appId}/flags                    | Create a feature flag            |
| GET    | /api/v1/apps/{appId}/flags/{flagKey}           | Get a single feature flag        |
| PUT    | /api/v1/apps/{appId}/flags/{flagKey}           | Update a feature flag            |
| DELETE | /api/v1/apps/{appId}/flags/{flagKey}           | Delete a feature flag            |
| PATCH  | /api/v1/apps/{appId}/flags/{flagKey}/enabled   | Enable/disable a flag (Body: `{"enabled":false}`) |
| POST   | /api/v1/apps/{appId}/flags/reload              | Trigger full refresh push        |

### Data Plane — EvalService (Port 8081)

| Method | Path                                         | Description                       |
|--------|----------------------------------------------|-----------------------------------|
| POST   | /api/v1/eval/evaluate                        | Evaluate a single feature flag    |
| POST   | /api/v1/eval/evaluate/batch?appId=xxx        | Batch evaluation                  |
| GET    | /api/v1/eval/flags?appId=xxx                 | Get full rule snapshot (CDN origin) |
| GET    | /api/v1/eval/sse/subscribe?appId=xxx         | SSE long connection subscribe for change pushes |

### Data Ingestion — IngestService (Port 8082)

| Method | Path                           | Description                          |
|--------|---------------------------------|--------------------------------------|
| POST   | /api/v1/ingest/metrics          | Report evaluation metrics (Fire & Forget) |
| POST   | /api/v1/ingest/audit-log        | Report audit log (timeout degradation) |
| POST   | /api/v1/ingest/audit-log/batch  | Batch report audit logs              |
| GET    | /api/v1/ingest/drop-total       | Get total count of dropped audit logs |

## Core Design Principles

### Data Plane / Control Plane Separation
- Control Plane (Admin API) — high latency, strong consistency, directly operates PostgreSQL
- Data Plane (EvalService) — pure in-memory, stateless, detects changes via Redis Pub/Sub
- Runtime evaluation path has zero database calls

### IngestService Dual-Channel Isolation
- **Pool A (Metrics)**: Fire & Forget, never blocks the caller, `CallerRunsPolicy`
- **Pool B (Audit Log)**: With timeout degradation (200ms), auto-discard on timeout with counting
- Two thread pools are fully isolated — blocking on one channel does not affect the other

### MetricsWorker Dual-Pipeline Persistence
- **Audit Log**: Kafka streaming consumption → `KafkaAuditConsumer` → near real-time writes to ClickHouse
- **Hit Metrics**: Redis high-frequency counter → `MetricsFlushService` `@Scheduled(10s)` → batch writes to ClickHouse

### SSE Precise Push Per App
- EvalService maintains independent `Sinks.Many` for each AppId
- On rule changes, only pushes changes to connected clients of the corresponding app
- Keep-alive heartbeat (default 30s)

### Server-Side Unified Timestamp
- All persistence timestamps (`recorded_at`) are forcibly generated server-side
- DTO layer has removed `timestamp`/`reportTimestamp` fields
- Clients cannot tamper with timestamps, preventing ClickHouse partition explosion risk

### K8s Native Service Discovery
- No Nacos/Consul/Eureka introduced
- East-west traffic goes through K8s Service (CoreDNS + IPVS)
- Middleware bridged from NAS via Headless Service + Endpoints

## ClickHouse Table Schema

```sql
-- Hit metrics statistics
CREATE TABLE IF NOT EXISTS flag.flag_hit_metrics (
    app_id      String,
    flag_key    String,
    hits        UInt64,
    eval_count  UInt64,
    recorded_at DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(recorded_at)
ORDER BY (app_id, flag_key, recorded_at);

-- Audit log
CREATE TABLE IF NOT EXISTS flag.flag_audit_log (
    app_id       String,
    flag_key     String,
    user_id      String,
    enabled      UInt8,
    client_ip    String,
    eval_cost_ms UInt32,
    recorded_at  DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(recorded_at)
ORDER BY (app_id, recorded_at, flag_key);
```

## Environment Variable Reference

| Variable               | Default (K8s)            | Default (Docker)           | Description             | Service(s)              |
|------------------------|--------------------------|----------------------------|-------------------------|-------------------------|
| DB_HOST                | dev-postgres.flag-system | 192.168.5.66              | PostgreSQL host          | admin, eval              |
| DB_PORT                | 5432 (K8s DNS)           | 15432 (NAS mapped)        | PostgreSQL port          | admin, eval              |
| DB_USERNAME            | postgres                 | postgres                  | Database username        | admin, eval              |
| DB_PASSWORD            | 666666                   | 666666                    | Database password        | admin, eval              |
| REDIS_HOST             | dev-redis.flag-system    | 192.168.5.66              | Redis host               | all                      |
| REDIS_PORT             | 6379                     | 6379                      | Redis port               | all                      |
| KAFKA_HOST             | dev-kafka.flag-system    | 192.168.5.66              | Kafka host               | ingest, worker           |
| KAFKA_PORT             | 9092                     | 9092                      | Kafka port               | ingest, worker           |
| CLICKHOUSE_HOST        | dev-clickhouse.flag-system | 192.168.5.66            | ClickHouse host          | worker                   |
| CLICKHOUSE_PORT        | 8123                     | 8123                      | ClickHouse port          | worker                   |
| CLICKHOUSE_USERNAME    | dev                      | dev                       | ClickHouse username      | worker                   |
| CLICKHOUSE_PASSWORD    | (in ConfigMap)           | (in YAML)                 | ClickHouse password      | worker                   |
| HOST_IP                | 192.168.5.66             | 192.168.5.66              | External origin address  | all                      |
| LOG_LEVEL              | INFO                     | INFO                      | Log level                | all                      |
