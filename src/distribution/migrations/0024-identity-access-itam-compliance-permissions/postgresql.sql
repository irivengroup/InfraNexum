INSERT INTO infranexum_iam.permission(id,organization_id,code,resource_type,action_name,sensitivity,scope_kind,system_defined,active,created_at,updated_at,deleted_at) VALUES
('019ffbda-1070-7001-8001-000000000001',NULL,'itam.warranty.read','itam_warranty','read','NORMAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1071-7002-8002-000000000002',NULL,'itam.warranty.manage','itam_warranty','manage','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1072-7003-8003-000000000003',NULL,'itam.support_coverage.read','itam_support_coverage','read','NORMAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1073-7004-8004-000000000004',NULL,'itam.support_coverage.manage','itam_support_coverage','manage','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1074-7005-8005-000000000005',NULL,'itam.support_catalog.manage','itam_support_catalog','manage','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1075-7006-8006-000000000006',NULL,'itam.license.read','itam_license','read','NORMAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1076-7007-8007-000000000007',NULL,'itam.license.manage','itam_license','manage','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL)
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_iam.role_permission(role_id,permission_id) SELECT '019ffbda-1001-7e80-9ec8-7580467e9a85',id FROM infranexum_iam.permission WHERE organization_id IS NULL AND code IN ('itam.warranty.read','itam.warranty.manage','itam.support_coverage.read','itam.support_coverage.manage','itam.support_catalog.manage','itam.license.read','itam.license.manage') ON CONFLICT DO NOTHING;
