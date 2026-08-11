-- Removing the constraint is reversible; a safely repaired UUIDv7 identity is intentionally preserved.
ALTER TABLE core_installation_identity
    DROP CONSTRAINT IF EXISTS ck_core_installation_uuidv7;
