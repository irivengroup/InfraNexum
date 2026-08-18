INSERT INTO infranexum_iam.permission(id,organization_id,code,resource_type,action_name,sensitivity,scope_kind,system_defined,active,created_at,updated_at,deleted_at) VALUES
('019ffbda-1701-7111-8101-000000000001',NULL,'integrations.sync.read','integration_sync','read','NORMAL','PLATFORM',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1702-7222-8202-000000000002',NULL,'integrations.sync.execute','integration_sync','execute','CRITICAL','PLATFORM',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1703-7333-8303-000000000003',NULL,'integrations.sync.compensate','integration_sync','compensate','CRITICAL','PLATFORM',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL)
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_iam.role_permission(role_id,permission_id)
SELECT '019ffbda-1001-7e80-9ec8-7580467e9a85',id FROM infranexum_iam.permission
WHERE organization_id IS NULL AND code IN ('integrations.sync.read','integrations.sync.execute','integrations.sync.compensate')
ON CONFLICT DO NOTHING;
