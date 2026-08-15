CREATE TABLE IF NOT EXISTS infranexum_itam.asset (
  id UUID PRIMARY KEY,
  rsot_object_id UUID NOT NULL,
  asset_type VARCHAR(16) NOT NULL,
  owning_organization_id UUID NOT NULL,
  owning_subdivision_id UUID NULL,
  acquisition_date DATE NOT NULL,
  acquisition_value NUMERIC(19,4) NOT NULL,
  currency_code CHAR(3) NOT NULL,
  acquired_from_partner_id UUID NULL,
  lifecycle_status VARCHAR(24) NOT NULL,
  current_custodian_kind VARCHAR(20) NOT NULL,
  current_custodian_id UUID NULL,
  version BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  created_by UUID NOT NULL,
  updated_by UUID NOT NULL,
  last_reason VARCHAR(1024) NOT NULL,
  CONSTRAINT uq_inx_itam_asset_rsot UNIQUE(rsot_object_id),
  CONSTRAINT ck_inx_itam_asset_uuidv7 CHECK (id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT ck_inx_itam_asset_rsot_uuid CHECK (rsot_object_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT ck_inx_itam_asset_org_uuid CHECK (owning_organization_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT ck_inx_itam_asset_sub_uuid CHECK (owning_subdivision_id IS NULL OR owning_subdivision_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT ck_inx_itam_asset_partner_uuid CHECK (acquired_from_partner_id IS NULL OR acquired_from_partner_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT ck_inx_itam_asset_type CHECK (asset_type IN ('HARDWARE','SOFTWARE')),
  CONSTRAINT ck_inx_itam_asset_status CHECK (lifecycle_status IN ('ACQUIRED','RECEIVED','IN_STOCK','ASSIGNED','DEPLOYED','MAINTENANCE','RETURNED','RETIRED','DISPOSED')),
  CONSTRAINT ck_inx_itam_asset_cust_kind CHECK (current_custodian_kind IN ('NONE','ORGANIZATION','SUBDIVISION','ACTOR','PARTNER')),
  CONSTRAINT ck_inx_itam_asset_cust_ref CHECK ((current_custodian_kind='NONE' AND current_custodian_id IS NULL) OR (current_custodian_kind<>'NONE' AND current_custodian_id IS NOT NULL)),
  CONSTRAINT ck_inx_itam_asset_value CHECK (acquisition_value >= 0),
  CONSTRAINT ck_inx_itam_asset_currency CHECK (currency_code ~ '^[A-Z]{3}$'),
  CONSTRAINT ck_inx_itam_asset_version CHECK (version >= 1),
  CONSTRAINT ck_inx_itam_asset_time CHECK (updated_at >= created_at)
);
CREATE INDEX IF NOT EXISTS ix_inx_itam_asset_portfolio ON infranexum_itam.asset(owning_organization_id,lifecycle_status,asset_type,id);
CREATE INDEX IF NOT EXISTS ix_inx_itam_asset_partner ON infranexum_itam.asset(acquired_from_partner_id) WHERE acquired_from_partner_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS infranexum_itam.asset_custody_event (
  event_id UUID NOT NULL,
  asset_id UUID NOT NULL REFERENCES infranexum_itam.asset(id) ON DELETE RESTRICT,
  sequence_no BIGINT NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  from_status VARCHAR(24) NULL,
  to_status VARCHAR(24) NOT NULL,
  custodian_kind VARCHAR(20) NOT NULL,
  custodian_id UUID NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  actor_id UUID NOT NULL,
  correlation_id UUID NOT NULL,
  reason VARCHAR(1024) NOT NULL,
  evidence_reference VARCHAR(240) NULL,
  PRIMARY KEY(asset_id,sequence_no),
  CONSTRAINT uq_inx_itam_asset_cust_event UNIQUE(event_id),
  CONSTRAINT ck_inx_itam_asset_cust_seq CHECK (sequence_no >= 1),
  CONSTRAINT ck_inx_itam_asset_cust_type CHECK (event_type IN ('ACQUIRED','RECEIVED','STOCKED','ASSIGNED','DEPLOYED','TRANSFERRED','MAINTENANCE_STARTED','RETURNED','RETIRED','DISPOSED')),
  CONSTRAINT ck_inx_itam_asset_cust_from CHECK (from_status IS NULL OR from_status IN ('ACQUIRED','RECEIVED','IN_STOCK','ASSIGNED','DEPLOYED','MAINTENANCE','RETURNED','RETIRED','DISPOSED')),
  CONSTRAINT ck_inx_itam_asset_cust_to CHECK (to_status IN ('ACQUIRED','RECEIVED','IN_STOCK','ASSIGNED','DEPLOYED','MAINTENANCE','RETURNED','RETIRED','DISPOSED')),
  CONSTRAINT ck_inx_itam_asset_event_kind CHECK (custodian_kind IN ('NONE','ORGANIZATION','SUBDIVISION','ACTOR','PARTNER')),
  CONSTRAINT ck_inx_itam_asset_event_ref CHECK ((custodian_kind='NONE' AND custodian_id IS NULL) OR (custodian_kind<>'NONE' AND custodian_id IS NOT NULL)),
  CONSTRAINT ck_inx_itam_asset_disposal_evidence CHECK (event_type<>'DISPOSED' OR evidence_reference IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS ix_inx_itam_asset_cust_time ON infranexum_itam.asset_custody_event(asset_id,occurred_at,sequence_no);
CREATE INDEX IF NOT EXISTS ix_inx_itam_asset_custodian ON infranexum_itam.asset_custody_event(custodian_kind,custodian_id,occurred_at);

CREATE TABLE IF NOT EXISTS infranexum_itam.asset_command_dedup (
  idempotency_key VARCHAR(200) PRIMARY KEY,
  payload_sha256 CHAR(64) NOT NULL,
  operation_name VARCHAR(40) NOT NULL,
  asset_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_inx_itam_asset_dedup_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX IF NOT EXISTS ix_inx_itam_asset_dedup_time ON infranexum_itam.asset_command_dedup(created_at);
