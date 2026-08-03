CREATE TABLE IF NOT EXISTS core_installation_identity (
    installation_id UUID PRIMARY KEY,
    fingerprint_version VARCHAR(16) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_core_installation_fingerprint_version CHECK (fingerprint_version ~ '^v[1-9][0-9]*$'),
    CONSTRAINT ck_core_installation_fingerprint CHECK (fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE TABLE IF NOT EXISTS core_entitlement_state (
    installation_id UUID PRIMARY KEY REFERENCES core_installation_identity(installation_id),
    profile VARCHAR(16) NOT NULL,
    allocation_tier VARCHAR(16) NOT NULL,
    evaluation_started_at TIMESTAMPTZ,
    last_reliable_at TIMESTAMPTZ NOT NULL,
    time_generation BIGINT NOT NULL,
    max_activation_sequence BIGINT NOT NULL DEFAULT 0,
    accepted_activation_id UUID,
    activation_state VARCHAR(32) NOT NULL,
    valid_until TIMESTAMPTZ,
    grace_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_core_entitlement_profile CHECK (profile IN ('LITE','PRO','ENTERPRISE')),
    CONSTRAINT ck_core_entitlement_tier CHECK (
        (profile = 'LITE' AND allocation_tier = 'STANDARD') OR
        (profile = 'PRO' AND allocation_tier IN ('STANDARD','ADVANCED')) OR
        (profile = 'ENTERPRISE' AND allocation_tier IN ('STANDARD','ULTIMATE'))
    ),
    CONSTRAINT ck_core_entitlement_lite_origin CHECK (
        (profile = 'LITE' AND evaluation_started_at IS NOT NULL AND accepted_activation_id IS NULL) OR
        (profile <> 'LITE' AND evaluation_started_at IS NULL)
    ),
    CONSTRAINT ck_core_entitlement_sequence CHECK (
        time_generation >= 1 AND max_activation_sequence >= 0 AND
        ((max_activation_sequence = 0 AND accepted_activation_id IS NULL) OR
         (max_activation_sequence > 0 AND accepted_activation_id IS NOT NULL))
    ),
    CONSTRAINT ck_core_entitlement_dates CHECK (
        last_reliable_at <= updated_at AND
        ((valid_until IS NULL AND grace_until IS NULL) OR
         (valid_until IS NOT NULL AND grace_until = valid_until + INTERVAL '30 days'))
    )
);

CREATE TABLE IF NOT EXISTS core_entitlement_integrity_proof (
    installation_id UUID PRIMARY KEY REFERENCES core_installation_identity(installation_id),
    fingerprint CHAR(64) NOT NULL,
    evaluation_started_at TIMESTAMPTZ NOT NULL,
    last_reliable_at TIMESTAMPTZ NOT NULL,
    generation BIGINT NOT NULL,
    mac_base64 VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_core_integrity_generation CHECK (generation >= 1),
    CONSTRAINT ck_core_integrity_interval CHECK (last_reliable_at >= evaluation_started_at)
);

CREATE TABLE IF NOT EXISTS core_activation_manifest (
    activation_id UUID PRIMARY KEY,
    installation_id UUID NOT NULL REFERENCES core_installation_identity(installation_id),
    customer_id VARCHAR(255) NOT NULL,
    customer_legal_name VARCHAR(255) NOT NULL,
    profile VARCHAR(16) NOT NULL,
    allocation_tier VARCHAR(16) NOT NULL,
    catalog_version VARCHAR(64) NOT NULL,
    host_limit BIGINT NOT NULL,
    capabilities_json JSONB NOT NULL,
    quotas_json JSONB NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    grace_period_days INTEGER NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    issuer VARCHAR(255) NOT NULL,
    sequence BIGINT NOT NULL,
    key_id VARCHAR(160) NOT NULL,
    signature_base64 VARCHAR(128) NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_core_activation_sequence UNIQUE (installation_id, sequence),
    CONSTRAINT ck_core_activation_profile CHECK (profile IN ('PRO','ENTERPRISE')),
    CONSTRAINT ck_core_activation_tier CHECK (
        (profile = 'PRO' AND allocation_tier IN ('STANDARD','ADVANCED')) OR
        (profile = 'ENTERPRISE' AND allocation_tier IN ('STANDARD','ULTIMATE'))
    ),
    CONSTRAINT ck_core_activation_dates CHECK (
        issued_at <= valid_from AND valid_from < valid_until AND grace_period_days = 30
    ),
    CONSTRAINT ck_core_activation_values CHECK (host_limit >= 0 AND sequence >= 1),
    CONSTRAINT ck_core_activation_sha CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX IF NOT EXISTS ix_core_activation_installation_validity
    ON core_activation_manifest (installation_id, valid_until DESC, sequence DESC);

CREATE TABLE IF NOT EXISTS core_activation_revocation (
    revocation_type VARCHAR(16) NOT NULL,
    revocation_key VARCHAR(160) NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(512) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (revocation_type, revocation_key),
    CONSTRAINT ck_core_revocation_type CHECK (revocation_type IN ('KEY','ACTIVATION'))
);
