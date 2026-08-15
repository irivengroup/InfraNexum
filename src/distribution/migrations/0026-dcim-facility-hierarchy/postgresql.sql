CREATE SCHEMA IF NOT EXISTS infranexum_dcim;

CREATE TABLE IF NOT EXISTS infranexum_dcim.facility_node (
  id UUID PRIMARY KEY,
  facility_kind VARCHAR(16) NOT NULL,
  organization_id UUID NOT NULL,
  subdivision_id UUID NOT NULL,
  parent_id UUID NULL REFERENCES infranexum_dcim.facility_node(id),
  scope_id UUID NOT NULL,
  code VARCHAR(64) NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  lifecycle_status VARCHAR(16) NOT NULL,
  address_line_1 VARCHAR(128) NULL,
  address_line_2 VARCHAR(128) NULL,
  postal_code VARCHAR(16) NULL,
  city VARCHAR(64) NULL,
  country_code CHAR(2) NULL,
  timezone VARCHAR(64) NULL,
  latitude NUMERIC(10,7) NULL,
  longitude NUMERIC(10,7) NULL,
  floor_count INTEGER NULL,
  level_number INTEGER NULL,
  area_m2 NUMERIC(14,3) NULL,
  level_height_m NUMERIC(10,3) NULL,
  capacity_kw NUMERIC(14,3) NULL,
  access_restriction VARCHAR(16) NULL,
  zone_type VARCHAR(32) NULL,
  description VARCHAR(4096) NULL,
  version BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  created_by UUID NOT NULL,
  updated_by UUID NOT NULL,
  last_reason VARCHAR(1024) NOT NULL,
  CONSTRAINT uq_inx_dcim_facility_scope_code UNIQUE(facility_kind,scope_id,code),
  CONSTRAINT ck_inx_dcim_facility_id CHECK (id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT ck_inx_dcim_facility_kind CHECK (facility_kind IN ('SITE','BUILDING','FLOOR','ROOM','ZONE')),
  CONSTRAINT ck_inx_dcim_facility_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{2,63}$'),
  CONSTRAINT ck_inx_dcim_facility_status CHECK (lifecycle_status IN ('DRAFT','ACTIVE','SUSPENDED','MAINTENANCE','LOCKED','INACTIVE','ARCHIVED','DELETED')),
  CONSTRAINT ck_inx_dcim_facility_geo CHECK ((latitude IS NULL OR latitude BETWEEN -90 AND 90) AND (longitude IS NULL OR longitude BETWEEN -180 AND 180)),
  CONSTRAINT ck_inx_dcim_facility_positive CHECK ((floor_count IS NULL OR floor_count > 0) AND (area_m2 IS NULL OR area_m2 > 0) AND (level_height_m IS NULL OR level_height_m > 0) AND (capacity_kw IS NULL OR capacity_kw > 0)),
  CONSTRAINT ck_inx_dcim_facility_access CHECK (access_restriction IS NULL OR access_restriction IN ('open','restricted','secure')),
  CONSTRAINT ck_inx_dcim_facility_zone CHECK (zone_type IS NULL OR zone_type IN ('cooling','power_distribution','airflow','security')),
  CONSTRAINT ck_inx_dcim_facility_site CHECK ((facility_kind <> 'SITE') OR (parent_id IS NULL AND length(btrim(address_line_1)) > 0 AND length(btrim(postal_code)) > 0 AND length(btrim(city)) > 0 AND country_code IS NOT NULL AND timezone IS NOT NULL)),
  CONSTRAINT ck_inx_dcim_facility_site_fields CHECK ((facility_kind = 'SITE') OR (address_line_1 IS NULL AND address_line_2 IS NULL AND postal_code IS NULL AND city IS NULL AND country_code IS NULL AND timezone IS NULL)),
  CONSTRAINT ck_inx_dcim_facility_building CHECK ((facility_kind <> 'BUILDING') OR floor_count IS NOT NULL),
  CONSTRAINT ck_inx_dcim_facility_floor CHECK ((facility_kind <> 'FLOOR') OR level_number IS NOT NULL),
  CONSTRAINT ck_inx_dcim_facility_room CHECK ((facility_kind <> 'ROOM') OR area_m2 IS NOT NULL),
  CONSTRAINT ck_inx_dcim_facility_zone_required CHECK ((facility_kind <> 'ZONE') OR zone_type IS NOT NULL),
  CONSTRAINT ck_inx_dcim_facility_kind_fields CHECK (
    (facility_kind = 'BUILDING' OR floor_count IS NULL) AND
    (facility_kind = 'FLOOR' OR (level_number IS NULL AND level_height_m IS NULL)) AND
    (facility_kind IN ('BUILDING','FLOOR','ROOM') OR area_m2 IS NULL) AND
    (facility_kind IN ('FLOOR','ROOM') OR capacity_kw IS NULL) AND
    (facility_kind = 'ROOM' OR access_restriction IS NULL) AND
    (facility_kind = 'ZONE' OR zone_type IS NULL) AND
    (facility_kind IN ('SITE','BUILDING') OR (latitude IS NULL AND longitude IS NULL))
  ),
  CONSTRAINT ck_inx_dcim_facility_child CHECK ((facility_kind = 'SITE') OR parent_id IS NOT NULL),
  CONSTRAINT ck_inx_dcim_facility_version CHECK (version >= 1),
  CONSTRAINT ck_inx_dcim_facility_time CHECK (updated_at >= created_at)
);
CREATE INDEX IF NOT EXISTS ix_inx_dcim_facility_org_kind ON infranexum_dcim.facility_node(organization_id,subdivision_id,facility_kind,lifecycle_status,id);
CREATE INDEX IF NOT EXISTS ix_inx_dcim_facility_parent ON infranexum_dcim.facility_node(parent_id,facility_kind,lifecycle_status,id);

CREATE TABLE IF NOT EXISTS infranexum_dcim.facility_command_dedup (
  idempotency_key VARCHAR(200) PRIMARY KEY,
  payload_sha256 CHAR(64) NOT NULL,
  operation_name VARCHAR(80) NOT NULL,
  facility_id UUID NOT NULL REFERENCES infranexum_dcim.facility_node(id),
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_inx_dcim_facility_dedup_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX IF NOT EXISTS ix_inx_dcim_facility_dedup_time ON infranexum_dcim.facility_command_dedup(created_at);
