# Feature Management Service

Cloud-native feature flag management system with control/data plane separation, CDN distribution, and dual-channel metrics/audit ingestion.

## Architecture Overview

```
┌──────────────────────────────────┐       ┌─────────────────────────────┐
│ [heavy-client]                   │       │ [web-sdk]                   │
│ Backend Microservices            │       │Frontend Browser             │
│ SSE Local Cache Eval             │       │Static File Pull             │
│ Send to Ingest Service           │       │Send to Ingest Service       │
└──────┬───────────────────────────┘       └─────────────────────────────┘
       │                                             │
       │ (SSE Stream)                                │ (HTTP GET rules)
       │                           ┌─────────────────▼───────────────────┐
       │                           │ CDN Cache Layer (Nginx - Port 8084) │
       │                           │  Serves rules.<ver>.json            │
       │                           │  Cache Busting via manifest.json    │
       │                           └──────────────────▲──────────────────┘
       │                                              │
       │                                              │ (Write Snapshots)
┌──────▼───────────────────────────┐       ┌──────────┴──────────────────┐
│ Data Plane                       │       │ Control Plane               │
│  [flag-eval-service]             │       │  [admin-api]                │
│                                  │◄─────►│                             │
│  Pure In-Memory Evaluation       │[redis]│  CRUD Flags/Apps            │
│  SSE Long Connection Push        │Pub/Sub│  [postgres] Database        │
│  Rule Engine                     │       │                             │
└──────────────────────────────────┘       └─────────────────────────────┘


       
┌────────────────────────────────────────────────────────────────────────┐
│ [ingest-service] (Data Ingestion Layer)                                │
│ Data From Client & SDK                                                 │
│  - Pool A: Fire & Forget ──► [redis] High-Frequency Counter            │
│  - Pool B: Timeout Degradation ──► [kafka] Audit Log                   │
└──────┬─────────────────────────────────────────────┬───────────────────┘
       │                                             │
       │ (Periodic Flush)                            │ (Stream Consume)
       ▼                                             ▼
┌──────────────────────────────────┐       ┌─────────────────────────────┐
│ [metrics-worker]                 │       │ Kafka Consumer Engine       │
│  - Reads from Redis Counter      │       │  - Reads from Kafka Stream  │
└──────┬───────────────────────────┘       └─────────┬───────────────────┘
       │                                             │
       │ (Write Metrics Data)                        │ (Write Audit Logs)
       └─────────────────────┬───────────────────────┘
                             ▼
┌────────────────────────────────────────────────────────────────────────┐
│ OLAP Storage Layer [clickhouse]                                        │
│  - Hit Metrics Table (From metrics-worker Aggregated Telemetry)        │
│  - Audit Log Table   (From Kafka Stream Granular Trails)               │
└────────────────────────────▲───────────────────────────────────────────┘
                             │ (Query Analytics / Scrape Metrics)
┌────────────────────────────┴───────────────────────────────────────────┐
│ Observability Stack                                                    │
│  - [prometheus] : Pulls time-series metrics from core services         │
│  - [grafana]    : Dashboards pulling from Prometheus & ClickHouse      │
└────────────────────────────────────────────────────────────────────────┘
```

## Project Structure

```
feature-management-service/
├── pom.xml                              # Maven Parent POM (multi-module)
├── .gitignore
│
├── flag-common/                         # [Shared Library]
│   ├── dto/                             # FlagChangeMessage, EvaluateRequest/Response
│   ├── enums/                           # FlagStatus, TargetMatchType
│   ├── exception/                       # BusinessException, ErrorCode, GlobalExceptionHandler
│   ├── model/                           # FlagConfig, EvaluationRule, Condition
│   ├── response/                        # UnifiedResponse, PageResult
│   └── util/                            # RuleConfigParser, RuleConfigValidator
│
├── flag-sdk-api/                        # [Pure Interface] FlagClient Interface + DTOs
│                                        #   2.5 KB · Zero External Dependencies
├── flag-sdk-light-client/               # [Lightweight] Java 21+ HttpClient
│   └── LightFlagClient.java            #   8.5 KB · Daemon Thread Batching · No Third-Party Libs
├── flag-sdk-heavy-client/               # [Heavy] SSE + Local Cache + WebFlux
│   ├── HeavyFlagClient.java            #   18 KB · WebClient · Read/Write Lock
│   ├── RemoteEvaluator / SseStreamMgr  # SSE subscription + local evaluation
│   └── HeavyMetricsAggregator          # Async metrics batching
│
├── flag-admin-api/                      # [Control Plane]
│   ├── controller/                      # REST endpoints: App CRUD, Flag CRUD
│   ├── service/                         # FeatureFlagService, AppService
│   ├── cdn/                             # <<CDN PUBLISH ENGINE>>
│   │   ├── CdnSnapshotService.java      # SQL filter → RuleCompiler pipeline
│   │   ├── RuleCompiler.java            # Atomic file write: rules.<ver>.json + manifest
│   │   └── ClientFlagMetadata.java      # Client-safe flag DTO
│   ├── entity/                          # JPA entities (FeatureFlagEntity, AppEntity, FlagOutbox)
│   ├── repository/                      # Spring Data JPA repositories
│   ├── publisher/                       # FlagChangePublisher (Redis outbox serialization)
│   └── config/                          # RedisConfig
│
├── flag-eval-service/                   # [Data Plane — Evaluation]
│   ├── cache/                           # FlagCache (ConcurrentHashMap, per-app snapshots)
│   ├── engine/                          # EvaluationEngine (rule matching, condition eval)
│   ├── listener/                        # FlagChangeListener, FlagDbLoader
│   ├── sse/                             # SseController (SSE long-connection push per app)
│   └── config/                          # EvalServiceConfig (startup DB load)
│
├── flag-ingest-service/                 # [Data Plane — Ingestion]
│   ├── channel/                         # Dual-channel: MetricsChannel, AuditLogChannel
│   └── config/                          # ThreadPool configs (isolated pools)
│
├── flag-metrics-worker/                 # [Async Worker]
│   └── service/                         # MetricsFlushService (Redis→ClickHouse batch flush)
│
├── flag-engine/                         # [Pure Engine — no Spring dep]
│   └── RuleEngine.java                  # Stateless rule matching
│
├── feature-flag-web-cdn/                # [Web SDK + CDN Static Files]
│   ├── cdn_root/                        # Versioned rules JSON + manifest (written by admin-api)
│   │   ├── rules.<ver>.json             # Client-safe flag rules (auto-generated)
│   │   └── manifest.json               # Latest file pointer (auto-generated)
│   ├── web-sdk/                         # Browser JS SDK
│   └── Nginx config                     # Caching headers, manifest caching rules
│
└── deploy/
    ├── docker/
    │   ├── docker-compose.yml            # Full-stack deployment (all services + middleware + CDN)
    │   ├── Dockerfile.admin-api          # Boot JAR
    │   ├── Dockerfile.eval-service       # Boot JAR
    │   ├── Dockerfile.ingest-service     # Boot JAR
    │   ├── Dockerfile.metrics-worker     # Boot JAR
    │   ├── nginx.conf                    # CDN Nginx config (cache headers)
    │   ├── prometheus/                   # Prometheus config
    │   └── grafana/                      # Grafana datasource + dashboard provisioning
    ├── scripts/
    │   ├── integration-test.py           # End-to-end Python integration test
    │   ├── deploy.sh                     # Server-side deploy script
    │   ├── health-check.sh               # Quick health check
    │   ├── env-check.sh                  # Environment prerequisite check
    │   └── clean-e2e.sh                  # Clean up test data
    ├── k8s/
    │   ├── namespace.yaml
    │   ├── configmap.yaml
    │   ├── *-deployment.yaml             # 4 Microservice Deployments + NodePort Services
    │   ├── infra-bridge.yaml             # Headless Service → NAS Middleware
    │   ├── ingress.yaml
    │   └── deploy-all.sh                 # One-click K8s deploy
    └── scripts/
        └── postgres-init.sql            # PostgreSQL schema init
```

## CDN Rule Distribution Pipeline

```
Flag mutation (create/update/delete)
       │
       ▼
FeatureFlagService (flag-admin-api)
       │
       ├─► Save to PostgreSQL (transactional outbox → Redis Pub/Sub → EvalService refresh)
       │
       └─► CdnSnapshotService.publishAllSafeFlags()
                │
                ├─1─► SQL: SELECT * FROM flag_feature WHERE safe_for_client = TRUE
                │        (physical isolation — server-only flags never leave DB)
                │
                ├─2─► RuleCompiler.publishSnapshot(clientFlags)
                │        │
                │        ├─► Write rules.<ver>.json.tmp
                │        ├─► Acquire FileLock on manifest.json (OS-level, multi-instance safe)
                │        ├─► Atomic rename .tmp → rules.<ver>.json
                │        └─► Overwrite manifest.json with new latest_file pointer
                │
                └─3─► CDN Nginx serves rules.<ver>.json via HTTP
                         (Cache-Control: public, immutable, max-age=31536000)
```

## SDK Three-Module Topology

| Module | Size | External Dependencies | Use Case |
|--------|------|----------------------|----------|
| **flag-sdk-api** | 2.5 KB | Zero | Pure interface — any microservice/mobile client |
| **flag-sdk-light-client** | 8.5 KB | sdk-api only | Frontend / mobile / external-facing API |
| **flag-sdk-heavy-client** | 18 KB | WebFlux, Jackson | Embedded in backend microservices |

### LightFlagClient Design Pillars

1. **Synchronous Evaluation + Fallback** — Java 11+ HttpClient calls EvalService, degrades to default false
2. **Async In-Memory Accumulation** — `ConcurrentHashMap<String, AtomicLong>` zero blocking
3. **Daemon Thread Periodic Batching** — `ScheduledExecutorService` (daemon=true), 5s interval
4. **Server-Side Clock** — Report payloads omit timestamps, server injects `now()` to prevent partition explosion
5. **Graceful Shutdown** — `AutoCloseable.close()` → final synchronous flush → shutdown

```java
// Programming to the interface
FlagSdkClient client = new LightFlagClient("my-app",
    "http://localhost:8081", "http://localhost:8082");
boolean enabled = client.isEnabled("my-app", "new-feature", "user-123");
client.close();   // Graceful shutdown — final flush prevents data loss
```

## Quick Start

### Prerequisites

| Service     | Docker Compose | Purpose                |
|-------------|----------------|------------------------|
| PostgreSQL  | Built-in       | Rule metadata storage  |
| Redis       | Built-in       | Pub/Sub + Metric counting |
| Kafka       | Built-in       | Audit log buffering    |
| ClickHouse  | Built-in       | Metrics/audit analytics |

**Build Environment:** JDK 21+, Maven 3.9+, Docker Desktop

### 1. Build All Modules

```bash
mvn clean package -DskipTests -Dmaven.test.skip=true
```

### 2. Full-Stack Docker Deploy

```bash
cd deploy/docker
docker compose -f docker-compose.yml up -d --build
```

This starts all 9 containers: PostgreSQL, Redis, Kafka, ClickHouse, CDN Nginx,
Prometheus, Grafana, and all 4 microservices.

### 3. Integration Test

```bash
python3 deploy/scripts/integration-test.py
# Custom ports:
python3 deploy/scripts/integration-test.py --admin http://admin:8080 --wait 5
```

### 4. Manual Verification

```bash
curl http://localhost:8080/actuator/health   # Admin API
curl http://localhost:8081/actuator/health   # EvalService
curl http://localhost:8082/actuator/health   # IngestService
curl http://localhost:8083/actuator/health   # MetricsWorker
curl http://localhost:8084/__headers         # CDN Nginx
curl http://localhost:8084/manifest.json     # CDN rules manifest
```

## API Reference

### Control Plane — Admin API (Port 8080)

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/v1/apps | List all applications |
| POST | /api/v1/apps | Create an application |
| GET | /api/v1/apps/{appId} | Get a single application |
| PUT | /api/v1/apps/{appId} | Update an application |
| DELETE | /api/v1/apps/{appId} | Delete an application (cascading flag delete) |
| GET | /api/v1/apps/{appId}/flags | List all flags for an application |
| POST | /api/v1/apps/{appId}/flags | Create a feature flag |
| GET | /api/v1/apps/{appId}/flags/{flagKey} | Get a single feature flag |
| PUT | /api/v1/apps/{appId}/flags/{flagKey} | Update a feature flag |
| DELETE | /api/v1/apps/{appId}/flags/{flagKey} | Delete a feature flag |
| PATCH | /api/v1/apps/{appId}/flags/{flagKey}/enabled | Enable/disable a flag |
| POST | /api/v1/apps/{appId}/flags/reload | Trigger full refresh push |

### Data Plane — EvalService (Port 8081)

| Method | Path | Description |
|--------|------|-------------|
| POST | /api/v1/eval/evaluate | Evaluate a single feature flag |
| POST | /api/v1/eval/evaluate/batch | Batch evaluation |
| GET | /api/v1/eval/flags?appId=xxx | Get full rule snapshot |
| GET | /api/v1/eval/sse/subscribe?appId=xxx | SSE long connection for change pushes |

### Data Ingestion — IngestService (Port 8082)

| Method | Path | Description |
|--------|------|-------------|
| POST | /api/v1/ingest/metrics | Report evaluation metrics (Fire & Forget) |
| POST | /api/v1/ingest/audit-log | Report single audit log |
| POST | /api/v1/ingest/audit-log/batch | Batch report audit logs |
| GET | /api/v1/ingest/drop-total | Count of dropped audit logs |

### CDN Cache (Port 8084)

| Method | Path | Description |
|--------|------|-------------|
| GET | /manifest.json | Latest rules file pointer (Cache Busting) |
| GET | /rules.<ver>.json | Versioned client-safe flag rules (1 year cache) |
| GET | /feature-flag-web-sdk.js | Web SDK JS (1 hour cache) |
| GET | /__headers | Debug: echo request headers |
| GET | / | Static assets (html, images — 5 min cache) |

## Core Design Principles

### Control Plane / Data Plane Separation
- **Admin API** (Control Plane) — direct PostgreSQL, transactional outbox → Redis Pub/Sub
- **EvalService** (Data Plane) — pure in-memory, zero DB at runtime, detects changes via Redis
- **CDN Snapshot** — admin-api writes directly to shared volume, Nginx serves statically

### SQL-Level Client Flag Filtering
- Server-only flags (`safe_for_client = FALSE`) are physically excluded at the query level
- They never enter the CDN pipeline — security by architecture, not by convention

### IngestService Dual-Channel Isolation
- **Pool A (Metrics)**: Fire & Forget, never blocks, `CallerRunsPolicy`
- **Pool B (Audit Log)**: 200ms timeout degradation, auto-discard with counting
- Two thread pools fully isolated — blocking one channel does not affect the other

### Atomic CDN File Publish
- Write to temp file → OS-level `FileLock` → atomic rename → overwrite manifest
- Multi-instance safe: concurrent admin nodes lock via `FileChannel.tryLock()`
- Version counter prevents stale read: manifest points to `rules.<N>.json`

### SSE Precise Push Per App
- EvalService maintains independent `Sinks.Many` per AppId
- On rule changes, only pushes changes to connected clients of the corresponding app
- Heartbeat via SSE comment line (protocol-level, no DTO pollution)

### Server-Side Unified Timestamp
- All persistence timestamps (`recorded_at`) server-generated
- DTO layer removes `timestamp`/`reportTimestamp` fields — prevents ClickHouse partition explosion

## Port Map

| Port | Service | Container |
|------|---------|-----------|
| 8080 | Admin API | flag-admin-api |
| 8081 | EvalService | flag-eval-service |
| 8082 | IngestService | flag-ingest-service |
| 8083 | MetricsWorker | flag-metrics-worker |
| 8084 | CDN Nginx | flag-cdn |
| 9090 | Prometheus | flag-prometheus |
| 3000 | Grafana | flag-grafana (mapped to 33000) |

## Environment Variables

| Variable | Default | Service(s) |
|----------|---------|------------|
| DB_HOST | flag-postgres | admin, eval |
| DB_PORT | 5432 | admin, eval |
| DB_PASSWORD | postgres | admin, eval |
| REDIS_HOST | flag-redis | all |
| REDIS_PORT | 6379 | all |
| KAFKA_HOST | flag-kafka | ingest, worker |
| KAFKA_PORT | 9092 | ingest, worker |
| CLICKHOUSE_HOST | flag-clickhouse | worker |
| CLICKHOUSE_PORT | 8123 | worker |
| CLICKHOUSE_PASSWORD | dev123 | worker |
| CDN_ROOT | /cdn_root | admin-api |
| LOG_LEVEL | INFO | all |
