DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM core_activation_manifest) THEN
    RAISE EXCEPTION 'rollback 0004 refused: accepted activation manifests exist';
  END IF;
END $$;
DROP TABLE IF EXISTS core_activation_revocation;
DROP TABLE IF EXISTS core_activation_manifest;
DROP TABLE IF EXISTS core_entitlement_integrity_proof;
DROP TABLE IF EXISTS core_entitlement_state;
DROP TABLE IF EXISTS core_installation_identity;
