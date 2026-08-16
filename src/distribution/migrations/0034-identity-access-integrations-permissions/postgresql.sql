INSERT INTO infranexum_iam.permission(id,organization_id,code,resource_type,action_name,sensitivity,scope_kind,system_defined,active,created_at,updated_at,deleted_at) VALUES
('019ffbda-1501-7111-8101-000000000001',NULL,'integrations.connector.read','integration_connector','read','NORMAL','PLATFORM',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1502-7222-8202-000000000002',NULL,'integrations.connector.resume','integration_connector','resume','CRITICAL','PLATFORM',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1503-7333-8303-000000000003',NULL,'integrations.dlq.read','integration_dlq','read','ELEVATED','PLATFORM',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1504-7444-8404-000000000004',NULL,'integrations.dlq.replay','integration_dlq','replay','CRITICAL','PLATFORM',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL)
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_iam.role_permission(role_id,permission_id)
SELECT '019ffbda-1001-7e80-9ec8-7580467e9a85',id FROM infranexum_iam.permission
WHERE organization_id IS NULL AND code IN ('integrations.connector.read','integrations.connector.resume','integrations.dlq.read','integrations.dlq.replay')
ON CONFLICT DO NOTHING;
