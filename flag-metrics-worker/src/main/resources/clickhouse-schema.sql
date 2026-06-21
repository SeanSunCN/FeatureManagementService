-- ============================================================
-- ClickHouse table creation statements
-- Architecture: ClickHouse stores hit metric statistics + evaluation audit trail
--
-- Usage: Execute via ClickHouse client, or automatically executed by WorkerApplication on startup.
-- ============================================================

-- 1. Feature flag hit metrics table
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

-- 2. Evaluation audit trail table
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
