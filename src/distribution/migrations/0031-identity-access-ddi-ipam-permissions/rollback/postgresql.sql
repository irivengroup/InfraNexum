DELETE FROM infranexum_iam.role_permission WHERE permission_id IN (SELECT id FROM infranexum_iam.permission WHERE organization_id IS NULL AND code LIKE 'ddi.ipam.%');
DELETE FROM infranexum_iam.permission WHERE organization_id IS NULL AND code LIKE 'ddi.ipam.%';
