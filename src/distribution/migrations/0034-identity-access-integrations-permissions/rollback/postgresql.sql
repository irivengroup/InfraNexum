DELETE FROM infranexum_iam.role_permission WHERE permission_id IN (SELECT id FROM infranexum_iam.permission WHERE organization_id IS NULL AND code LIKE 'integrations.%');
DELETE FROM infranexum_iam.permission WHERE organization_id IS NULL AND code LIKE 'integrations.%';
