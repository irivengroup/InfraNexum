-- Removing the constraint is reversible; identity values are intentionally preserved.
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE core_installation_identity DROP CONSTRAINT ck_core_install_uuidv7';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -2443 THEN
            RAISE;
        END IF;
END;
/
