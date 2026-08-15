CREATE SCHEMA IF NOT EXISTS infranexum_dcim;

CREATE TABLE IF NOT EXISTS infranexum_dcim.equipment_model (
  id UUID PRIMARY KEY, organization_id UUID NOT NULL, manufacturer_partner_id UUID NOT NULL,
  code VARCHAR(64) NOT NULL, display_name VARCHAR(128) NOT NULL, form_factor VARCHAR(32) NOT NULL,
  rack_units INTEGER NOT NULL, width_mm INTEGER NOT NULL, depth_mm INTEGER NOT NULL, weight_kg NUMERIC(12,3) NOT NULL,
  lifecycle_status VARCHAR(16) NOT NULL, description VARCHAR(4096), version BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, created_by UUID NOT NULL, updated_by UUID NOT NULL, last_reason VARCHAR(1024) NOT NULL,
  CONSTRAINT uq_inx_dcim_model_vendor_code UNIQUE(organization_id,manufacturer_partner_id,code),
  CONSTRAINT ck_inx_dcim_model_dims CHECK(rack_units BETWEEN 1 AND 100 AND width_mm BETWEEN 1 AND 5000 AND depth_mm BETWEEN 1 AND 5000 AND weight_kg>0),
  CONSTRAINT ck_inx_dcim_model_status CHECK(lifecycle_status IN ('DRAFT','ACTIVE','DECOMMISSIONED','ARCHIVED')),
  CONSTRAINT ck_inx_dcim_model_ver CHECK(version>=1), CONSTRAINT ck_inx_dcim_model_time CHECK(updated_at>=created_at)
);
CREATE TABLE IF NOT EXISTS infranexum_dcim.model_port_template (
  model_id UUID NOT NULL REFERENCES infranexum_dcim.equipment_model(id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL, name_prefix VARCHAR(24) NOT NULL, port_count INTEGER NOT NULL, port_kind VARCHAR(16) NOT NULL,
  media VARCHAR(32) NOT NULL, connector VARCHAR(32) NOT NULL,
  PRIMARY KEY(model_id,ordinal), CONSTRAINT ck_inx_dcim_tpl_count CHECK(port_count BETWEEN 1 AND 512),
  CONSTRAINT ck_inx_dcim_tpl_kind CHECK(port_kind IN ('NETWORK','POWER','CONSOLE','FIBER','OTHER'))
);
CREATE TABLE IF NOT EXISTS infranexum_dcim.rack (
  id UUID PRIMARY KEY, organization_id UUID NOT NULL, subdivision_id UUID NOT NULL, room_id UUID NOT NULL,
  code VARCHAR(64) NOT NULL, display_name VARCHAR(128) NOT NULL, height_u INTEGER NOT NULL, width_mm INTEGER NOT NULL, depth_mm INTEGER NOT NULL,
  lifecycle_status VARCHAR(16) NOT NULL, version BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
  created_by UUID NOT NULL, updated_by UUID NOT NULL, last_reason VARCHAR(1024) NOT NULL,
  CONSTRAINT uq_inx_dcim_rack_room_code UNIQUE(room_id,code), CONSTRAINT ck_inx_dcim_rack_dims CHECK(height_u BETWEEN 1 AND 100 AND width_mm BETWEEN 1 AND 5000 AND depth_mm BETWEEN 1 AND 5000),
  CONSTRAINT ck_inx_dcim_rack_status CHECK(lifecycle_status IN ('DRAFT','ACTIVE','MAINTENANCE','DECOMMISSIONED','ARCHIVED')), CONSTRAINT ck_inx_dcim_rack_ver CHECK(version>=1), CONSTRAINT ck_inx_dcim_rack_time CHECK(updated_at>=created_at)
);
CREATE INDEX IF NOT EXISTS ix_inx_dcim_rack_scope ON infranexum_dcim.rack(organization_id,subdivision_id,room_id,lifecycle_status,id);
CREATE TABLE IF NOT EXISTS infranexum_dcim.equipment (
  id UUID PRIMARY KEY, organization_id UUID NOT NULL, subdivision_id UUID NOT NULL,
  rack_id UUID NOT NULL REFERENCES infranexum_dcim.rack(id), model_id UUID NOT NULL REFERENCES infranexum_dcim.equipment_model(id),
  rsot_object_id UUID NOT NULL, itam_asset_id UUID NULL, serial_number VARCHAR(128) NULL, asset_tag VARCHAR(128) NULL,
  start_u INTEGER NOT NULL, face VARCHAR(5) NOT NULL, lifecycle_status VARCHAR(16) NOT NULL, version BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, created_by UUID NOT NULL, updated_by UUID NOT NULL, last_reason VARCHAR(1024) NOT NULL,
  CONSTRAINT uq_inx_dcim_equipment_rsot UNIQUE(rsot_object_id), CONSTRAINT uq_inx_dcim_equipment_serial UNIQUE(serial_number),
  CONSTRAINT ck_inx_dcim_equipment_u CHECK(start_u BETWEEN 1 AND 100), CONSTRAINT ck_inx_dcim_equipment_face CHECK(face IN ('front','rear')),
  CONSTRAINT ck_inx_dcim_equipment_status CHECK(lifecycle_status IN ('ACTIVE','MAINTENANCE','DECOMMISSIONED','ARCHIVED')), CONSTRAINT ck_inx_dcim_equipment_ver CHECK(version>=1), CONSTRAINT ck_inx_dcim_equipment_time CHECK(updated_at>=created_at)
);
CREATE INDEX IF NOT EXISTS ix_inx_dcim_equipment_rack ON infranexum_dcim.equipment(rack_id,lifecycle_status,start_u,id);
CREATE TABLE IF NOT EXISTS infranexum_dcim.physical_port (
  id UUID PRIMARY KEY, organization_id UUID NOT NULL, equipment_id UUID NOT NULL REFERENCES infranexum_dcim.equipment(id) ON DELETE CASCADE,
  port_name VARCHAR(64) NOT NULL, port_kind VARCHAR(16) NOT NULL, media VARCHAR(32) NOT NULL, connector VARCHAR(32) NOT NULL,
  CONSTRAINT uq_inx_dcim_port_name UNIQUE(equipment_id,port_name), CONSTRAINT ck_inx_dcim_port_kind CHECK(port_kind IN ('NETWORK','POWER','CONSOLE','FIBER','OTHER'))
);
CREATE INDEX IF NOT EXISTS ix_inx_dcim_port_equipment ON infranexum_dcim.physical_port(equipment_id,id);
CREATE TABLE IF NOT EXISTS infranexum_dcim.cable_connection (
  id UUID PRIMARY KEY, organization_id UUID NOT NULL, subdivision_id UUID NOT NULL,
  port_a_id UUID NOT NULL REFERENCES infranexum_dcim.physical_port(id), port_b_id UUID NOT NULL REFERENCES infranexum_dcim.physical_port(id),
  label VARCHAR(128) NOT NULL, media VARCHAR(32) NOT NULL, connector VARCHAR(32) NOT NULL, lifecycle_status VARCHAR(16) NOT NULL, version BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, created_by UUID NOT NULL, updated_by UUID NOT NULL, last_reason VARCHAR(1024) NOT NULL,
  CONSTRAINT ck_inx_dcim_cable_endpoints CHECK(port_a_id<>port_b_id), CONSTRAINT ck_inx_dcim_cable_status CHECK(lifecycle_status IN ('ACTIVE','DECOMMISSIONED','ARCHIVED')), CONSTRAINT ck_inx_dcim_cable_ver CHECK(version>=1), CONSTRAINT ck_inx_dcim_cable_time CHECK(updated_at>=created_at)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_inx_dcim_cable_active_a ON infranexum_dcim.cable_connection(port_a_id) WHERE lifecycle_status='ACTIVE';
CREATE UNIQUE INDEX IF NOT EXISTS uq_inx_dcim_cable_active_b ON infranexum_dcim.cable_connection(port_b_id) WHERE lifecycle_status='ACTIVE';
CREATE INDEX IF NOT EXISTS ix_inx_dcim_cable_scope ON infranexum_dcim.cable_connection(organization_id,subdivision_id,lifecycle_status,id);
CREATE TABLE IF NOT EXISTS infranexum_dcim.physical_command_dedup (
  idempotency_key VARCHAR(200) PRIMARY KEY, payload_sha256 CHAR(64) NOT NULL, operation_name VARCHAR(80) NOT NULL, result_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_inx_dcim_phys_dedup_hash CHECK(payload_sha256 ~ '^[0-9a-f]{64}$')
);
