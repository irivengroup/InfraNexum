CREATE TABLE IF NOT EXISTS infranexum_core.api_idempotency (
    scope_key VARCHAR(64) NOT NULL,
    operation_name VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL,
    http_status INTEGER NULL,
    content_type VARCHAR(160) NULL,
    etag VARCHAR(256) NULL,
    location VARCHAR(1024) NULL,
    response_body_b64 TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_inx_api_idempotency PRIMARY KEY(scope_key, operation_name, idempotency_key),
    CONSTRAINT ck_inx_api_idem_state CHECK (state IN ('IN_PROGRESS','COMPLETED','INDETERMINATE')),
    CONSTRAINT ck_inx_api_idem_hash CHECK (request_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX IF NOT EXISTS ix_inx_api_idem_updated ON infranexum_core.api_idempotency(updated_at);
