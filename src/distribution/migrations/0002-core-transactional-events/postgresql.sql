-- InfraNexum migration 0002: Core-local transactional outbox and inbox for PostgreSQL.
CREATE SCHEMA IF NOT EXISTS infranexum_core;

CREATE TABLE IF NOT EXISTS infranexum_core.outbox_event (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(200) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    event_source VARCHAR(255) NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID NULL,
    payload_json JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL,
    lease_owner VARCHAR(160) NULL,
    lease_until TIMESTAMPTZ NULL,
    published_at TIMESTAMPTZ NULL,
    last_failure VARCHAR(1024) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_inx_outbox_event_id CHECK (
        SUBSTRING(event_id::TEXT FROM 15 FOR 1) = '7'
        AND SUBSTRING(event_id::TEXT FROM 20 FOR 1) IN ('8', '9', 'a', 'b')
    ),
    CONSTRAINT ck_inx_outbox_correlation_id CHECK (
        SUBSTRING(correlation_id::TEXT FROM 15 FOR 1) = '7'
        AND SUBSTRING(correlation_id::TEXT FROM 20 FOR 1) IN ('8', '9', 'a', 'b')
    ),
    CONSTRAINT ck_inx_outbox_causation_id CHECK (
        causation_id IS NULL OR (
            SUBSTRING(causation_id::TEXT FROM 15 FOR 1) = '7'
            AND SUBSTRING(causation_id::TEXT FROM 20 FOR 1) IN ('8', '9', 'a', 'b')
        )
    ),
    CONSTRAINT ck_inx_outbox_event_type CHECK (
        event_type ~ '^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*){2,7}\.v[1-9][0-9]*$'
    ),
    CONSTRAINT ck_inx_outbox_schema_ver CHECK (
        schema_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
    ),
    CONSTRAINT ck_inx_outbox_source CHECK (LENGTH(BTRIM(event_source)) > 0),
    CONSTRAINT ck_inx_outbox_status CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD_LETTER')),
    CONSTRAINT ck_inx_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_inx_outbox_lease CHECK (
        (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'IN_FLIGHT' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_inx_outbox_published CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS ix_inx_outbox_dispatch
    ON infranexum_core.outbox_event (status, available_at, occurred_at, event_id);

CREATE INDEX IF NOT EXISTS ix_inx_outbox_lease
    ON infranexum_core.outbox_event (lease_until)
    WHERE status = 'IN_FLIGHT';

CREATE TABLE IF NOT EXISTS infranexum_core.inbox_receipt (
    consumer_name VARCHAR(160) NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_inx_inbox_receipt PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT ck_inx_inbox_event_id CHECK (
        SUBSTRING(event_id::TEXT FROM 15 FOR 1) = '7'
        AND SUBSTRING(event_id::TEXT FROM 20 FOR 1) IN ('8', '9', 'a', 'b')
    ),
    CONSTRAINT ck_inx_inbox_event_type CHECK (
        event_type ~ '^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*){2,7}\.v[1-9][0-9]*$'
    ),
    CONSTRAINT ck_inx_inbox_completed CHECK (completed_at >= received_at),
    CONSTRAINT ck_inx_inbox_sha256 CHECK (payload_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX IF NOT EXISTS ix_inx_inbox_completed
    ON infranexum_core.inbox_receipt (completed_at, consumer_name);
