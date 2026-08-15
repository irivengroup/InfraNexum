INSERT INTO infranexum_iam.permission(id,organization_id,code,resource_type,action_name,sensitivity,scope_kind,system_defined,active,created_at,updated_at,deleted_at) VALUES
('019ffbda-1301-7111-8101-000000000001',NULL,'dcim.model.read','dcim_model','read','NORMAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1302-7222-8202-000000000002',NULL,'dcim.model.create','dcim_model','create','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1303-7333-8303-000000000003',NULL,'dcim.model.update','dcim_model','update','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1304-7444-8404-000000000004',NULL,'dcim.model.archive','dcim_model','archive','CRITICAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1305-7555-8505-000000000005',NULL,'dcim.rack.read','dcim_rack','read','NORMAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1306-7666-8606-000000000006',NULL,'dcim.rack.create','dcim_rack','create','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1307-7777-8707-000000000007',NULL,'dcim.rack.update','dcim_rack','update','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1308-7888-8808-000000000008',NULL,'dcim.rack.decommission','dcim_rack','decommission','CRITICAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1309-7999-8909-000000000009',NULL,'dcim.equipment.read','dcim_equipment','read','NORMAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1310-7aaa-8000-000000000010',NULL,'dcim.equipment.create','dcim_equipment','create','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1311-7bbb-8101-000000000011',NULL,'dcim.equipment.update','dcim_equipment','update','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1312-7ccc-8202-000000000012',NULL,'dcim.equipment.move','dcim_equipment','move','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1313-7ddd-8303-000000000013',NULL,'dcim.equipment.decommission','dcim_equipment','decommission','CRITICAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1314-7eee-8404-000000000014',NULL,'dcim.port.read','dcim_port','read','NORMAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1315-7fff-8505-000000000015',NULL,'dcim.cable.read','dcim_cable','read','NORMAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1316-7000-8606-000000000016',NULL,'dcim.cable.create','dcim_cable','create','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1317-7111-8707-000000000017',NULL,'dcim.cable.disconnect','dcim_cable','disconnect','CRITICAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL)
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_iam.role_permission(role_id,permission_id) SELECT '019ffbda-1001-7e80-9ec8-7580467e9a85',id FROM infranexum_iam.permission WHERE organization_id IS NULL AND code IN ('dcim.model.read','dcim.model.create','dcim.model.update','dcim.model.archive','dcim.rack.read','dcim.rack.create','dcim.rack.update','dcim.rack.decommission','dcim.equipment.read','dcim.equipment.create','dcim.equipment.update','dcim.equipment.move','dcim.equipment.decommission','dcim.port.read','dcim.cable.read','dcim.cable.create','dcim.cable.disconnect') ON CONFLICT DO NOTHING;
