-- InfraNexum migration 0001: Core schema history for PostgreSQL.
CREATE SCHEMA IF NOT EXISTS infranexum_core;

CREATE TABLE IF NOT EXISTS infranexum_core.schema_history (
    migration_id VARCHAR(32) PRIMARY KEY,
    logical_checksum CHAR(64) NOT NULL,
    application_version VARCHAR(64) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_by VARCHAR(255) NOT NULL
);
