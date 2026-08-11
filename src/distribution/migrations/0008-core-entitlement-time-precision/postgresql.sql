-- Enforce the whole-second temporal contract used by Core Entitlements.
-- alpha.0.32's PostgreSQL developer bootstrap inserted installation created_at with
-- CURRENT_TIMESTAMP, which carries fractional seconds. The installation timestamp is
-- non-signed metadata and can be normalized safely; signed/HMAC-protected temporal
-- data must already satisfy the contract or the migration fails closed.
UPDATE core_installation_identity
   SET created_at = date_trunc('second', created_at)
 WHERE created_at <> date_trunc('second', created_at);

DO $$
DECLARE
    invalid_count BIGINT;
BEGIN
    SELECT
        (SELECT COUNT(*) FROM core_entitlement_state
          WHERE (evaluation_started_at IS NOT NULL AND evaluation_started_at <> date_trunc('second', evaluation_started_at))
             OR last_reliable_at <> date_trunc('second', last_reliable_at)
             OR (valid_until IS NOT NULL AND valid_until <> date_trunc('second', valid_until))
             OR (grace_until IS NOT NULL AND grace_until <> date_trunc('second', grace_until))
             OR updated_at <> date_trunc('second', updated_at))
      + (SELECT COUNT(*) FROM core_entitlement_integrity_proof
          WHERE evaluation_started_at <> date_trunc('second', evaluation_started_at)
             OR last_reliable_at <> date_trunc('second', last_reliable_at)
             OR updated_at <> date_trunc('second', updated_at))
      + (SELECT COUNT(*) FROM core_activation_manifest
          WHERE valid_from <> date_trunc('second', valid_from)
             OR valid_until <> date_trunc('second', valid_until)
             OR issued_at <> date_trunc('second', issued_at)
             OR accepted_at <> date_trunc('second', accepted_at))
      INTO invalid_count;

    IF invalid_count <> 0 THEN
        RAISE EXCEPTION
            'cannot normalize consumed entitlement timestamps: signed or HMAC-protected data contains fractional seconds';
    END IF;
END;
$$;

ALTER TABLE core_installation_identity
    DROP CONSTRAINT IF EXISTS ck_core_installation_created_second;
ALTER TABLE core_installation_identity
    ADD CONSTRAINT ck_core_installation_created_second
    CHECK (created_at = date_trunc('second', created_at));

ALTER TABLE core_entitlement_state
    DROP CONSTRAINT IF EXISTS ck_core_entitlement_time_second;
ALTER TABLE core_entitlement_state
    ADD CONSTRAINT ck_core_entitlement_time_second CHECK (
        (evaluation_started_at IS NULL OR evaluation_started_at = date_trunc('second', evaluation_started_at)) AND
        last_reliable_at = date_trunc('second', last_reliable_at) AND
        (valid_until IS NULL OR valid_until = date_trunc('second', valid_until)) AND
        (grace_until IS NULL OR grace_until = date_trunc('second', grace_until)) AND
        updated_at = date_trunc('second', updated_at)
    );

ALTER TABLE core_entitlement_integrity_proof
    DROP CONSTRAINT IF EXISTS ck_core_integrity_time_second;
ALTER TABLE core_entitlement_integrity_proof
    ADD CONSTRAINT ck_core_integrity_time_second CHECK (
        evaluation_started_at = date_trunc('second', evaluation_started_at) AND
        last_reliable_at = date_trunc('second', last_reliable_at) AND
        updated_at = date_trunc('second', updated_at)
    );

ALTER TABLE core_activation_manifest
    DROP CONSTRAINT IF EXISTS ck_core_activation_time_second;
ALTER TABLE core_activation_manifest
    ADD CONSTRAINT ck_core_activation_time_second CHECK (
        valid_from = date_trunc('second', valid_from) AND
        valid_until = date_trunc('second', valid_until) AND
        issued_at = date_trunc('second', issued_at) AND
        accepted_at = date_trunc('second', accepted_at)
    );
