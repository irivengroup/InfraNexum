CREATE SCHEMA IF NOT EXISTS infranexum_ddi;
CREATE TABLE IF NOT EXISTS infranexum_ddi.ipam_vrf (
 id UUID PRIMARY KEY, organization_id UUID NOT NULL, code VARCHAR(64) NOT NULL, display_name VARCHAR(160) NOT NULL,
 route_distinguisher VARCHAR(128), lifecycle_status VARCHAR(16) NOT NULL, version BIGINT NOT NULL CHECK(version>0), created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
 CONSTRAINT uq_ddi_vrf_org_code UNIQUE(organization_id,code), CONSTRAINT ck_ddi_vrf_status CHECK(lifecycle_status IN ('DRAFT','ACTIVE','RESERVED','DEPRECATED','RETIRED'))
);
CREATE TABLE IF NOT EXISTS infranexum_ddi.ipam_vlan (
 id UUID PRIMARY KEY, organization_id UUID NOT NULL, site_id UUID, vlan_id INTEGER, vni BIGINT, name VARCHAR(160) NOT NULL,
 lifecycle_status VARCHAR(16) NOT NULL, version BIGINT NOT NULL CHECK(version>0), created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
 CONSTRAINT ck_ddi_vlan_identifier CHECK(vlan_id IS NOT NULL OR vni IS NOT NULL), CONSTRAINT ck_ddi_vlan_id CHECK(vlan_id IS NULL OR vlan_id BETWEEN 1 AND 4094), CONSTRAINT ck_ddi_vni CHECK(vni IS NULL OR vni BETWEEN 1 AND 16777215)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ddi_vlan_id ON infranexum_ddi.ipam_vlan(organization_id,vlan_id) WHERE vlan_id IS NOT NULL AND lifecycle_status<>'RETIRED';
CREATE UNIQUE INDEX IF NOT EXISTS uq_ddi_vni ON infranexum_ddi.ipam_vlan(organization_id,vni) WHERE vni IS NOT NULL AND lifecycle_status<>'RETIRED';
CREATE TABLE IF NOT EXISTS infranexum_ddi.ipam_network (
 id UUID PRIMARY KEY, organization_id UUID NOT NULL, subdivision_id UUID, site_id UUID, vrf_id UUID NOT NULL, vlan_id UUID, parent_network_id UUID,
 network_kind VARCHAR(16) NOT NULL, cidr_value VARCHAR(64) NOT NULL, address_family SMALLINT NOT NULL, prefix_length SMALLINT NOT NULL,
 first_address VARCHAR(45) NOT NULL, last_address VARCHAR(45) NOT NULL, first_key CHAR(32) NOT NULL, last_key CHAR(32) NOT NULL, usage_text VARCHAR(160), trust_level VARCHAR(64), lifecycle_status VARCHAR(16) NOT NULL,
 version BIGINT NOT NULL CHECK(version>0), created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
 CONSTRAINT fk_ddi_network_vrf FOREIGN KEY(vrf_id) REFERENCES infranexum_ddi.ipam_vrf(id),
 CONSTRAINT fk_ddi_network_vlan FOREIGN KEY(vlan_id) REFERENCES infranexum_ddi.ipam_vlan(id),
 CONSTRAINT fk_ddi_network_parent FOREIGN KEY(parent_network_id) REFERENCES infranexum_ddi.ipam_network(id),
 CONSTRAINT ck_ddi_network_kind CHECK(network_kind IN ('BLOCK','PREFIX','SUBNET')), CONSTRAINT ck_ddi_network_af CHECK(address_family IN (4,6))
);
CREATE INDEX IF NOT EXISTS ix_ddi_network_scope ON infranexum_ddi.ipam_network(organization_id,vrf_id,lifecycle_status);
CREATE INDEX IF NOT EXISTS ix_ddi_network_overlap ON infranexum_ddi.ipam_network(organization_id,vrf_id,address_family,first_key,last_key,lifecycle_status);
CREATE INDEX IF NOT EXISTS ix_ddi_network_parent ON infranexum_ddi.ipam_network(parent_network_id);
CREATE TABLE IF NOT EXISTS infranexum_ddi.ipam_pool (
 id UUID PRIMARY KEY, organization_id UUID NOT NULL, network_id UUID NOT NULL, start_address VARCHAR(45) NOT NULL, end_address VARCHAR(45) NOT NULL,
 allocation_cursor VARCHAR(45) NOT NULL, start_key CHAR(32) NOT NULL, end_key CHAR(32) NOT NULL, name VARCHAR(160) NOT NULL, lifecycle_status VARCHAR(16) NOT NULL, version BIGINT NOT NULL CHECK(version>0), created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
 CONSTRAINT fk_ddi_pool_network FOREIGN KEY(network_id) REFERENCES infranexum_ddi.ipam_network(id)
);
CREATE INDEX IF NOT EXISTS ix_ddi_pool_network ON infranexum_ddi.ipam_pool(network_id,lifecycle_status);
CREATE INDEX IF NOT EXISTS ix_ddi_pool_overlap ON infranexum_ddi.ipam_pool(network_id,start_key,end_key,lifecycle_status);
CREATE TABLE IF NOT EXISTS infranexum_ddi.ipam_address (
 id UUID PRIMARY KEY, organization_id UUID NOT NULL, vrf_id UUID NOT NULL, network_id UUID NOT NULL, pool_id UUID, address_value VARCHAR(45) NOT NULL,
 lifecycle_status VARCHAR(16) NOT NULL, hostname VARCHAR(253), rsot_object_id UUID, dcim_equipment_id UUID, purpose VARCHAR(512), allocation_token VARCHAR(36) NOT NULL,
 version BIGINT NOT NULL CHECK(version>0), created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
 CONSTRAINT fk_ddi_address_vrf FOREIGN KEY(vrf_id) REFERENCES infranexum_ddi.ipam_vrf(id), CONSTRAINT fk_ddi_address_network FOREIGN KEY(network_id) REFERENCES infranexum_ddi.ipam_network(id), CONSTRAINT fk_ddi_address_pool FOREIGN KEY(pool_id) REFERENCES infranexum_ddi.ipam_pool(id),
 CONSTRAINT uq_ddi_address_active UNIQUE(vrf_id,address_value,allocation_token), CONSTRAINT ck_ddi_address_status CHECK(lifecycle_status IN ('ALLOCATED','RESERVED','DEPRECATED','RELEASED'))
);
CREATE INDEX IF NOT EXISTS ix_ddi_address_search ON infranexum_ddi.ipam_address(organization_id,vrf_id,network_id,lifecycle_status);
CREATE TABLE IF NOT EXISTS infranexum_ddi.ipam_command_dedup (idempotency_key VARCHAR(200) PRIMARY KEY,payload_sha256 CHAR(64) NOT NULL,operation_name VARCHAR(64) NOT NULL,result_id UUID NOT NULL,created_at TIMESTAMPTZ NOT NULL);
