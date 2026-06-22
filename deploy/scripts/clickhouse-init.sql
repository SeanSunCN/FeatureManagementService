-- ============================================================
-- ClickHouse table creation statements
-- Architecture: ClickHouse stores hit metric statistics + evaluation audit trail
--
-- Usage: Execute via ClickHouse client, or automatically executed by WorkerApplication on startup.
-- ============================================================

-- Create database (if not exists)
CREATE DATABASE IF NOT EXISTS flag;

-- ============================================================
-- 1. Hit metrics table
--    Periodically flushed from Redis by MetricsWorker
-- ============================================================
CREATE TABLE IF NOT EXISTS flag.flag_hit_metrics
(
    app_id      String,
    flag_key    String,
    hits        UInt64,
    eval_count  UInt64,
    recorded_at DateTime('UTC')
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(recorded_at)
ORDER BY (app_id, flag_key, recorded_at)
TTL recorded_at + INTERVAL 90 DAY DELETE;

-- ============================================================
-- 2. Audit trail table
--    Architecture: Kafka -> Kafka Engine -> MV (TO) -> MergeTree
--    Zero JVM overhead: ClickHouse C++ natively parses JSON from Kafka
-- ============================================================
CREATE TABLE IF NOT EXISTS flag.flag_audit_log
(
    app_id             String,
    flag_key           String,
    user_id            String,
    enabled            UInt8,
    matched_rule       String,
    client_ip          String,
    attributes_snapshot String,
    eval_cost_ns       UInt64,
    recorded_at        DateTime('UTC')
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(recorded_at)
ORDER BY (app_id, flag_key, recorded_at)
TTL recorded_at + INTERVAL 90 DAY DELETE;

-- ============================================================
-- 3. Kafka engine pipeline table (raw JSON ingestion)
--    kafka_format='JSONAsString': ClickHouse C++ parses the JSON,
--    zero object allocation in JVM heap.
-- ============================================================
CREATE TABLE IF NOT EXISTS flag.kafka_flag_audit_log_queue
(
    message String
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list = 'flag-kafka:9092',
    kafka_topic_list = 'flag-audit-log',
    kafka_group_name = 'clickhouse-audit-log-consumer',
    kafka_format = 'JSONAsString',
    kafka_num_consumers = 3;

-- ============================================================
-- 4. Materialized view: auto-parse and push to physical table
--    TO keyword ensures direct streaming to flag_audit_log
-- ============================================================
CREATE MATERIALIZED VIEW flag.mv_kafka_to_flag_audit_log
TO flag.flag_audit_log
AS SELECT
    JSONExtractString(message, 'appId')              AS app_id,
    JSONExtractString(message, 'flagKey')            AS flag_key,
    JSONExtractString(message, 'userId')             AS user_id,
    JSONExtractUInt(message, 'enabled')              AS enabled,
    JSONExtractString(message, 'matchedRule')        AS matched_rule,
    JSONExtractString(message, 'clientIp')           AS client_ip,
    JSONExtractString(message, 'attributesSnapshot') AS attributes_snapshot,
    JSONExtractUInt(message, 'evalCostNs')            AS eval_cost_ns,
    toDateTime(JSONExtractUInt(message, 'timestamp') / 1000) AS recorded_at
FROM flag.kafka_flag_audit_log_queue;

-- ============================================================
-- Verify table creation
-- ============================================================
SELECT 'ClickHouse init complete' AS status,
       database,
       name,
       engine
FROM system.tables
WHERE database = 'flag';
