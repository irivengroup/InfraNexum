BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE core_installation_identity (
    installation_id VARCHAR2(36 CHAR) PRIMARY KEY,
    fingerprint_version VARCHAR2(16 CHAR) NOT NULL,
    fingerprint CHAR(64 CHAR) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_core_install_fp_ver CHECK (REGEXP_LIKE(fingerprint_version, ''^v[1-9][0-9]*$'')),
    CONSTRAINT ck_core_install_fp CHECK (REGEXP_LIKE(fingerprint, ''^[0-9a-f]{64}$''))
  )';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
/
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE core_entitlement_state (
    installation_id VARCHAR2(36 CHAR) PRIMARY KEY REFERENCES core_installation_identity(installation_id),
    profile VARCHAR2(16 CHAR) NOT NULL,
    allocation_tier VARCHAR2(16 CHAR) NOT NULL,
    evaluation_started_at TIMESTAMP WITH TIME ZONE,
    last_reliable_at TIMESTAMP WITH TIME ZONE NOT NULL,
    time_generation NUMBER(19) NOT NULL,
    max_activation_sequence NUMBER(19) DEFAULT 0 NOT NULL,
    accepted_activation_id VARCHAR2(36 CHAR),
    activation_state VARCHAR2(32 CHAR) NOT NULL,
    valid_until TIMESTAMP WITH TIME ZONE,
    grace_until TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_core_ent_profile CHECK (profile IN (''LITE'',''PRO'',''ENTERPRISE'')),
    CONSTRAINT ck_core_ent_tier CHECK ((profile = ''LITE'' AND allocation_tier = ''STANDARD'') OR (profile = ''PRO'' AND allocation_tier IN (''STANDARD'',''ADVANCED'')) OR (profile = ''ENTERPRISE'' AND allocation_tier IN (''STANDARD'',''ULTIMATE''))),
    CONSTRAINT ck_core_ent_lite CHECK ((profile = ''LITE'' AND evaluation_started_at IS NOT NULL AND accepted_activation_id IS NULL) OR (profile <> ''LITE'' AND evaluation_started_at IS NULL)),
    CONSTRAINT ck_core_ent_seq CHECK (time_generation >= 1 AND max_activation_sequence >= 0 AND ((max_activation_sequence = 0 AND accepted_activation_id IS NULL) OR (max_activation_sequence > 0 AND accepted_activation_id IS NOT NULL))),
    CONSTRAINT ck_core_ent_dates CHECK (last_reliable_at <= updated_at AND ((valid_until IS NULL AND grace_until IS NULL) OR (valid_until IS NOT NULL AND grace_until = valid_until + INTERVAL ''30'' DAY)))
  )';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
/
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE core_entitlement_integrity_proof (
    installation_id VARCHAR2(36 CHAR) PRIMARY KEY REFERENCES core_installation_identity(installation_id),
    fingerprint CHAR(64 CHAR) NOT NULL,
    evaluation_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_reliable_at TIMESTAMP WITH TIME ZONE NOT NULL,
    generation NUMBER(19) NOT NULL,
    mac_base64 VARCHAR2(64 CHAR) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_core_integrity_gen CHECK (generation >= 1),
    CONSTRAINT ck_core_integrity_int CHECK (last_reliable_at >= evaluation_started_at)
  )';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
/
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE core_activation_manifest (
    activation_id VARCHAR2(36 CHAR) PRIMARY KEY,
    installation_id VARCHAR2(36 CHAR) NOT NULL REFERENCES core_installation_identity(installation_id),
    customer_id VARCHAR2(255 CHAR) NOT NULL,
    customer_legal_name VARCHAR2(255 CHAR) NOT NULL,
    profile VARCHAR2(16 CHAR) NOT NULL,
    allocation_tier VARCHAR2(16 CHAR) NOT NULL,
    catalog_version VARCHAR2(64 CHAR) NOT NULL,
    host_limit NUMBER(19) NOT NULL,
    capabilities_json CLOB NOT NULL,
    quotas_json CLOB NOT NULL,
    valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_until TIMESTAMP WITH TIME ZONE NOT NULL,
    grace_period_days NUMBER(3) NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    issuer VARCHAR2(255 CHAR) NOT NULL,
    sequence NUMBER(19) NOT NULL,
    key_id VARCHAR2(160 CHAR) NOT NULL,
    signature_base64 VARCHAR2(128 CHAR) NOT NULL,
    manifest_sha256 CHAR(64 CHAR) NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_core_activation_seq UNIQUE (installation_id, sequence),
    CONSTRAINT ck_core_activation_profile CHECK (profile IN (''PRO'',''ENTERPRISE'')),
    CONSTRAINT ck_core_activation_tier CHECK ((profile = ''PRO'' AND allocation_tier IN (''STANDARD'',''ADVANCED'')) OR (profile = ''ENTERPRISE'' AND allocation_tier IN (''STANDARD'',''ULTIMATE''))),
    CONSTRAINT ck_core_activation_dates CHECK (issued_at <= valid_from AND valid_from < valid_until AND grace_period_days = 30),
    CONSTRAINT ck_core_activation_vals CHECK (host_limit >= 0 AND sequence >= 1),
    CONSTRAINT ck_core_activation_sha CHECK (REGEXP_LIKE(manifest_sha256, ''^[0-9a-f]{64}$'')),
    CONSTRAINT ck_core_activation_caps_json CHECK (capabilities_json IS JSON),
    CONSTRAINT ck_core_activation_quotas_json CHECK (quotas_json IS JSON)
  )';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
/
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_core_activation_inst_valid ON core_activation_manifest (installation_id, valid_until DESC, sequence DESC)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
/
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE core_activation_revocation (
    revocation_type VARCHAR2(16 CHAR) NOT NULL,
    revocation_key VARCHAR2(160 CHAR) NOT NULL,
    effective_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reason VARCHAR2(512 CHAR) NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (revocation_type, revocation_key),
    CONSTRAINT ck_core_revoke_type CHECK (revocation_type IN (''KEY'',''ACTIVATION''))
  )';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
/
