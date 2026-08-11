-- InfraNexum migration 0003: transactional inbox reservation state for PostgreSQL.
ALTER TABLE infranexum_core.inbox_receipt
    ADD COLUMN IF NOT EXISTS status VARCHAR(16);

UPDATE infranexum_core.inbox_receipt
SET status = 'COMPLETED'
WHERE status IS NULL;

ALTER TABLE infranexum_core.inbox_receipt
    ALTER COLUMN status SET DEFAULT 'COMPLETED',
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN completed_at DROP NOT NULL;

ALTER TABLE infranexum_core.inbox_receipt
    DROP CONSTRAINT IF EXISTS ck_inx_inbox_completed;

ALTER TABLE infranexum_core.inbox_receipt
    DROP CONSTRAINT IF EXISTS ck_inx_inbox_status;

ALTER TABLE infranexum_core.inbox_receipt
    ADD CONSTRAINT ck_inx_inbox_status CHECK (status IN ('PROCESSING', 'COMPLETED')),
    ADD CONSTRAINT ck_inx_inbox_completed CHECK (
        (status = 'PROCESSING' AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND completed_at >= received_at)
    );

CREATE INDEX IF NOT EXISTS ix_inx_inbox_processing
    ON infranexum_core.inbox_receipt (status, received_at, consumer_name);
