ALTER TABLE infranexum_core.worker_task
    DROP CONSTRAINT IF EXISTS ck_inx_worker_correlation_v7;
ALTER TABLE infranexum_core.worker_task
    DROP COLUMN IF EXISTS correlation_id;
