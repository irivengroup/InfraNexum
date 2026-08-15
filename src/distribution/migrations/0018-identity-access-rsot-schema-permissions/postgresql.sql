INSERT INTO infranexum_iam.permission(id,organization_id,code,resource_type,action_name,sensitivity,scope_kind,system_defined,active,created_at,updated_at,deleted_at) VALUES
('019ffbda-1040-7001-8001-000000000001',NULL,'rsot.schema.create','rsot_schema','create','ELEVATED','PLATFORM',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1041-7002-8002-000000000002',NULL,'rsot.schema.read','rsot_schema','read','NORMAL','PLATFORM',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1042-7003-8003-000000000003',NULL,'rsot.schema.update','rsot_schema','update','ELEVATED','PLATFORM',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1043-7004-8004-000000000004',NULL,'rsot.schema.deprecate','rsot_schema','deprecate','CRITICAL','PLATFORM',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1044-7005-8005-000000000005',NULL,'rsot.schema.publish','rsot_schema','publish','CRITICAL','PLATFORM',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL),
('019ffbda-1045-7006-8006-000000000006',NULL,'rsot.audit','rsot_schema','audit','ELEVATED','PLATFORM',TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL)
ON CONFLICT DO NOTHING;
INSERT INTO infranexum_iam.role_permission(role_id,permission_id)
SELECT '019ffbda-1001-7e80-9ec8-7580467e9a85',id FROM infranexum_iam.permission
WHERE organization_id IS NULL AND code IN ('rsot.schema.create','rsot.schema.read','rsot.schema.update','rsot.schema.deprecate','rsot.schema.publish','rsot.audit')
ON CONFLICT DO NOTHING;
