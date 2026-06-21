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
--    Consumed from Kafka topic by MetricsWorker and written to ClickHouse
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
-- Verify table creation
-- ============================================================
SELECT 'ClickHouse init complete' AS status,
       database,
       name,
       engine
FROM system.tables
WHERE database = 'flag';
