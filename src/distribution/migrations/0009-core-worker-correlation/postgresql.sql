-- Add durable request correlation metadata to background tasks.
ALTER TABLE infranexum_core.worker_task
    ADD COLUMN IF NOT EXISTS correlation_id UUID;

ALTER TABLE infranexum_core.worker_task
    DROP CONSTRAINT IF EXISTS ck_inx_worker_correlation_v7;
ALTER TABLE infranexum_core.worker_task
    ADD CONSTRAINT ck_inx_worker_correlation_v7 CHECK (
        correlation_id IS NULL
        OR (
            SUBSTRING(correlation_id::TEXT FROM 15 FOR 1) = '7'
            AND SUBSTRING(correlation_id::TEXT FROM 20 FOR 1) IN ('8', '9', 'a', 'b')
        )
    );
