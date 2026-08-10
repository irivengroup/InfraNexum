-- InfraNexum migration 0005: append-only Core Audit for PostgreSQL.
CREATE SCHEMA IF NOT EXISTS infranexum_core;

CREATE TABLE IF NOT EXISTS infranexum_core.audit_chain_head (
    scope_type VARCHAR(32) NOT NULL,
    scope_id VARCHAR(160) NOT NULL,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    head_hash CHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (scope_type, scope_id),
    CONSTRAINT ck_inx_audit_head_sequence CHECK (last_sequence >= 0),
    CONSTRAINT ck_inx_audit_head_hash CHECK (head_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE IF NOT EXISTS infranexum_core.audit_entry (
    scope_type VARCHAR(32) NOT NULL,
    scope_id VARCHAR(160) NOT NULL,
    sequence_no BIGINT NOT NULL,
    audit_id UUID NOT NULL,
    actor_id VARCHAR(160) NOT NULL,
    actor_type VARCHAR(160) NOT NULL,
    action_name VARCHAR(160) NOT NULL,
    target_type VARCHAR(160) NOT NULL,
    target_id VARCHAR(160) NOT NULL,
    authorization_decision VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID,
    result_name VARCHAR(32) NOT NULL,
    origin_name VARCHAR(512) NOT NULL,
    reason_text VARCHAR(1024),
    client_ip VARCHAR(64),
    user_agent VARCHAR(512),
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    sensitivity VARCHAR(32) NOT NULL,
    previous_hash CHAR(64) NOT NULL,
    entry_hash CHAR(64) NOT NULL,
    immutable_flag CHAR(1) NOT NULL DEFAULT 'Y',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (scope_type, scope_id, sequence_no),
    CONSTRAINT uq_inx_audit_id UNIQUE (audit_id),
    CONSTRAINT ck_inx_audit_sequence CHECK (sequence_no >= 1),
    CONSTRAINT ck_inx_audit_id_v7 CHECK (
        SUBSTRING(audit_id::TEXT FROM 15 FOR 1) = '7'
        AND SUBSTRING(audit_id::TEXT FROM 20 FOR 1) IN ('8', '9', 'a', 'b')
    ),
    CONSTRAINT ck_inx_audit_correlation_v7 CHECK (
        correlation_id IS NULL OR (
            SUBSTRING(correlation_id::TEXT FROM 15 FOR 1) = '7'
            AND SUBSTRING(correlation_id::TEXT FROM 20 FOR 1) IN ('8', '9', 'a', 'b')
        )
    ),
    CONSTRAINT ck_inx_audit_scope_type CHECK (scope_type ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    CONSTRAINT ck_inx_audit_scope_id CHECK (LENGTH(BTRIM(scope_id)) BETWEEN 1 AND 160),
    CONSTRAINT ck_inx_audit_decision CHECK (authorization_decision ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    CONSTRAINT ck_inx_audit_result CHECK (result_name ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    CONSTRAINT ck_inx_audit_sensitivity CHECK (sensitivity ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    CONSTRAINT ck_inx_audit_hashes CHECK (
        previous_hash ~ '^[0-9a-f]{64}$' AND entry_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_inx_audit_immutable CHECK (immutable_flag = 'Y')
);

CREATE INDEX IF NOT EXISTS ix_inx_audit_time
    ON infranexum_core.audit_entry (scope_type, scope_id, occurred_at DESC, sequence_no DESC);
CREATE INDEX IF NOT EXISTS ix_inx_audit_actor
    ON infranexum_core.audit_entry (scope_type, scope_id, actor_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_inx_audit_action
    ON infranexum_core.audit_entry (scope_type, scope_id, action_name, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_inx_audit_target
    ON infranexum_core.audit_entry (scope_type, scope_id, target_type, target_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_inx_audit_correlation
    ON infranexum_core.audit_entry (correlation_id) WHERE correlation_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS infranexum_core.audit_purge_tombstone (
    tombstone_id UUID PRIMARY KEY,
    scope_type VARCHAR(32) NOT NULL,
    scope_id VARCHAR(160) NOT NULL,
    policy_id VARCHAR(160) NOT NULL,
    approved_by_first UUID NOT NULL,
    approved_by_second UUID NOT NULL,
    purged_at TIMESTAMPTZ NOT NULL,
    proof_sha256 CHAR(64) NOT NULL,
    reason_text VARCHAR(1024) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_inx_audit_tombstone_id CHECK (
        SUBSTRING(tombstone_id::TEXT FROM 15 FOR 1) = '7'
        AND SUBSTRING(tombstone_id::TEXT FROM 20 FOR 1) IN ('8', '9', 'a', 'b')
    ),
    CONSTRAINT ck_inx_audit_tombstone_approvers CHECK (approved_by_first <> approved_by_second),
    CONSTRAINT ck_inx_audit_tombstone_sha CHECK (proof_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE OR REPLACE FUNCTION infranexum_core.reject_audit_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'InfraNexum Core Audit is append-only';
END;
$$;

DROP TRIGGER IF EXISTS trg_inx_audit_entry_immutable ON infranexum_core.audit_entry;
CREATE TRIGGER trg_inx_audit_entry_immutable
BEFORE UPDATE OR DELETE ON infranexum_core.audit_entry
FOR EACH ROW EXECUTE FUNCTION infranexum_core.reject_audit_mutation();

DROP TRIGGER IF EXISTS trg_inx_audit_tombstone_immutable ON infranexum_core.audit_purge_tombstone;
CREATE TRIGGER trg_inx_audit_tombstone_immutable
BEFORE UPDATE OR DELETE ON infranexum_core.audit_purge_tombstone
FOR EACH ROW EXECUTE FUNCTION infranexum_core.reject_audit_mutation();
