DELETE FROM INFRANEXUM_IAM_ROLE_PERMISSION WHERE PERMISSION_ID IN (SELECT ID FROM INFRANEXUM_IAM_PERMISSION WHERE CODE IN ('integrations.sync.read','integrations.sync.execute','integrations.sync.compensate'));
DELETE FROM INFRANEXUM_IAM_PERMISSION WHERE CODE IN ('integrations.sync.read','integrations.sync.execute','integrations.sync.compensate');
