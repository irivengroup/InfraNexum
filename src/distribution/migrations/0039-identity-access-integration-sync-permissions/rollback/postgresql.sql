DELETE FROM infranexum_iam.role_permission WHERE permission_id IN (SELECT id FROM infranexum_iam.permission WHERE code IN ('integrations.sync.read','integrations.sync.execute','integrations.sync.compensate'));
DELETE FROM infranexum_iam.permission WHERE code IN ('integrations.sync.read','integrations.sync.execute','integrations.sync.compensate');
