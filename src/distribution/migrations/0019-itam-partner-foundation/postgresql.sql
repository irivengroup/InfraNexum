CREATE SCHEMA IF NOT EXISTS infranexum_itam;

CREATE TABLE IF NOT EXISTS infranexum_itam.partner (
  id UUID PRIMARY KEY,
  governing_organization_id UUID NOT NULL,
  governing_subdivision_id UUID NULL,
  code VARCHAR(32) NOT NULL,
  legal_name VARCHAR(255) NOT NULL,
  legal_name_normalized VARCHAR(255) NOT NULL,
  display_name VARCHAR(255) NOT NULL,
  country_code CHAR(2) NOT NULL,
  authorization_status VARCHAR(24) NOT NULL,
  valid_from DATE NOT NULL,
  valid_until DATE NULL,
  official_website VARCHAR(2048) NULL,
  support_portal VARCHAR(2048) NULL,
  version BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  created_by UUID NOT NULL,
  updated_by UUID NOT NULL,
  last_reason VARCHAR(1024) NOT NULL,
  CONSTRAINT uq_inx_itam_partner_code UNIQUE(governing_organization_id,code),
  CONSTRAINT ck_inx_itam_partner_uuidv7 CHECK (id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT ck_inx_itam_partner_org_uuidv7 CHECK (governing_organization_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT ck_inx_itam_partner_sub_uuidv7 CHECK (governing_subdivision_id IS NULL OR governing_subdivision_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT ck_inx_itam_partner_code CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{2,31}$'),
  CONSTRAINT ck_inx_itam_partner_country CHECK (country_code ~ '^[A-Z]{2}$'),
  CONSTRAINT ck_inx_itam_partner_status CHECK (authorization_status IN ('DRAFT','PENDING_APPROVAL','ACTIVE','SUSPENDED','RETIRED')),
  CONSTRAINT ck_inx_itam_partner_period CHECK (valid_until IS NULL OR valid_until >= valid_from),
  CONSTRAINT ck_inx_itam_partner_version CHECK (version >= 1),
  CONSTRAINT ck_inx_itam_partner_time CHECK (updated_at >= created_at)
);
CREATE INDEX IF NOT EXISTS ix_inx_itam_partner_catalogue ON infranexum_itam.partner(governing_organization_id,authorization_status,country_code,id);

CREATE TABLE IF NOT EXISTS infranexum_itam.partner_role (
  partner_id UUID NOT NULL REFERENCES infranexum_itam.partner(id) ON DELETE CASCADE,
  role_code VARCHAR(40) NOT NULL,
  PRIMARY KEY(partner_id,role_code),
  CONSTRAINT ck_inx_itam_partner_role CHECK (role_code IN ('MANUFACTURER','SOFTWARE_PUBLISHER','SUPPLIER','THIRD_PARTY_SUPPORT_PROVIDER','INTEGRATOR','RECYCLER'))
);
CREATE INDEX IF NOT EXISTS ix_inx_itam_partner_role_catalogue ON infranexum_itam.partner_role(role_code,partner_id);

CREATE TABLE IF NOT EXISTS infranexum_itam.partner_alias (
  partner_id UUID NOT NULL REFERENCES infranexum_itam.partner(id) ON DELETE CASCADE,
  alias_name VARCHAR(255) NOT NULL,
  PRIMARY KEY(partner_id,alias_name)
);

CREATE TABLE IF NOT EXISTS infranexum_itam.partner_external_id (
  partner_id UUID NOT NULL REFERENCES infranexum_itam.partner(id) ON DELETE CASCADE,
  authority_code VARCHAR(64) NOT NULL,
  external_value VARCHAR(240) NOT NULL,
  PRIMARY KEY(partner_id,authority_code,external_value)
);

CREATE TABLE IF NOT EXISTS infranexum_itam.partner_accreditation (
  partner_id UUID NOT NULL REFERENCES infranexum_itam.partner(id) ON DELETE CASCADE,
  position_no SMALLINT NOT NULL,
  accreditation_code VARCHAR(120) NOT NULL,
  issuer_name VARCHAR(200) NOT NULL,
  valid_from DATE NOT NULL,
  valid_until DATE NULL,
  evidence_reference VARCHAR(240) NOT NULL,
  PRIMARY KEY(partner_id,position_no),
  CONSTRAINT ck_inx_itam_partner_acc_pos CHECK (position_no BETWEEN 1 AND 128),
  CONSTRAINT ck_inx_itam_partner_acc_period CHECK (valid_until IS NULL OR valid_until >= valid_from)
);
CREATE INDEX IF NOT EXISTS ix_inx_itam_partner_acc_code ON infranexum_itam.partner_accreditation(accreditation_code,partner_id);

CREATE TABLE IF NOT EXISTS infranexum_itam.partner_contact (
  partner_id UUID NOT NULL REFERENCES infranexum_itam.partner(id) ON DELETE CASCADE,
  position_no SMALLINT NOT NULL,
  contact_type VARCHAR(32) NOT NULL,
  contact_name VARCHAR(160) NOT NULL,
  email_address VARCHAR(320) NULL,
  phone_number VARCHAR(64) NULL,
  contact_uri VARCHAR(2048) NULL,
  PRIMARY KEY(partner_id,position_no),
  CONSTRAINT ck_inx_itam_partner_contact_pos CHECK (position_no BETWEEN 1 AND 128),
  CONSTRAINT ck_inx_itam_partner_contact_channel CHECK (email_address IS NOT NULL OR phone_number IS NOT NULL OR contact_uri IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS infranexum_itam.partner_identity_token (
  governing_organization_id UUID NOT NULL,
  identity_token VARCHAR(600) NOT NULL,
  partner_id UUID NOT NULL REFERENCES infranexum_itam.partner(id) ON DELETE CASCADE,
  PRIMARY KEY(governing_organization_id,identity_token),
  CONSTRAINT uq_inx_itam_partner_ident_partner UNIQUE(partner_id,identity_token)
);

CREATE TABLE IF NOT EXISTS infranexum_itam.partner_command_dedup (
  idempotency_key VARCHAR(200) PRIMARY KEY,
  payload_sha256 CHAR(64) NOT NULL,
  operation_name VARCHAR(40) NOT NULL,
  partner_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_inx_itam_partner_dedup_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX IF NOT EXISTS ix_inx_itam_partner_dedup_time ON infranexum_itam.partner_command_dedup(created_at);
