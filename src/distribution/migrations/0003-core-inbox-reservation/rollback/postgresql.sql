-- Rollback is safe only before an inbox handler has an active PROCESSING reservation.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM infranexum_core.inbox_receipt WHERE status = 'PROCESSING'
    ) THEN
        RAISE EXCEPTION 'cannot roll back migration 0003 while inbox reservations are processing';
    END IF;
END;
$$;

DROP INDEX IF EXISTS infranexum_core.ix_inx_inbox_processing;

ALTER TABLE infranexum_core.inbox_receipt
    DROP CONSTRAINT IF EXISTS ck_inx_inbox_completed,
    DROP CONSTRAINT IF EXISTS ck_inx_inbox_status;

ALTER TABLE infranexum_core.inbox_receipt
    ALTER COLUMN completed_at SET NOT NULL;

ALTER TABLE infranexum_core.inbox_receipt
    ADD CONSTRAINT ck_inx_inbox_completed CHECK (completed_at >= received_at);

ALTER TABLE infranexum_core.inbox_receipt
    DROP COLUMN IF EXISTS status;
