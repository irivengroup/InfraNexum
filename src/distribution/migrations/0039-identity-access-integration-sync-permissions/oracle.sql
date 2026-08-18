BEGIN
  FOR r IN (
    SELECT '019ffbda-1701-7111-8101-000000000001' id, 'integrations.sync.read' code, 'integration_sync' resource_type, 'read' action_name, 'NORMAL' sensitivity FROM dual UNION ALL
    SELECT '019ffbda-1702-7222-8202-000000000002', 'integrations.sync.execute', 'integration_sync', 'execute', 'CRITICAL' FROM dual UNION ALL
    SELECT '019ffbda-1703-7333-8303-000000000003', 'integrations.sync.compensate', 'integration_sync', 'compensate', 'CRITICAL' FROM dual
  ) LOOP
    BEGIN
      INSERT INTO INFRANEXUM_IAM_PERMISSION(ID,ORGANIZATION_ID,CODE,RESOURCE_TYPE,ACTION_NAME,SENSITIVITY,SCOPE_KIND,SYSTEM_DEFINED,ACTIVE,CREATED_AT,UPDATED_AT,DELETED_AT)
      VALUES(r.id,NULL,r.code,r.resource_type,r.action_name,r.sensitivity,'PLATFORM',1,1,SYSTIMESTAMP,SYSTIMESTAMP,NULL);
    EXCEPTION WHEN DUP_VAL_ON_INDEX THEN NULL;
    END;
  END LOOP;
  FOR r IN (SELECT ID FROM INFRANEXUM_IAM_PERMISSION WHERE ORGANIZATION_ID IS NULL AND CODE IN ('integrations.sync.read','integrations.sync.execute','integrations.sync.compensate')) LOOP
    BEGIN
      INSERT INTO INFRANEXUM_IAM_ROLE_PERMISSION(ROLE_ID,PERMISSION_ID) VALUES('019ffbda-1001-7e80-9ec8-7580467e9a85',r.ID);
    EXCEPTION WHEN DUP_VAL_ON_INDEX THEN NULL;
    END;
  END LOOP;
END;
/
