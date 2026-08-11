-- Rollback removes only precision constraints; normalized installation metadata is preserved.
ALTER TABLE core_activation_manifest
    DROP CONSTRAINT IF EXISTS ck_core_activation_time_second;
ALTER TABLE core_entitlement_integrity_proof
    DROP CONSTRAINT IF EXISTS ck_core_integrity_time_second;
ALTER TABLE core_entitlement_state
    DROP CONSTRAINT IF EXISTS ck_core_entitlement_time_second;
ALTER TABLE core_installation_identity
    DROP CONSTRAINT IF EXISTS ck_core_installation_created_second;
