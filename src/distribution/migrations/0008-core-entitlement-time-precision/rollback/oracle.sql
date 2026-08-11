-- Rollback removes only precision constraints; persisted timestamps are not rewritten.
BEGIN EXECUTE IMMEDIATE 'ALTER TABLE core_activation_manifest DROP CONSTRAINT ck_core_act_time_sec'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -2443 THEN RAISE; END IF; END;
/
BEGIN EXECUTE IMMEDIATE 'ALTER TABLE core_entitlement_integrity_proof DROP CONSTRAINT ck_core_int_time_sec'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -2443 THEN RAISE; END IF; END;
/
BEGIN EXECUTE IMMEDIATE 'ALTER TABLE core_entitlement_state DROP CONSTRAINT ck_core_ent_time_sec'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -2443 THEN RAISE; END IF; END;
/
BEGIN EXECUTE IMMEDIATE 'ALTER TABLE core_installation_identity DROP CONSTRAINT ck_core_install_created_sec'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -2443 THEN RAISE; END IF; END;
/
