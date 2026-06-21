graph TB
    subgraph Client_Side [Client Layer / SDK - Application Isolation]
        direction LR
        HeavySDK_A[Backend Heavy SDK <br> App A Pods * 500]
        HeavySDK_B[Backend Heavy SDK <br> App B Pods * 500]
        AppSDK[Mobile Light SDK <br> End-User App <br> With App_ID Isolation]
        WebSDK[Web Light SDK <br> Browser Local Evaluation <br> With App_ID Isolation]
    end

    subgraph Traffic_Ingress [Edge and Gateway Layer]
        direction TB
        LB[K8s Ingress / Load Balancer <br> Dynamic Route Distribution]
        CDN[CDN / Edge Nodes <br> Cache Full Rule Snapshot JSON per App <br> Supports Purge API]
        PushServer[Unified Push Center <br> Supports Payload with Incremental Rules]
    end

    subgraph Control_Plane [Control Plane - Low Concurrency / Strong Consistency]
        direction TB
        AdminUI[Admin UI]
        AdminAPI[Admin API Service]
        DB[(PostgreSQL <br> Stores Base Rule Metadata)]
    end

    subgraph Data_Plane [Data Plane - Fully Purified Responsibilities / In-Process Channel Isolation]
        direction TB
        EvalService[EvalService <br> Exposes Only /evaluate <br> Pure In-Memory Computation / Stateless Cluster]

        subgraph IngestService_Detail [IngestService Internal Decoupling]
            MetricsChannel[1. Metrics Async Channel <br> Pool A: Fire & Forget]
            LogChannel[2. Audit Log Channel <br> Pool B: With Timeout and Degraded Discard]
        end

        Redis[(Redis <br> 1. High-Frequency In-Memory Metrics Counting <br> 2. Pub/Sub Change Notification)]
    end

    subgraph Async_Log_Metrics [Async Big Data Pipeline / Final Persistence]
        direction TB
        KafkaStream[[Kafka Message Queue <br> Audit Log Async Buffer]]
        MetricsWorker[Metrics Worker Service <br> Async Metrics Configurable Persistence]
        CH[(ClickHouse <br> 1. Hit Metrics Statistics Table <br> 2. Evaluation Audit Log Table)]
    end

    %% 1. Control Plane Write and Local Notification
    AdminUI --> AdminAPI
    AdminAPI -->|2. Write Rules| DB
    AdminAPI -->|2. Publish Change Notification| Redis

    %% 2. Backend Microservice Sync (Precise Distribution by App_ID)
    Redis -.->|3. Listen for Notification and Full Pull from DB| EvalService
    EvalService -.->|4a. SSE Long Connection: Push Only App A Changes| HeavySDK_A
    EvalService -.->|4b. SSE Long Connection: Push Only App B Changes| HeavySDK_B

    %% 3. Mobile Seconds-Level Sync (with Payload Optimization)
    AdminAPI -->|5a. Push with Incremental Rules| PushServer
    PushServer -.->|5b. Carry Data to Directly Update Local Memory| AppSDK
    AppSDK -.->|5c. Bypass Cache Fallback on Exception| LB

    %% 4. Web Sync (CDN Caches Full Rule Snapshot, Delivered for Local Evaluation)
    AdminAPI -->|6a. Purge Specified App Cache Snapshot| CDN
    WebSDK -->|6b. Fetch Full Rule Snapshot for Own App_ID| CDN
    CDN -->|6c. Cache Miss Origin Fetch| LB
    LB --> EvalService

    %% 5. Ultra-Purified North-South / East-West Data Reporting Pipeline
    HeavySDK_A & HeavySDK_B -->|7a. 10s Local Aggregated Metrics Report| LB
    AppSDK -->|7b. Client Metrics and Audit Report| LB
    WebSDK -->|7c. Client Metrics and Audit Report| LB

    %% All Traffic Split
    LB -->|Route Distribution: Compute Requests Only /evaluate| EvalService
    LB -->|Route Distribution: Reporting Traffic Merge Entry| IngestService_Detail

    %% IngestService Internal Dual-Channel Split
    MetricsChannel -->|8. HINCRBY In-Memory Counting| Redis
    LogChannel -.->|11. Fast Write to Buffer <br> Auto Discard on Timeout, Counted as ingest_drop_total| KafkaStream

    %% Async Persistence Final Destination: ClickHouse
    Redis -.->|9. Batch Pull Metrics| MetricsWorker
    MetricsWorker -->|10. Batch Write Metrics| CH
    KafkaStream -.->|12. Stream Clean and Persist| CH

    style Control_Plane fill:#f9f,stroke:#333,stroke-width:2px
    style Data_Plane fill:#bbf,stroke:#333,stroke-width:2px
    style Client_Side fill:#dfd,stroke:#333,stroke-width:2px
    style Traffic_Ingress fill:#fffbbf,stroke:#333,stroke-width:2px
    style Async_Log_Metrics fill:#fdd,stroke:#333,stroke-width:2px
    style IngestService_Detail fill:#fff,stroke:#333,stroke-width:1px,stroke-dasharray: 5 5