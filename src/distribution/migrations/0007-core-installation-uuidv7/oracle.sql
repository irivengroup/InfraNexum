-- Enforce the UUIDv7 domain-identifier contract for the persisted installation identity.
-- The alpha.0.31 defect existed in the PostgreSQL developer bootstrap only. Oracle therefore
-- fails closed if legacy invalid data is present instead of inventing a replacement identity.
DECLARE
    identity_count NUMBER;
    invalid_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO identity_count FROM core_installation_identity;
    IF identity_count > 1 THEN
        RAISE_APPLICATION_ERROR(-20071,
            'cannot enforce UUIDv7 installation identity: more than one identity exists');
    END IF;

    SELECT COUNT(*) INTO invalid_count
      FROM core_installation_identity
     WHERE NOT REGEXP_LIKE(
         installation_id,
         '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$'
     );
    IF invalid_count <> 0 THEN
        RAISE_APPLICATION_ERROR(-20072,
            'non-UUIDv7 installation identity requires explicit offline repair before migration 0007');
    END IF;
END;
/

BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE core_installation_identity ADD CONSTRAINT ck_core_install_uuidv7 '
        || 'CHECK (REGEXP_LIKE(installation_id, '
        || '''^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$''))';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -2264 THEN
            RAISE;
        END IF;
END;
/
