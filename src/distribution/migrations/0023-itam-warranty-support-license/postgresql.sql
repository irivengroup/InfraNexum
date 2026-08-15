ALTER TABLE infranexum_itam.asset ADD COLUMN IF NOT EXISTS producer_partner_id UUID NULL;
CREATE INDEX IF NOT EXISTS ix_inx_itam_asset_producer ON infranexum_itam.asset(producer_partner_id) WHERE producer_partner_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS infranexum_itam.warranty_type (
  id UUID PRIMARY KEY, code VARCHAR(64) NOT NULL UNIQUE, display_name VARCHAR(160) NOT NULL, active BOOLEAN NOT NULL,
  created_at TIMESTAMPTZ NOT NULL, created_by UUID NOT NULL,
  CONSTRAINT ck_inx_itam_wtype_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,63}$')
);

CREATE TABLE IF NOT EXISTS infranexum_itam.warranty (
  id UUID PRIMARY KEY, asset_id UUID NOT NULL REFERENCES infranexum_itam.asset(id) ON DELETE RESTRICT,
  manufacturer_partner_id UUID NOT NULL, warranty_type_id UUID NOT NULL REFERENCES infranexum_itam.warranty_type(id) ON DELETE RESTRICT,
  coverage_level VARCHAR(120) NOT NULL, warranty_start_date DATE NOT NULL, warranty_end_date DATE NOT NULL,
  manufacturer_support_end_date DATE NOT NULL, contract_certificate_number VARCHAR(160) NULL,
  proof_reference VARCHAR(240) NOT NULL, source VARCHAR(24) NOT NULL, status VARCHAR(24) NOT NULL,
  verified_at TIMESTAMPTZ NULL, verified_by UUID NULL, version BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, created_by UUID NOT NULL, updated_by UUID NOT NULL,
  last_reason VARCHAR(1024) NOT NULL,
  CONSTRAINT ck_inx_itam_warranty_dates CHECK (warranty_end_date>=warranty_start_date AND manufacturer_support_end_date>=warranty_start_date),
  CONSTRAINT ck_inx_itam_warranty_status CHECK (status IN ('DRAFT','ACTIVE','EXPIRED','CANCELLED','SUPERSEDED','REVIEW_REQUIRED')),
  CONSTRAINT ck_inx_itam_warranty_verify CHECK ((status NOT IN ('ACTIVE','EXPIRED','REVIEW_REQUIRED')) OR (verified_at IS NOT NULL AND verified_by IS NOT NULL)),
  CONSTRAINT ck_inx_itam_warranty_version CHECK (version>=1)
);
CREATE INDEX IF NOT EXISTS ix_inx_itam_warranty_asset ON infranexum_itam.warranty(asset_id,status,warranty_end_date);
CREATE INDEX IF NOT EXISTS ix_inx_itam_warranty_support_end ON infranexum_itam.warranty(manufacturer_support_end_date,status);

CREATE TABLE IF NOT EXISTS infranexum_itam.software_license_contract (
  id UUID PRIMARY KEY, asset_id UUID NOT NULL REFERENCES infranexum_itam.asset(id) ON DELETE RESTRICT,
  publisher_partner_id UUID NOT NULL, contract_number VARCHAR(160) NOT NULL, license_model VARCHAR(120) NOT NULL,
  usage_rights VARCHAR(2000) NOT NULL, entitlement_quantity BIGINT NOT NULL, starts_on DATE NOT NULL, ends_on DATE NULL,
  publisher_support_end_date DATE NOT NULL, proof_reference VARCHAR(240) NOT NULL, source VARCHAR(24) NOT NULL,
  status VARCHAR(24) NOT NULL, verified_at TIMESTAMPTZ NULL, verified_by UUID NULL, version BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, created_by UUID NOT NULL, updated_by UUID NOT NULL,
  last_reason VARCHAR(1024) NOT NULL,
  CONSTRAINT ck_inx_itam_license_qty CHECK (entitlement_quantity>=1),
  CONSTRAINT ck_inx_itam_license_dates CHECK ((ends_on IS NULL OR ends_on>=starts_on) AND publisher_support_end_date>=starts_on),
  CONSTRAINT ck_inx_itam_license_status CHECK (status IN ('DRAFT','ACTIVE','EXPIRED','CANCELLED','SUPERSEDED','REVIEW_REQUIRED')),
  CONSTRAINT ck_inx_itam_license_verify CHECK ((status NOT IN ('ACTIVE','EXPIRED','REVIEW_REQUIRED')) OR (verified_at IS NOT NULL AND verified_by IS NOT NULL)),
  CONSTRAINT ck_inx_itam_license_version CHECK (version>=1)
);
CREATE INDEX IF NOT EXISTS ix_inx_itam_license_asset ON infranexum_itam.software_license_contract(asset_id,status,ends_on);
CREATE INDEX IF NOT EXISTS ix_inx_itam_license_support ON infranexum_itam.software_license_contract(publisher_support_end_date,status);

CREATE TABLE IF NOT EXISTS infranexum_itam.support_provider_authorization (
  id UUID PRIMARY KEY, provider_partner_id UUID NOT NULL, organization_id UUID NOT NULL,
  service_hours VARCHAR(240) NOT NULL, time_zone_id VARCHAR(80) NOT NULL, valid_from DATE NOT NULL, valid_until DATE NULL,
  status VARCHAR(24) NOT NULL, version BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
  created_by UUID NOT NULL, updated_by UUID NOT NULL, last_reason VARCHAR(1024) NOT NULL,
  CONSTRAINT ck_inx_itam_sauth_dates CHECK (valid_until IS NULL OR valid_until>=valid_from),
  CONSTRAINT ck_inx_itam_sauth_status CHECK (status IN ('DRAFT','ACTIVE','EXPIRED','CANCELLED','SUPERSEDED','REVIEW_REQUIRED')),
  CONSTRAINT ck_inx_itam_sauth_version CHECK (version>=1)
);
CREATE INDEX IF NOT EXISTS ix_inx_itam_sauth_provider ON infranexum_itam.support_provider_authorization(provider_partner_id,organization_id,status,valid_from,valid_until);

CREATE TABLE IF NOT EXISTS infranexum_itam.support_authorization_manufacturer (
  authorization_id UUID NOT NULL REFERENCES infranexum_itam.support_provider_authorization(id) ON DELETE CASCADE,
  manufacturer_partner_id UUID NOT NULL, PRIMARY KEY(authorization_id,manufacturer_partner_id)
);
CREATE TABLE IF NOT EXISTS infranexum_itam.support_authorization_object_type (
  authorization_id UUID NOT NULL REFERENCES infranexum_itam.support_provider_authorization(id) ON DELETE CASCADE,
  object_type VARCHAR(160) NOT NULL, PRIMARY KEY(authorization_id,object_type)
);
CREATE TABLE IF NOT EXISTS infranexum_itam.support_authorization_subdivision (
  authorization_id UUID NOT NULL REFERENCES infranexum_itam.support_provider_authorization(id) ON DELETE CASCADE,
  subdivision_id UUID NOT NULL, PRIMARY KEY(authorization_id,subdivision_id)
);
CREATE TABLE IF NOT EXISTS infranexum_itam.support_authorization_service_level (
  authorization_id UUID NOT NULL REFERENCES infranexum_itam.support_provider_authorization(id) ON DELETE CASCADE,
  service_level VARCHAR(160) NOT NULL, PRIMARY KEY(authorization_id,service_level)
);
CREATE TABLE IF NOT EXISTS infranexum_itam.support_authorization_escalation_contact (
  authorization_id UUID NOT NULL REFERENCES infranexum_itam.support_provider_authorization(id) ON DELETE CASCADE,
  contact_type VARCHAR(160) NOT NULL, PRIMARY KEY(authorization_id,contact_type)
);

CREATE TABLE IF NOT EXISTS infranexum_itam.support_coverage (
  id UUID PRIMARY KEY, asset_id UUID NOT NULL REFERENCES infranexum_itam.asset(id) ON DELETE RESTRICT,
  provider_partner_id UUID NOT NULL, authorization_id UUID NOT NULL REFERENCES infranexum_itam.support_provider_authorization(id) ON DELETE RESTRICT,
  contract_reference VARCHAR(160) NULL, coverage_type VARCHAR(120) NOT NULL, service_level VARCHAR(160) NOT NULL,
  starts_on DATE NOT NULL, ends_on DATE NOT NULL, supported_manufacturer_id UUID NOT NULL, supported_object_type VARCHAR(160) NOT NULL,
  organization_id UUID NOT NULL, subdivision_id UUID NULL, proof_reference VARCHAR(240) NOT NULL, status VARCHAR(24) NOT NULL,
  version BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, created_by UUID NOT NULL,
  updated_by UUID NOT NULL, last_reason VARCHAR(1024) NOT NULL,
  CONSTRAINT ck_inx_itam_scov_dates CHECK (ends_on>=starts_on),
  CONSTRAINT ck_inx_itam_scov_status CHECK (status IN ('DRAFT','ACTIVE','EXPIRED','CANCELLED','SUPERSEDED','REVIEW_REQUIRED')),
  CONSTRAINT ck_inx_itam_scov_version CHECK (version>=1)
);
CREATE INDEX IF NOT EXISTS ix_inx_itam_scov_asset ON infranexum_itam.support_coverage(asset_id,status,ends_on);
CREATE INDEX IF NOT EXISTS ix_inx_itam_scov_auth ON infranexum_itam.support_coverage(authorization_id,status);

CREATE TABLE IF NOT EXISTS infranexum_itam.compliance_revision (
  record_type VARCHAR(32) NOT NULL, record_id UUID NOT NULL, version BIGINT NOT NULL, status VARCHAR(24) NOT NULL,
  proof_reference VARCHAR(240) NULL, reason VARCHAR(1024) NOT NULL, snapshot_json TEXT NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL, recorded_by UUID NOT NULL, PRIMARY KEY(record_type,record_id,version),
  CONSTRAINT ck_inx_itam_crev_type CHECK (record_type IN ('warranty','license','support_authorization','support_coverage')),
  CONSTRAINT ck_inx_itam_crev_version CHECK (version>=1)
);
CREATE INDEX IF NOT EXISTS ix_inx_itam_crev_time ON infranexum_itam.compliance_revision(record_type,record_id,recorded_at);

CREATE TABLE IF NOT EXISTS infranexum_itam.compliance_command_dedup (
  idempotency_key VARCHAR(200) PRIMARY KEY, payload_sha256 CHAR(64) NOT NULL, operation_name VARCHAR(64) NOT NULL,
  record_type VARCHAR(32) NOT NULL, record_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_inx_itam_cdedup_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX IF NOT EXISTS ix_inx_itam_cdedup_time ON infranexum_itam.compliance_command_dedup(created_at);

CREATE TABLE IF NOT EXISTS infranexum_itam.compliance_alert_dedup (
  alert_kind VARCHAR(40) NOT NULL, record_id UUID NOT NULL, due_date DATE NOT NULL, threshold_days INTEGER NOT NULL,
  emitted_on DATE NOT NULL, PRIMARY KEY(alert_kind,record_id,due_date,threshold_days),
  CONSTRAINT ck_inx_itam_alert_threshold CHECK (threshold_days IN (180,120,90,60,30,15,7,1))
);
