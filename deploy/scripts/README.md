# Infrastructure Scripts

This directory contains infrastructure-related scripts for Feature Management Service, used for database initialization, environment checks, and health monitoring.

## Script List

| File | Purpose | Usage |
|------|---------|-------|
| `clickhouse-init.sql` | ClickHouse table creation (hit metrics + audit trail) | `docker exec -i dev_clickhouse clickhouse-client < clickhouse-init.sql` |
| `postgres-init.sql` | PostgreSQL table creation (flag_app + flag_feature) | `docker exec -i dev_postgres psql -U postgres -d flag_db < postgres-init.sql` |
| `env-check.sh` | Check whether middleware (PostgreSQL/Redis/Kafka/ClickHouse) is ready | `bash env-check.sh` |
| `health-check.sh` | Health check for all microservice instances | `bash health-check.sh` |

> **Note**: JPA's `ddl-auto: update` mode manages PostgreSQL table structures automatically. `postgres-init.sql` is only needed when manual initialization or reset is required.