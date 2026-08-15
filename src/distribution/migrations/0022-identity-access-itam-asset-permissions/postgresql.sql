INSERT INTO infranexum_iam.permission(id,organization_id,code,resource_type,action_name,sensitivity,scope_kind,system_defined,active,created_at,updated_at,deleted_at) VALUES
('019ffbda-1060-7001-8001-000000000001',NULL,'itam.asset.read','itam_asset','read','NORMAL','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1061-7002-8002-000000000002',NULL,'itam.asset.create','itam_asset','create','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1062-7003-8003-000000000003',NULL,'itam.asset.update','itam_asset','update','ELEVATED','ORGANIZATION',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL)
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_iam.role_permission(role_id,permission_id)
SELECT '019ffbda-1001-7e80-9ec8-7580467e9a85',id FROM infranexum_iam.permission
WHERE organization_id IS NULL AND code IN ('itam.asset.read','itam.asset.create','itam.asset.update')
ON CONFLICT DO NOTHING;
