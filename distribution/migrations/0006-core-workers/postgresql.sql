-- InfraNexum migration 0006: durable Core Workers task store for PostgreSQL.
CREATE SCHEMA IF NOT EXISTS infranexum_core;

CREATE TABLE IF NOT EXISTS infranexum_core.worker_task (
    task_id UUID PRIMARY KEY,
    task_type VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    retry_safety VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    requested_not_before TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    lease_owner VARCHAR(160),
    lease_version BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    checkpoint_sequence BIGINT,
    checkpoint_token VARCHAR(4096),
    checkpoint_at TIMESTAMPTZ,
    cancellation_requested CHAR(1) NOT NULL DEFAULT 'N',
    last_failure VARCHAR(1024),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_inx_worker_task_idempotency UNIQUE (task_type, idempotency_key),
    CONSTRAINT ck_inx_worker_task_id_v7 CHECK (
        SUBSTRING(task_id::TEXT FROM 15 FOR 1) = '7'
        AND SUBSTRING(task_id::TEXT FROM 20 FOR 1) IN ('8', '9', 'a', 'b')
    ),
    CONSTRAINT ck_inx_worker_task_type CHECK (
        task_type ~ '^[a-z][a-z0-9]*([.-][a-z0-9]+)*$'
        AND LENGTH(task_type) BETWEEN 1 AND 160
    ),
    CONSTRAINT ck_inx_worker_retry_safety CHECK (retry_safety IN ('RETRY_SAFE', 'AT_MOST_ONCE')),
    CONSTRAINT ck_inx_worker_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_inx_worker_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_inx_worker_lease_version CHECK (lease_version >= 0),
    CONSTRAINT ck_inx_worker_lease_state CHECK (
        (status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL AND lease_version >= 1)
        OR
        (status <> 'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_inx_worker_checkpoint CHECK (
        (checkpoint_sequence IS NULL AND checkpoint_token IS NULL AND checkpoint_at IS NULL)
        OR
        (checkpoint_sequence >= 1 AND checkpoint_token IS NOT NULL AND checkpoint_at IS NOT NULL)
    ),
    CONSTRAINT ck_inx_worker_cancel CHECK (cancellation_requested IN ('Y', 'N'))
);

CREATE TABLE IF NOT EXISTS infranexum_core.worker_task_parameter (
    task_id UUID NOT NULL,
    parameter_key VARCHAR(64) NOT NULL,
    parameter_value VARCHAR(4096) NOT NULL,
    PRIMARY KEY (task_id, parameter_key),
    CONSTRAINT fk_inx_worker_param_task FOREIGN KEY (task_id)
        REFERENCES infranexum_core.worker_task(task_id) ON DELETE CASCADE,
    CONSTRAINT ck_inx_worker_parameter_key CHECK (
        parameter_key ~ '^[A-Za-z][A-Za-z0-9_.-]{0,63}$'
    )
);

CREATE INDEX IF NOT EXISTS ix_inx_worker_task_due
    ON infranexum_core.worker_task (status, available_at, created_at, task_id);
CREATE INDEX IF NOT EXISTS ix_inx_worker_task_lease
    ON infranexum_core.worker_task (status, lease_until, created_at, task_id)
    WHERE status = 'RUNNING';
