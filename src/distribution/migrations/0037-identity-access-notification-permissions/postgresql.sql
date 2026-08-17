INSERT INTO infranexum_iam.permission(id,organization_id,code,resource_type,action_name,sensitivity,scope_kind,system_defined,active,created_at,updated_at,deleted_at) VALUES
('019ffbda-1601-7111-8101-000000000001',NULL,'integrations.notification.read','integration_notification','read','NORMAL','PLATFORM',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1602-7222-8202-000000000002',NULL,'integrations.notification.publish','integration_notification','publish','ELEVATED','PLATFORM',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1603-7333-8303-000000000003',NULL,'integrations.notification.replay','integration_notification','replay','CRITICAL','PLATFORM',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1604-7444-8404-000000000004',NULL,'integrations.notification.resume','integration_notification','resume','CRITICAL','PLATFORM',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL)
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_iam.role_permission(role_id,permission_id)
SELECT '019ffbda-1001-7e80-9ec8-7580467e9a85',id FROM infranexum_iam.permission
WHERE organization_id IS NULL AND code IN ('integrations.notification.read','integrations.notification.publish','integrations.notification.replay','integrations.notification.resume')
ON CONFLICT DO NOTHING;
