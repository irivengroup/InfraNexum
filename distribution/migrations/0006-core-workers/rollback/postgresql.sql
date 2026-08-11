-- Rollback is idempotent but intentionally fail-closed once any durable task exists.
DO $$
BEGIN
    IF to_regclass('infranexum_core.worker_task') IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM infranexum_core.worker_task LIMIT 1) THEN
            RAISE EXCEPTION 'cannot roll back migration 0006 after durable worker tasks have been created';
        END IF;
    END IF;
END;
$$;

DROP TABLE IF EXISTS infranexum_core.worker_task_parameter;
DROP TABLE IF EXISTS infranexum_core.worker_task;
