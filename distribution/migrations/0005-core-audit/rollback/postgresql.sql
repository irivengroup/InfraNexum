DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM infranexum_core.audit_entry LIMIT 1)
       OR EXISTS (SELECT 1 FROM infranexum_core.audit_purge_tombstone LIMIT 1) THEN
        RAISE EXCEPTION 'rollback 0005 refused: audit evidence exists';
    END IF;
END;
$$;
DROP TRIGGER IF EXISTS trg_inx_audit_entry_immutable ON infranexum_core.audit_entry;
DROP TRIGGER IF EXISTS trg_inx_audit_tombstone_immutable ON infranexum_core.audit_purge_tombstone;
DROP FUNCTION IF EXISTS infranexum_core.reject_audit_mutation();
DROP TABLE IF EXISTS infranexum_core.audit_purge_tombstone;
DROP TABLE IF EXISTS infranexum_core.audit_entry;
DROP TABLE IF EXISTS infranexum_core.audit_chain_head;
