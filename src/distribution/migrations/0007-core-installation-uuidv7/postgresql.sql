-- Enforce the UUIDv7 domain-identifier contract for the persisted installation identity.
-- alpha.0.31's developer PostgreSQL bootstrap incorrectly used the kernel UUIDv4 generator.
-- Repair is safe only while no entitlement state, proof or accepted manifest references the identity.
DO $$
DECLARE
    identity_count BIGINT;
    invalid_count BIGINT;
    dependent_count BIGINT;
    timestamp_hex TEXT;
    entropy TEXT;
    variant_nibble TEXT;
    replacement_id UUID;
BEGIN
    SELECT COUNT(*) INTO identity_count FROM core_installation_identity;
    IF identity_count > 1 THEN
        RAISE EXCEPTION 'cannot enforce UUIDv7 installation identity: expected at most one identity, found %', identity_count;
    END IF;

    SELECT COUNT(*) INTO invalid_count
      FROM core_installation_identity
     WHERE uuid_extract_version(installation_id) IS DISTINCT FROM 7;

    IF invalid_count = 1 THEN
        SELECT
            (SELECT COUNT(*) FROM core_entitlement_state)
          + (SELECT COUNT(*) FROM core_entitlement_integrity_proof)
          + (SELECT COUNT(*) FROM core_activation_manifest)
          INTO dependent_count;

        IF dependent_count <> 0 THEN
            RAISE EXCEPTION
                'cannot automatically replace non-UUIDv7 installation identity after entitlement state or activation data exists';
        END IF;

        -- PostgreSQL 17 has gen_random_uuid() (UUIDv4) and UUID version extraction,
        -- but not uuidv7(). Compose therefore constructs the RFC 9562 UUIDv7 layout
        -- from a millisecond Unix timestamp plus 74 random payload bits.
        timestamp_hex := LPAD(
            TO_HEX(FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT),
            12,
            '0'
        );
        entropy := REPLACE(gen_random_uuid()::TEXT, '-', '');
        variant_nibble := SUBSTRING(
            '89ab89ab89ab89ab'
            FROM STRPOS('0123456789abcdef', SUBSTRING(entropy FROM 4 FOR 1))
            FOR 1
        );
        replacement_id := (
            SUBSTRING(timestamp_hex FROM 1 FOR 8) || '-' ||
            SUBSTRING(timestamp_hex FROM 9 FOR 4) || '-7' ||
            SUBSTRING(entropy FROM 1 FOR 3) || '-' ||
            variant_nibble || SUBSTRING(entropy FROM 5 FOR 3) || '-' ||
            SUBSTRING(entropy FROM 8 FOR 12)
        )::UUID;

        IF uuid_extract_version(replacement_id) IS DISTINCT FROM 7 THEN
            RAISE EXCEPTION 'generated installation identity is not UUIDv7';
        END IF;

        UPDATE core_installation_identity
           SET installation_id = replacement_id
         WHERE uuid_extract_version(installation_id) IS DISTINCT FROM 7;
    END IF;
END;
$$;

ALTER TABLE core_installation_identity
    DROP CONSTRAINT IF EXISTS ck_core_installation_uuidv7;
ALTER TABLE core_installation_identity
    ADD CONSTRAINT ck_core_installation_uuidv7
    CHECK (uuid_extract_version(installation_id) = 7);
