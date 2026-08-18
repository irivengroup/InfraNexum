-- InfraNexum migration 0038: durable connector synchronization runs and checkpoints for PostgreSQL.
CREATE SCHEMA IF NOT EXISTS infranexum_integrations;

CREATE TABLE IF NOT EXISTS infranexum_integrations.connector_sync_state (
    connector_key VARCHAR(80) PRIMARY KEY,
    current_revision BIGINT NOT NULL DEFAULT 0,
    cursor_value VARCHAR(2048) NULL,
    cursor_sha256 CHAR(64) NOT NULL,
    active_run_id UUID NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_inx_sync_state_key CHECK (connector_key ~ '^[a-z0-9][a-z0-9._-]{2,79}$'),
    CONSTRAINT ck_inx_sync_state_rev CHECK (current_revision >= 0),
    CONSTRAINT ck_inx_sync_state_hash CHECK (cursor_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE IF NOT EXISTS infranexum_integrations.connector_sync_run (
    run_id UUID PRIMARY KEY,
    connector_key VARCHAR(80) NOT NULL,
    provider VARCHAR(80) NOT NULL,
    direction VARCHAR(20) NOT NULL,
    rollback_strategy VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    requested_fields VARCHAR(4096) NOT NULL,
    propagate_deletions BOOLEAN NOT NULL,
    max_batches INTEGER NOT NULL,
    initial_revision BIGINT NOT NULL,
    last_checkpoint_revision BIGINT NOT NULL,
    failure_code VARCHAR(64) NULL,
    actor_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NULL,
    compensation_checkpoint_revision BIGINT NULL,
    CONSTRAINT uq_inx_sync_run_idem UNIQUE(connector_key,idempotency_key),
    CONSTRAINT ck_inx_sync_run_id CHECK (SUBSTRING(run_id::TEXT FROM 15 FOR 1)='7' AND SUBSTRING(run_id::TEXT FROM 20 FOR 1) IN ('8','9','a','b')),
    CONSTRAINT ck_inx_sync_run_key CHECK (connector_key ~ '^[a-z0-9][a-z0-9._-]{2,79}$'),
    CONSTRAINT ck_inx_sync_provider CHECK (provider ~ '^[a-z][a-z0-9-]{0,79}$'),
    CONSTRAINT ck_inx_sync_direction CHECK (direction IN ('INBOUND','OUTBOUND','BIDIRECTIONAL')),
    CONSTRAINT ck_inx_sync_rollback CHECK (rollback_strategy IN ('LOCAL_CHECKPOINT','REMOTE_COMPENSATION','DUAL_COMPENSATION','MANUAL')),
    CONSTRAINT ck_inx_sync_status CHECK (status IN ('RUNNING','PAUSED','SUCCEEDED','FAILED','COMPENSATING','COMPENSATED','COMPENSATION_FAILED')),
    CONSTRAINT ck_inx_sync_idem CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{8,200}$'),
    CONSTRAINT ck_inx_sync_req_hash CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_inx_sync_batches CHECK (max_batches BETWEEN 1 AND 100),
    CONSTRAINT ck_inx_sync_revisions CHECK (initial_revision>=0 AND last_checkpoint_revision>=initial_revision),
    CONSTRAINT ck_inx_sync_failure CHECK (failure_code IS NULL OR failure_code ~ '^[A-Z0-9_:-]{1,64}$')
);
CREATE INDEX IF NOT EXISTS ix_inx_sync_run_connector ON infranexum_integrations.connector_sync_run(connector_key,started_at DESC,run_id);
CREATE INDEX IF NOT EXISTS ix_inx_sync_run_status ON infranexum_integrations.connector_sync_run(status,updated_at,run_id);

CREATE TABLE IF NOT EXISTS infranexum_integrations.connector_sync_checkpoint (
    checkpoint_id UUID PRIMARY KEY,
    connector_key VARCHAR(80) NOT NULL,
    run_id UUID NOT NULL REFERENCES infranexum_integrations.connector_sync_run(run_id),
    revision BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    cursor_value VARCHAR(2048) NULL,
    cursor_sha256 CHAR(64) NOT NULL,
    processed_count BIGINT NOT NULL,
    changed_count BIGINT NOT NULL,
    rejected_count BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_inx_sync_checkpoint_revision UNIQUE(connector_key,revision),
    CONSTRAINT ck_inx_sync_checkpoint_id CHECK (SUBSTRING(checkpoint_id::TEXT FROM 15 FOR 1)='7' AND SUBSTRING(checkpoint_id::TEXT FROM 20 FOR 1) IN ('8','9','a','b')),
    CONSTRAINT ck_inx_sync_checkpoint_key CHECK (connector_key ~ '^[a-z0-9][a-z0-9._-]{2,79}$'),
    CONSTRAINT ck_inx_sync_checkpoint_rev CHECK (revision >= 1),
    CONSTRAINT ck_inx_sync_checkpoint_kind CHECK (kind IN ('PROGRESS','COMPENSATION')),
    CONSTRAINT ck_inx_sync_checkpoint_hash CHECK (cursor_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_inx_sync_checkpoint_counts CHECK (processed_count>=0 AND changed_count>=0 AND rejected_count>=0 AND changed_count+rejected_count<=processed_count)
);
CREATE INDEX IF NOT EXISTS ix_inx_sync_checkpoint_run ON infranexum_integrations.connector_sync_checkpoint(run_id,revision);
