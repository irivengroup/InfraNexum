CREATE TABLE IF NOT EXISTS infranexum_core.schema_registry_entry (
  id UUID PRIMARY KEY,
  schema_key VARCHAR(160) NOT NULL,
  schema_kind VARCHAR(32) NOT NULL,
  owner_code VARCHAR(160) NOT NULL,
  schema_version VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  definition_json JSONB NOT NULL,
  checksum_sha256 CHAR(64) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  effective_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  published_at TIMESTAMPTZ NULL,
  deprecated_at TIMESTAMPTZ NULL,
  sunset_at TIMESTAMPTZ NULL,
  deprecation_reason VARCHAR(500) NULL,
  compatibility_evidence VARCHAR(4000) NULL,
  breaking_approval_ref VARCHAR(240) NULL,
  CONSTRAINT uq_inx_core_schema_key_version UNIQUE(schema_key,schema_version),
  CONSTRAINT ck_inx_core_schema_uuidv7 CHECK (id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT ck_inx_core_schema_key CHECK (schema_key ~ '^[a-z][a-z0-9.-]{2,159}$'),
  CONSTRAINT ck_inx_core_schema_owner CHECK (owner_code ~ '^[a-z][a-z0-9._-]{2,159}$'),
  CONSTRAINT ck_inx_core_schema_kind CHECK (schema_kind IN ('API','EVENT','COMMAND','IMPORT','EXPORT','RSOT_CANONICAL','RSOT_EXTENSION','CUSTOM_FIELD','PROJECTION')),
  CONSTRAINT ck_inx_core_schema_status CHECK (status IN ('DRAFT','PUBLISHED','DEPRECATED')),
  CONSTRAINT ck_inx_core_schema_checksum CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inx_core_schema_revision CHECK (revision >= 1),
  CONSTRAINT ck_inx_core_schema_time CHECK (updated_at >= created_at),
  CONSTRAINT ck_inx_core_schema_lifecycle CHECK (
    (status='DRAFT' AND published_at IS NULL AND deprecated_at IS NULL AND sunset_at IS NULL AND deprecation_reason IS NULL)
    OR (status='PUBLISHED' AND published_at IS NOT NULL AND deprecated_at IS NULL AND sunset_at IS NULL AND deprecation_reason IS NULL)
    OR (status='DEPRECATED' AND published_at IS NOT NULL AND deprecated_at IS NOT NULL AND sunset_at > deprecated_at AND deprecation_reason IS NOT NULL)
  )
);
CREATE INDEX IF NOT EXISTS ix_inx_core_schema_lookup ON infranexum_core.schema_registry_entry(schema_key,status,published_at DESC);
CREATE INDEX IF NOT EXISTS ix_inx_core_schema_kind_status ON infranexum_core.schema_registry_entry(schema_kind,status,effective_at);

CREATE TABLE IF NOT EXISTS infranexum_core.schema_profile (
  id UUID PRIMARY KEY,
  profile_code VARCHAR(160) NOT NULL,
  owner_code VARCHAR(160) NOT NULL,
  profile_version VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  checksum_sha256 CHAR(64) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  published_at TIMESTAMPTZ NULL,
  deprecated_at TIMESTAMPTZ NULL,
  sunset_at TIMESTAMPTZ NULL,
  deprecation_reason VARCHAR(500) NULL,
  CONSTRAINT uq_inx_core_profile_code_version UNIQUE(profile_code,profile_version),
  CONSTRAINT ck_inx_core_profile_uuidv7 CHECK (id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT ck_inx_core_profile_code CHECK (profile_code ~ '^[a-z][a-z0-9.-]{2,159}$'),
  CONSTRAINT ck_inx_core_profile_owner CHECK (owner_code ~ '^[a-z][a-z0-9._-]{2,159}$'),
  CONSTRAINT ck_inx_core_profile_status CHECK (status IN ('DRAFT','PUBLISHED','DEPRECATED')),
  CONSTRAINT ck_inx_core_profile_checksum CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inx_core_profile_revision CHECK (revision >= 1),
  CONSTRAINT ck_inx_core_profile_time CHECK (updated_at >= created_at),
  CONSTRAINT ck_inx_core_profile_lifecycle CHECK (
    (status='DRAFT' AND published_at IS NULL AND deprecated_at IS NULL AND sunset_at IS NULL AND deprecation_reason IS NULL)
    OR (status='PUBLISHED' AND published_at IS NOT NULL AND deprecated_at IS NULL AND sunset_at IS NULL AND deprecation_reason IS NULL)
    OR (status='DEPRECATED' AND published_at IS NOT NULL AND deprecated_at IS NOT NULL AND sunset_at > deprecated_at AND deprecation_reason IS NOT NULL)
  )
);
CREATE INDEX IF NOT EXISTS ix_inx_core_profile_lookup ON infranexum_core.schema_profile(profile_code,status,published_at DESC);

CREATE TABLE IF NOT EXISTS infranexum_core.schema_profile_member (
  profile_id UUID NOT NULL REFERENCES infranexum_core.schema_profile(id) ON DELETE CASCADE,
  position_no SMALLINT NOT NULL,
  schema_id UUID NOT NULL REFERENCES infranexum_core.schema_registry_entry(id),
  required_member BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY(profile_id,position_no),
  CONSTRAINT uq_inx_core_profile_schema UNIQUE(profile_id,schema_id),
  CONSTRAINT ck_inx_core_profile_position CHECK (position_no BETWEEN 1 AND 128)
);
CREATE INDEX IF NOT EXISTS ix_inx_core_profile_member_schema ON infranexum_core.schema_profile_member(schema_id,profile_id);
