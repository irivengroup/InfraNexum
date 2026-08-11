-- Enforce the whole-second temporal contract used by Core Entitlements.
-- The alpha.0.32 defect came from PostgreSQL Compose bootstrap. Oracle therefore
-- fails closed on fractional legacy values instead of rewriting persisted temporal data.
DECLARE
    invalid_count NUMBER;
BEGIN
    SELECT
        (SELECT COUNT(*) FROM core_installation_identity
          WHERE EXTRACT(SECOND FROM created_at) <> TRUNC(EXTRACT(SECOND FROM created_at)))
      + (SELECT COUNT(*) FROM core_entitlement_state
          WHERE (evaluation_started_at IS NOT NULL AND EXTRACT(SECOND FROM evaluation_started_at) <> TRUNC(EXTRACT(SECOND FROM evaluation_started_at)))
             OR EXTRACT(SECOND FROM last_reliable_at) <> TRUNC(EXTRACT(SECOND FROM last_reliable_at))
             OR (valid_until IS NOT NULL AND EXTRACT(SECOND FROM valid_until) <> TRUNC(EXTRACT(SECOND FROM valid_until)))
             OR (grace_until IS NOT NULL AND EXTRACT(SECOND FROM grace_until) <> TRUNC(EXTRACT(SECOND FROM grace_until)))
             OR EXTRACT(SECOND FROM updated_at) <> TRUNC(EXTRACT(SECOND FROM updated_at)))
      + (SELECT COUNT(*) FROM core_entitlement_integrity_proof
          WHERE EXTRACT(SECOND FROM evaluation_started_at) <> TRUNC(EXTRACT(SECOND FROM evaluation_started_at))
             OR EXTRACT(SECOND FROM last_reliable_at) <> TRUNC(EXTRACT(SECOND FROM last_reliable_at))
             OR EXTRACT(SECOND FROM updated_at) <> TRUNC(EXTRACT(SECOND FROM updated_at)))
      + (SELECT COUNT(*) FROM core_activation_manifest
          WHERE EXTRACT(SECOND FROM valid_from) <> TRUNC(EXTRACT(SECOND FROM valid_from))
             OR EXTRACT(SECOND FROM valid_until) <> TRUNC(EXTRACT(SECOND FROM valid_until))
             OR EXTRACT(SECOND FROM issued_at) <> TRUNC(EXTRACT(SECOND FROM issued_at))
             OR EXTRACT(SECOND FROM accepted_at) <> TRUNC(EXTRACT(SECOND FROM accepted_at)))
      INTO invalid_count
      FROM dual;

    IF invalid_count <> 0 THEN
        RAISE_APPLICATION_ERROR(-20081,
            'fractional entitlement timestamps require explicit offline repair before migration 0008');
    END IF;
END;
/

BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE core_installation_identity ADD CONSTRAINT ck_core_install_created_sec '
        || 'CHECK (EXTRACT(SECOND FROM created_at) = TRUNC(EXTRACT(SECOND FROM created_at)))';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -2264 THEN RAISE; END IF;
END;
/

BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE core_entitlement_state ADD CONSTRAINT ck_core_ent_time_sec CHECK ('
        || '(evaluation_started_at IS NULL OR EXTRACT(SECOND FROM evaluation_started_at) = TRUNC(EXTRACT(SECOND FROM evaluation_started_at))) AND '
        || 'EXTRACT(SECOND FROM last_reliable_at) = TRUNC(EXTRACT(SECOND FROM last_reliable_at)) AND '
        || '(valid_until IS NULL OR EXTRACT(SECOND FROM valid_until) = TRUNC(EXTRACT(SECOND FROM valid_until))) AND '
        || '(grace_until IS NULL OR EXTRACT(SECOND FROM grace_until) = TRUNC(EXTRACT(SECOND FROM grace_until))) AND '
        || 'EXTRACT(SECOND FROM updated_at) = TRUNC(EXTRACT(SECOND FROM updated_at)))';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -2264 THEN RAISE; END IF;
END;
/

BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE core_entitlement_integrity_proof ADD CONSTRAINT ck_core_int_time_sec CHECK ('
        || 'EXTRACT(SECOND FROM evaluation_started_at) = TRUNC(EXTRACT(SECOND FROM evaluation_started_at)) AND '
        || 'EXTRACT(SECOND FROM last_reliable_at) = TRUNC(EXTRACT(SECOND FROM last_reliable_at)) AND '
        || 'EXTRACT(SECOND FROM updated_at) = TRUNC(EXTRACT(SECOND FROM updated_at)))';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -2264 THEN RAISE; END IF;
END;
/

BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE core_activation_manifest ADD CONSTRAINT ck_core_act_time_sec CHECK ('
        || 'EXTRACT(SECOND FROM valid_from) = TRUNC(EXTRACT(SECOND FROM valid_from)) AND '
        || 'EXTRACT(SECOND FROM valid_until) = TRUNC(EXTRACT(SECOND FROM valid_until)) AND '
        || 'EXTRACT(SECOND FROM issued_at) = TRUNC(EXTRACT(SECOND FROM issued_at)) AND '
        || 'EXTRACT(SECOND FROM accepted_at) = TRUNC(EXTRACT(SECOND FROM accepted_at)))';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -2264 THEN RAISE; END IF;
END;
/
