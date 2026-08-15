INSERT INTO infranexum_iam.permission(id,organization_id,code,resource_type,action_name,sensitivity,scope_kind,system_defined,active,created_at,updated_at,deleted_at) VALUES
('019ffbda-1050-7001-8001-000000000001',NULL,'itam.partner.read','itam_partner','read','NORMAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1051-7002-8002-000000000002',NULL,'itam.partner.create','itam_partner','create','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1052-7003-8003-000000000003',NULL,'itam.partner.update','itam_partner','update','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1053-7004-8004-000000000004',NULL,'itam.partner.approve','itam_partner','approve','CRITICAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1054-7005-8005-000000000005',NULL,'itam.partner.suspend','itam_partner','suspend','CRITICAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1055-7006-8006-000000000006',NULL,'itam.audit.read','itam_partner','audit','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL)
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_iam.role_permission(role_id,permission_id)
SELECT '019ffbda-1001-7e80-9ec8-7580467e9a85',id FROM infranexum_iam.permission
WHERE organization_id IS NULL AND code IN ('itam.partner.read','itam.partner.create','itam.partner.update','itam.partner.approve','itam.partner.suspend','itam.audit.read')
ON CONFLICT DO NOTHING;
