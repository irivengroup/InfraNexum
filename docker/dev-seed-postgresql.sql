-- InfraNexum developer-only representative data.
-- This file is deliberately outside src/distribution/migrations: it is not a
-- product migration and is executed only by docker/dev-compose.{ps1,sh}.
\set ON_ERROR_STOP on

BEGIN;
SELECT pg_advisory_xact_lock(hashtext('infranexum-dev-seed-v1'));

DO $$
BEGIN
  IF to_regclass('infranexum_org.organization') IS NULL
     OR to_regclass('infranexum_iam.iam_user') IS NULL
     OR to_regclass('infranexum_rsot.canonical_object') IS NULL
     OR to_regclass('infranexum_itam.asset') IS NULL
     OR to_regclass('infranexum_dcim.facility_node') IS NULL
     OR to_regclass('infranexum_ddi.ipam_network') IS NULL
     OR to_regclass('infranexum_integrations.connector_inbox') IS NULL THEN
    RAISE EXCEPTION 'InfraNexum developer seed requires the complete migration catalogue to be applied first';
  END IF;
END $$;

-- Organization hierarchy used by UI entity selectors and scope filtering.
INSERT INTO infranexum_org.organization(
  id,code,display_name,legal_name,country_code,default_language,timezone,currency,parent_organization_id,status,version,created_at,updated_at
) VALUES
('019f2000-0001-7000-8000-000000000001','DEMO-CORP','InfraNexum Demo Corp','InfraNexum Demo Corporation','FR','fr-FR','Europe/Paris','EUR',NULL,'ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;

INSERT INTO infranexum_org.subdivision(
  id,organization_id,code,display_name,description_text,type_name,status,parent_subdivision_id,version,created_at,updated_at,deleted_at
) VALUES
('019f2000-0002-7000-8000-000000000001','019f2000-0001-7000-8000-000000000001','INFRA','Infrastructure','Developer fixture for infrastructure operations','DEPARTMENT','ACTIVE',NULL,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019f2000-0002-7000-8000-000000000002','019f2000-0001-7000-8000-000000000001','NETOPS','Network Operations','Developer fixture for DDI and network operations','FUNCTION','ACTIVE','019f2000-0002-7000-8000-000000000001',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL)
ON CONFLICT DO NOTHING;

-- Non-local IAM identities: no real or reusable credentials are seeded.
INSERT INTO infranexum_iam.iam_user(id,login,email,display_name,status,created_at,updated_at,deleted_at) VALUES
('019f2000-0010-7000-8000-000000000001','demo.operator','operator@demo.invalid','Demo Operator','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019f2000-0010-7000-8000-000000000002','demo.auditor','auditor@demo.invalid','Demo Auditor','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019f2000-0010-7000-8000-000000000003','demo.suspended','suspended@demo.invalid','Demo Suspended User','SUSPENDED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL)
ON CONFLICT DO NOTHING;

INSERT INTO infranexum_iam.user_membership(id,user_id,organization_id,subdivision_id,effective_from,effective_to,revoked_at) VALUES
('019f2000-0011-7000-8000-000000000001','019f2000-0010-7000-8000-000000000001','019f2000-0001-7000-8000-000000000001','019f2000-0002-7000-8000-000000000001',CURRENT_TIMESTAMP,NULL,NULL),
('019f2000-0011-7000-8000-000000000002','019f2000-0010-7000-8000-000000000002','019f2000-0001-7000-8000-000000000001',NULL,CURRENT_TIMESTAMP,NULL,NULL)
ON CONFLICT DO NOTHING;

INSERT INTO infranexum_iam.iam_group(id,organization_id,code,display_name,system_group,created_at,updated_at,deleted_at) VALUES
('019f2000-0012-7000-8000-000000000001','019f2000-0001-7000-8000-000000000001','demo.infrastructure-operators','Demo Infrastructure Operators',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL)
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_iam.group_user_member(organization_id,group_id,user_id,created_at) VALUES
('019f2000-0001-7000-8000-000000000001','019f2000-0012-7000-8000-000000000001','019f2000-0010-7000-8000-000000000001',CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;

-- Canonical RSOT assets underpin ITAM/DCIM/DDI examples.
INSERT INTO infranexum_rsot.canonical_object(
 id,object_type,version,organization_id,schema_version,status,status_reason,effective_from,effective_until,archived_at,archived_by,created_at,updated_at
) VALUES
('019f2000-0020-7000-8000-000000000001','infrastructure.server',1,'019f2000-0001-7000-8000-000000000001','1.0.0','VALIDATED','Developer fixture',CURRENT_TIMESTAMP,NULL,NULL,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('019f2000-0020-7000-8000-000000000002','infrastructure.server',1,'019f2000-0001-7000-8000-000000000001','1.0.0','RECONCILED','Developer fixture',CURRENT_TIMESTAMP,NULL,NULL,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('019f2000-0020-7000-8000-000000000003','network.address',1,'019f2000-0001-7000-8000-000000000001','1.0.0','VALIDATED','Developer fixture',CURRENT_TIMESTAMP,NULL,NULL,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;

-- Schema registry sample remains DRAFT to avoid pretending that a mock schema is published.
INSERT INTO infranexum_core.schema_registry_entry(
 id,schema_key,schema_kind,owner_code,schema_version,status,definition_json,checksum_sha256,revision,effective_at,created_at,updated_at,published_at,deprecated_at,sunset_at,deprecation_reason,compatibility_evidence,breaking_approval_ref
) VALUES (
 '019f2000-0021-7000-8000-000000000001','demo.infrastructure.server','RSOT_CANONICAL','rsot.demo','1.0.0','DRAFT',
 '{"type":"object","properties":{"hostname":{"type":"string"}}}'::jsonb,
 '8bca9d73e8111b7dec12336f458c63490c6cfbc0d143e89e26eb14ee6261472e',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL,NULL,NULL,NULL,NULL,NULL
) ON CONFLICT DO NOTHING;

-- ITAM partners and asset lifecycle fixtures.
INSERT INTO infranexum_itam.partner(
 id,governing_organization_id,governing_subdivision_id,code,legal_name,legal_name_normalized,display_name,country_code,authorization_status,valid_from,valid_until,official_website,support_portal,version,created_at,updated_at,created_by,updated_by,last_reason
) VALUES
('019f2000-0030-7000-8000-000000000001','019f2000-0001-7000-8000-000000000001','019f2000-0002-7000-8000-000000000001','NEXUM-HW','Nexum Hardware Labs','nexum hardware labs','Nexum Hardware Labs','FR','ACTIVE',CURRENT_DATE,NULL,'https://hardware.demo.invalid','https://support.hardware.demo.invalid',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'019f2000-0010-7000-8000-000000000001','019f2000-0010-7000-8000-000000000001','Developer fixture'),
('019f2000-0030-7000-8000-000000000002','019f2000-0001-7000-8000-000000000001',NULL,'NEXUM-SW','Nexum Software Works','nexum software works','Nexum Software Works','FR','ACTIVE',CURRENT_DATE,NULL,'https://software.demo.invalid',NULL,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'019f2000-0010-7000-8000-000000000001','019f2000-0010-7000-8000-000000000001','Developer fixture')
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_itam.partner_role(partner_id,role_code) VALUES
('019f2000-0030-7000-8000-000000000001','MANUFACTURER'),
('019f2000-0030-7000-8000-000000000001','SUPPLIER'),
('019f2000-0030-7000-8000-000000000002','SOFTWARE_PUBLISHER')
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_itam.partner_contact(partner_id,position_no,contact_type,contact_name,email_address,phone_number,contact_uri) VALUES
('019f2000-0030-7000-8000-000000000001',1,'SUPPORT','Demo Support','support@hardware.demo.invalid',NULL,'https://support.hardware.demo.invalid')
ON CONFLICT DO NOTHING;

INSERT INTO infranexum_itam.asset(
 id,rsot_object_id,asset_type,owning_organization_id,owning_subdivision_id,acquisition_date,acquisition_value,currency_code,acquired_from_partner_id,lifecycle_status,current_custodian_kind,current_custodian_id,version,created_at,updated_at,created_by,updated_by,last_reason
) VALUES
('019f2000-0031-7000-8000-000000000001','019f2000-0020-7000-8000-000000000001','HARDWARE','019f2000-0001-7000-8000-000000000001','019f2000-0002-7000-8000-000000000001',CURRENT_DATE-180,6200.00,'EUR','019f2000-0030-7000-8000-000000000001','DEPLOYED','SUBDIVISION','019f2000-0002-7000-8000-000000000001',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'019f2000-0010-7000-8000-000000000001','019f2000-0010-7000-8000-000000000001','Developer fixture'),
('019f2000-0031-7000-8000-000000000002','019f2000-0020-7000-8000-000000000002','HARDWARE','019f2000-0001-7000-8000-000000000001','019f2000-0002-7000-8000-000000000001',CURRENT_DATE-120,5900.00,'EUR','019f2000-0030-7000-8000-000000000001','DEPLOYED','SUBDIVISION','019f2000-0002-7000-8000-000000000001',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'019f2000-0010-7000-8000-000000000001','019f2000-0010-7000-8000-000000000001','Developer fixture')
ON CONFLICT DO NOTHING;

-- DCIM facility hierarchy, rack, model, equipment, ports and cabling.
INSERT INTO infranexum_dcim.facility_node(
 id,facility_kind,organization_id,subdivision_id,parent_id,scope_id,code,display_name,lifecycle_status,address_line_1,address_line_2,postal_code,city,country_code,timezone,latitude,longitude,floor_count,level_number,area_m2,level_height_m,capacity_kw,access_restriction,zone_type,description,version,created_at,updated_at,created_by,updated_by,last_reason
) VALUES
('019f2000-0040-7000-8000-000000000001','SITE','019f2000-0001-7000-8000-000000000001','019f2000-0002-7000-8000-000000000001',NULL,'019f2000-0001-7000-8000-000000000001','PARIS-DC1','Paris Demo Datacenter','ACTIVE','10 Demo Avenue',NULL,'75001','Paris','FR','Europe/Paris',48.8566000,2.3522000,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'Developer fixture',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'019f2000-0010-7000-8000-000000000001','019f2000-0010-7000-8000-000000000001','Developer fixture'),
('019f2000-0040-7000-8000-000000000002','BUILDING','019f2000-0001-7000-8000-000000000001','019f2000-0002-7000-8000-000000000001','019f2000-0040-7000-8000-000000000001','019f2000-0040-7000-8000-000000000001','BLD-A','Building A','ACTIVE',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,3,NULL,900.000,NULL,NULL,NULL,NULL,'Developer fixture',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'019f2000-0010-7000-8000-000000000001','019f2000-0010-7000-8000-000000000001','Developer fixture'),
('019f2000-0040-7000-8000-000000000003','FLOOR','019f2000-0001-7000-8000-000000000001','019f2000-0002-7000-8000-000000000001','019f2000-0040-7000-8000-000000000002','019f2000-0040-7000-8000-000000000002','FLR-01','Floor 1','ACTIVE',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,1,300.000,3.200,120.000,NULL,NULL,'Developer fixture',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'019f2000-0010-7000-8000-000000000001','019f2000-0010-7000-8000-000000000001','Developer fixture'),
('019f2000-0040-7000-8000-000000000004','ROOM','019f2000-0001-7000-8000-000000000001','019f2000-0002-7000-8000-000000000001','019f2000-0040-7000-8000-000000000003','019f2000-0040-7000-8000-000000000003','ROOM-A','Server Room A','ACTIVE',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,120.000,NULL,80.000,'secure',NULL,'Developer fixture',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'019f2000-0010-7000-8000-000000000001','019f2000-0010-7000-8000-000000000001','Developer fixture'),
('019f2000-0040-7000-8000-000000000005','ZONE','019f2000-0001-7000-8000-000000000001','019f2000-0002-7000-8000-000000000001','019f2000-0040-7000-8000-000000000004','019f2000-0040-7000-8000-000000000004','ZONE-COLD','Cold Aisle','ACTIVE',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'cooling','Developer fixture',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'019f2000-0010-7000-8000-000000000001','019f2000-0010-7000-8000-000000000001','Developer fixture')
ON CONFLICT DO NOTHING;

INSERT INTO infranexum_dcim.equipment_model(
 id,organization_id,manufacturer_partner_id,code,display_name,form_factor,rack_units,width_mm,depth_mm,weight_kg,lifecycle_status,description,version,created_at,updated_at,created_by,updated_by,last_reason
) VALUES ('019f2000-0041-7000-8000-000000000001','019f2000-0001-7000-8000-000000000001','019f2000-0030-7000-8000-000000000001','NX-SRV-2U','Nexum Demo Server 2U','RACK',2,482,700,18.500,'ACTIVE','Developer fixture',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'019f2000-0010-7000-8000-000000000001','019f2000-0010-7000-8000-000000000001','Developer fixture')
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_dcim.model_port_template(model_id,ordinal,name_prefix,port_count,port_kind,media,connector) VALUES
('019f2000-0041-7000-8000-000000000001',1,'eth',2,'NETWORK','copper','RJ45')
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_dcim.rack(
 id,organization_id,subdivision_id,room_id,code,display_name,height_u,width_mm,depth_mm,lifecycle_status,version,created_at,updated_at,created_by,updated_by,last_reason
) VALUES ('019f2000-0042-7000-8000-000000000001','019f2000-0001-7000-8000-000000000001','019f2000-0002-7000-8000-000000000001','019f2000-0040-7000-8000-000000000004','RACK-A01','Rack A01',42,600,1000,'ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'019f2000-0010-7000-8000-000000000001','019f2000-0010-7000-8000-000000000001','Developer fixture')
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_dcim.equipment(
 id,organization_id,subdivision_id,rack_id,model_id,rsot_object_id,itam_asset_id,serial_number,asset_tag,start_u,face,lifecycle_status,version,created_at,updated_at,created_by,updated_by,last_reason
) VALUES
('019f2000-0043-7000-8000-000000000001','019f2000-0001-7000-8000-000000000001','019f2000-0002-7000-8000-000000000001','019f2000-0042-7000-8000-000000000001','019f2000-0041-7000-8000-000000000001','019f2000-0020-7000-8000-000000000001','019f2000-0031-7000-8000-000000000001','DEMO-SN-0001','DEMO-ASSET-0001',1,'front','ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'019f2000-0010-7000-8000-000000000001','019f2000-0010-7000-8000-000000000001','Developer fixture'),
('019f2000-0043-7000-8000-000000000002','019f2000-0001-7000-8000-000000000001','019f2000-0002-7000-8000-000000000001','019f2000-0042-7000-8000-000000000001','019f2000-0041-7000-8000-000000000001','019f2000-0020-7000-8000-000000000002','019f2000-0031-7000-8000-000000000002','DEMO-SN-0002','DEMO-ASSET-0002',3,'front','ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'019f2000-0010-7000-8000-000000000001','019f2000-0010-7000-8000-000000000001','Developer fixture')
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_dcim.physical_port(id,organization_id,equipment_id,port_name,port_kind,media,connector) VALUES
('019f2000-0044-7000-8000-000000000001','019f2000-0001-7000-8000-000000000001','019f2000-0043-7000-8000-000000000001','eth0','NETWORK','copper','RJ45'),
('019f2000-0044-7000-8000-000000000002','019f2000-0001-7000-8000-000000000001','019f2000-0043-7000-8000-000000000002','eth0','NETWORK','copper','RJ45')
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_dcim.cable_connection(
 id,organization_id,subdivision_id,port_a_id,port_b_id,label,media,connector,lifecycle_status,version,created_at,updated_at,created_by,updated_by,last_reason
) VALUES ('019f2000-0045-7000-8000-000000000001','019f2000-0001-7000-8000-000000000001','019f2000-0002-7000-8000-000000000001','019f2000-0044-7000-8000-000000000001','019f2000-0044-7000-8000-000000000002','DEMO-CABLE-01','copper','RJ45','ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'019f2000-0010-7000-8000-000000000001','019f2000-0010-7000-8000-000000000001','Developer fixture')
ON CONFLICT DO NOTHING;

-- DDI/IPAM network inventory linked back to the DCIM/RSOT fixtures.
INSERT INTO infranexum_ddi.ipam_vrf(id,organization_id,code,display_name,route_distinguisher,lifecycle_status,version,created_at,updated_at) VALUES
('019f2000-0050-7000-8000-000000000001','019f2000-0001-7000-8000-000000000001','DEMO-VRF','Demo VRF','65000:100','ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_ddi.ipam_vlan(id,organization_id,site_id,vlan_id,vni,name,lifecycle_status,version,created_at,updated_at) VALUES
('019f2000-0051-7000-8000-000000000001','019f2000-0001-7000-8000-000000000001','019f2000-0040-7000-8000-000000000001',120,NULL,'DEMO-SERVERS','ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_ddi.ipam_network(
 id,organization_id,subdivision_id,site_id,vrf_id,vlan_id,parent_network_id,network_kind,cidr_value,address_family,prefix_length,first_address,last_address,first_key,last_key,usage_text,trust_level,lifecycle_status,version,created_at,updated_at
) VALUES ('019f2000-0052-7000-8000-000000000001','019f2000-0001-7000-8000-000000000001','019f2000-0002-7000-8000-000000000002','019f2000-0040-7000-8000-000000000001','019f2000-0050-7000-8000-000000000001','019f2000-0051-7000-8000-000000000001',NULL,'SUBNET','10.20.0.0/24',4,24,'10.20.0.0','10.20.0.255','0000000000000000000000000a140000','0000000000000000000000000a1400ff','Demo server network','trusted','ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_ddi.ipam_pool(id,organization_id,network_id,start_address,end_address,allocation_cursor,start_key,end_key,name,lifecycle_status,version,created_at,updated_at) VALUES
('019f2000-0053-7000-8000-000000000001','019f2000-0001-7000-8000-000000000001','019f2000-0052-7000-8000-000000000001','10.20.0.10','10.20.0.100','10.20.0.11','0000000000000000000000000a14000a','0000000000000000000000000a140064','Demo static pool','ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_ddi.ipam_address(
 id,organization_id,vrf_id,network_id,pool_id,address_value,lifecycle_status,hostname,rsot_object_id,dcim_equipment_id,purpose,allocation_token,version,created_at,updated_at
) VALUES
('019f2000-0054-7000-8000-000000000001','019f2000-0001-7000-8000-000000000001','019f2000-0050-7000-8000-000000000001','019f2000-0052-7000-8000-000000000001','019f2000-0053-7000-8000-000000000001','10.20.0.10','ALLOCATED','srv-demo-01.demo.invalid','019f2000-0020-7000-8000-000000000001','019f2000-0043-7000-8000-000000000001','Primary management address','019f2000-0054-7000-8000-000000000101',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('019f2000-0054-7000-8000-000000000002','019f2000-0001-7000-8000-000000000001','019f2000-0050-7000-8000-000000000001','019f2000-0052-7000-8000-000000000001','019f2000-0053-7000-8000-000000000001','10.20.0.11','RESERVED','srv-demo-02.demo.invalid','019f2000-0020-7000-8000-000000000002','019f2000-0043-7000-8000-000000000002','Reserved management address','019f2000-0054-7000-8000-000000000102',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;

-- Connector inbox exercises list/replay/status screens without external systems.
INSERT INTO infranexum_integrations.connector_runtime_state(connector_key,consecutive_dead_letters,suspended_until,last_success_at,last_failure_at,updated_at) VALUES
('demo.inventory',0,NULL,CURRENT_TIMESTAMP,NULL,CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_integrations.connector_inbox(
 delivery_id,connector_key,external_delivery_id,payload_json,payload_sha256,status,attempts,received_at,available_at,lease_owner,lease_until,processed_at,last_failure,replay_count,last_replayed_at,created_at,updated_at,payload_raw
) VALUES (
 '019f2000-0060-7000-8000-000000000001','demo.inventory','demo-delivery-001',
 '{"kind":"demo","hostname":"srv-demo-01"}'::jsonb,
 '6d9cca7751cc6eb6b74f8a4da73ac6d95481bb0e73098847aa8ffdf0fa2c57c1',
 'PENDING',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL,NULL,NULL,NULL,0,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
 '{"kind":"demo","hostname":"srv-demo-01"}'
) ON CONFLICT DO NOTHING;

COMMIT;

-- Human-readable summary consumed by the dev wrappers after a successful seed.
SELECT json_build_object(
  'organizations', (SELECT count(*) FROM infranexum_org.organization WHERE code='DEMO-CORP'),
  'iamUsers',      (SELECT count(*) FROM infranexum_iam.iam_user WHERE login LIKE 'demo.%'),
  'rsotObjects',   (SELECT count(*) FROM infranexum_rsot.canonical_object WHERE id::text LIKE '019f2000-0020-%'),
  'itamAssets',    (SELECT count(*) FROM infranexum_itam.asset WHERE id::text LIKE '019f2000-0031-%'),
  'dcimNodes',     (SELECT count(*) FROM infranexum_dcim.facility_node WHERE id::text LIKE '019f2000-0040-%'),
  'ddiAddresses',  (SELECT count(*) FROM infranexum_ddi.ipam_address WHERE id::text LIKE '019f2000-0054-%'),
  'connectorInbox',(SELECT count(*) FROM infranexum_integrations.connector_inbox WHERE connector_key='demo.inventory')
)::text AS infranexum_developer_seed;
