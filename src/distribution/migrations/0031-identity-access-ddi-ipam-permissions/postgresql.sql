INSERT INTO infranexum_iam.permission(id,organization_id,code,resource_type,action_name,sensitivity,scope_kind,system_defined,active,created_at,updated_at,deleted_at) VALUES
('019ffbda-1401-7111-8101-000000000001',NULL,'ddi.ipam.read','ddi_ipam','read','NORMAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1402-7222-8202-000000000002',NULL,'ddi.ipam.vrf.create','ddi_vrf','create','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1403-7333-8303-000000000003',NULL,'ddi.ipam.vrf.update','ddi_vrf','update','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1404-7444-8404-000000000004',NULL,'ddi.ipam.vlan.create','ddi_vlan','create','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1405-7555-8505-000000000005',NULL,'ddi.ipam.vlan.update','ddi_vlan','update','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1406-7666-8606-000000000006',NULL,'ddi.ipam.network.create','ddi_network','create','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1407-7777-8707-000000000007',NULL,'ddi.ipam.network.update','ddi_network','update','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1408-7888-8808-000000000008',NULL,'ddi.ipam.pool.create','ddi_pool','create','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1409-7999-8909-000000000009',NULL,'ddi.ipam.address.read','ddi_address','read','NORMAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1410-7aaa-8000-000000000010',NULL,'ddi.ipam.address.allocate','ddi_address','allocate','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1411-7bbb-8101-000000000011',NULL,'ddi.ipam.address.release','ddi_address','release','CRITICAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1412-7ccc-8202-000000000012',NULL,'ddi.ipam.audit.read','ddi_ipam_audit','read','NORMAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL)
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_iam.role_permission(role_id,permission_id) SELECT '019ffbda-1001-7e80-9ec8-7580467e9a85',id FROM infranexum_iam.permission WHERE organization_id IS NULL AND code IN ('ddi.ipam.read','ddi.ipam.vrf.create','ddi.ipam.vrf.update','ddi.ipam.vlan.create','ddi.ipam.vlan.update','ddi.ipam.network.create','ddi.ipam.network.update','ddi.ipam.pool.create','ddi.ipam.address.read','ddi.ipam.address.allocate','ddi.ipam.address.release','ddi.ipam.audit.read') ON CONFLICT DO NOTHING;
