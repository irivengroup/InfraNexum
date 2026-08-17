-- InfraNexum migration 0036: durable outbound notification outbox, DLQ and endpoint suspension for PostgreSQL.
CREATE SCHEMA IF NOT EXISTS infranexum_integrations;

CREATE TABLE IF NOT EXISTS infranexum_integrations.notification_outbox (
    delivery_id UUID PRIMARY KEY,
    endpoint_key VARCHAR(80) NOT NULL,
    event_id VARCHAR(200) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload_json JSONB NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL,
    lease_owner VARCHAR(160) NULL,
    lease_until TIMESTAMPTZ NULL,
    delivered_at TIMESTAMPTZ NULL,
    last_failure VARCHAR(64) NULL,
    replay_count INTEGER NOT NULL DEFAULT 0,
    last_replayed_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_inx_notif_event UNIQUE(endpoint_key, event_id),
    CONSTRAINT ck_inx_notif_id CHECK (
        SUBSTRING(delivery_id::TEXT FROM 15 FOR 1) = '7'
        AND SUBSTRING(delivery_id::TEXT FROM 20 FOR 1) IN ('8','9','a','b')
    ),
    CONSTRAINT ck_inx_notif_key CHECK (endpoint_key ~ '^[a-z0-9][a-z0-9._-]{2,79}$'),
    CONSTRAINT ck_inx_notif_event_id CHECK (event_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,199}$'),
    CONSTRAINT ck_inx_notif_event_type CHECK (event_type ~ '^[a-z][a-z0-9]*(\.[a-z0-9]+|_[a-z0-9]+|-[a-z0-9]+){1,15}$'),
    CONSTRAINT ck_inx_notif_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_inx_notif_status CHECK (status IN ('PENDING','IN_FLIGHT','DELIVERED','DEAD_LETTER')),
    CONSTRAINT ck_inx_notif_count CHECK (attempts >= 0 AND replay_count >= 0),
    CONSTRAINT ck_inx_notif_lease CHECK (
        (status='IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status<>'IN_FLIGHT' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_inx_notif_done CHECK (
        (status='DELIVERED' AND delivered_at IS NOT NULL)
        OR (status<>'DELIVERED' AND delivered_at IS NULL)
    ),
    CONSTRAINT ck_inx_notif_replay CHECK ((replay_count=0 AND last_replayed_at IS NULL) OR replay_count>0)
);

CREATE INDEX IF NOT EXISTS ix_inx_notif_dispatch
    ON infranexum_integrations.notification_outbox(status, available_at, created_at, delivery_id);
CREATE INDEX IF NOT EXISTS ix_inx_notif_dlq
    ON infranexum_integrations.notification_outbox(endpoint_key, created_at, delivery_id)
    WHERE status='DEAD_LETTER';

CREATE TABLE IF NOT EXISTS infranexum_integrations.notification_endpoint_state (
    endpoint_key VARCHAR(80) PRIMARY KEY,
    consecutive_dead_letters INTEGER NOT NULL DEFAULT 0,
    suspended_until TIMESTAMPTZ NULL,
    last_success_at TIMESTAMPTZ NULL,
    last_failure_at TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_inx_notif_state_key CHECK (endpoint_key ~ '^[a-z0-9][a-z0-9._-]{2,79}$'),
    CONSTRAINT ck_inx_notif_state_fail CHECK (consecutive_dead_letters >= 0)
);
CREATE INDEX IF NOT EXISTS ix_inx_notif_suspended
    ON infranexum_integrations.notification_endpoint_state(suspended_until)
    WHERE suspended_until IS NOT NULL;
