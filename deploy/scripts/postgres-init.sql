-- ============================================================
-- Feature Management Service - PostgreSQL initialization script
-- ============================================================
-- Normally JPA ddl-auto: update manages table schemas automatically.
-- This script is for manual initialization or database reset.
-- Usage:
--   docker exec -i dev_postgres psql -U postgres -d flag_db < postgres-init.sql
-- ============================================================

-- Create database (if it does not exist)
SELECT 'CREATE DATABASE flag_db'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'flag_db')\gexec

-- Connect to flag_db
\c flag_db

-- ============================================================
-- Application table
-- Corresponds to JPA Entity: AppEntity @Table(name = "flag_app")
-- Uses TIMESTAMPTZ for timezone-safe audit timestamps
-- ============================================================
CREATE TABLE IF NOT EXISTS flag_app (
    id              BIGSERIAL PRIMARY KEY,
    app_id          VARCHAR(64)  NOT NULL,
    app_name        VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    app_type        VARCHAR(32)  NOT NULL DEFAULT 'BACKEND',
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_app_id UNIQUE (app_id)
);

CREATE INDEX IF NOT EXISTS idx_flag_app_app_id ON flag_app(app_id);

-- ============================================================
-- Feature Flag table
-- Corresponds to JPA Entity: FeatureFlagEntity @Table(name = "flag_feature")
-- rule_config uses JSONB for flexible targeting rules
-- Uses TIMESTAMPTZ for timezone-safe audit timestamps
-- ============================================================
CREATE TABLE IF NOT EXISTS flag_feature (
    id              BIGSERIAL PRIMARY KEY,
    app_id          VARCHAR(64)  NOT NULL,
    flag_key        VARCHAR(128) NOT NULL,
    name            VARCHAR(256) NOT NULL,
    description     VARCHAR(1024),
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    rule_config     JSONB,
    version         INTEGER      NOT NULL DEFAULT 0,
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_app_flag_key UNIQUE (app_id, flag_key)
);

CREATE INDEX IF NOT EXISTS idx_flag_feature_app_id ON flag_feature(app_id);
CREATE INDEX IF NOT EXISTS idx_flag_feature_app_flag ON flag_feature(app_id, flag_key);

-- ============================================================
-- Verify table creation results
-- ============================================================
SELECT 'PostgreSQL init complete' AS status,
       table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN ('flag_app', 'flag_feature');
