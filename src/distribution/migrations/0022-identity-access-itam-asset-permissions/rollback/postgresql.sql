DELETE FROM infranexum_iam.role_permission WHERE permission_id IN (SELECT id FROM infranexum_iam.permission WHERE organization_id IS NULL AND code IN ('itam.asset.read','itam.asset.create','itam.asset.update'));
DELETE FROM infranexum_iam.permission WHERE organization_id IS NULL AND code IN ('itam.asset.read','itam.asset.create','itam.asset.update');
