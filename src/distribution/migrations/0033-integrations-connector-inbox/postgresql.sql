-- InfraNexum migration 0033: durable connector webhook inbox, DLQ and suspension state for PostgreSQL.
CREATE SCHEMA IF NOT EXISTS infranexum_integrations;

CREATE TABLE IF NOT EXISTS infranexum_integrations.connector_inbox (
    delivery_id UUID PRIMARY KEY,
    connector_key VARCHAR(80) NOT NULL,
    external_delivery_id VARCHAR(200) NOT NULL,
    payload_json JSONB NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    received_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    lease_owner VARCHAR(160) NULL,
    lease_until TIMESTAMPTZ NULL,
    processed_at TIMESTAMPTZ NULL,
    last_failure VARCHAR(1024) NULL,
    replay_count INTEGER NOT NULL DEFAULT 0,
    last_replayed_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_inx_conn_inbox_external UNIQUE(connector_key, external_delivery_id),
    CONSTRAINT ck_inx_conn_inbox_id CHECK (
        SUBSTRING(delivery_id::TEXT FROM 15 FOR 1) = '7'
        AND SUBSTRING(delivery_id::TEXT FROM 20 FOR 1) IN ('8','9','a','b')
    ),
    CONSTRAINT ck_inx_conn_key CHECK (connector_key ~ '^[a-z0-9][a-z0-9._-]{2,79}$'),
    CONSTRAINT ck_inx_conn_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_inx_conn_status CHECK (status IN ('PENDING','IN_FLIGHT','PROCESSED','DEAD_LETTER')),
    CONSTRAINT ck_inx_conn_attempts CHECK (attempts >= 0 AND replay_count >= 0),
    CONSTRAINT ck_inx_conn_lease CHECK (
        (status='IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status<>'IN_FLIGHT' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_inx_conn_processed CHECK (
        (status='PROCESSED' AND processed_at IS NOT NULL)
        OR (status<>'PROCESSED' AND processed_at IS NULL)
    ),
    CONSTRAINT ck_inx_conn_replay CHECK (
        (replay_count=0 AND last_replayed_at IS NULL) OR replay_count>0
    )
);

CREATE INDEX IF NOT EXISTS ix_inx_conn_dispatch
    ON infranexum_integrations.connector_inbox(status, available_at, received_at, delivery_id);
CREATE INDEX IF NOT EXISTS ix_inx_conn_dlq
    ON infranexum_integrations.connector_inbox(connector_key, received_at, delivery_id)
    WHERE status='DEAD_LETTER';

CREATE TABLE IF NOT EXISTS infranexum_integrations.connector_runtime_state (
    connector_key VARCHAR(80) PRIMARY KEY,
    consecutive_dead_letters INTEGER NOT NULL DEFAULT 0,
    suspended_until TIMESTAMPTZ NULL,
    last_success_at TIMESTAMPTZ NULL,
    last_failure_at TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_inx_conn_state_key CHECK (connector_key ~ '^[a-z0-9][a-z0-9._-]{2,79}$'),
    CONSTRAINT ck_inx_conn_state_fail CHECK (consecutive_dead_letters >= 0)
);
CREATE INDEX IF NOT EXISTS ix_inx_conn_suspended
    ON infranexum_integrations.connector_runtime_state(suspended_until)
    WHERE suspended_until IS NOT NULL;
