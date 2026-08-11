# Docker Compose runtime

InfraNexum provides a local/reference PostgreSQL topology under `src/deployment/docker/compose.yaml`.
The topology is intentionally bounded to the executable Server and PostgreSQL until the Web and Agent
runtime compositions are promoted independently.

## Topology

- `secret-init`: idempotently creates the database password and the 32-byte Base64 integrity key in an isolated named volume.
- `postgres`: PostgreSQL 17 with durable storage and a readiness health check.
- `migrate`: validates migration checksums, serializes each migration with a PostgreSQL advisory transaction lock,
  records logical checksums in `infranexum_core.schema_history`, and bootstraps exactly one installation identity on a new database.
- `server`: multi-stage Java 25 Spring Boot image, non-root runtime UID 10001, readiness probe and graceful shutdown.
- `rollback`: maintenance-profile one-shot rollback that refuses non-latest migrations and requires explicit confirmation.

## Start and smoke

```bash
make compose-up
make compose-smoke
```

The Server is published on `127.0.0.1:${INFRANEXUM_SERVER_PUBLISHED_PORT:-8080}` by default through Docker's port mapping.

## Stop without deleting data

```bash
make compose-down
```

## Backup

```bash
make compose-backup
```

Backups are written below `.infranexum/backups/` and are intentionally outside the product source tree.

## Controlled migration rollback

Always create a backup first. Only the most recently applied migration can be rolled back:

```bash
make compose-backup
MIGRATION_ID=0006 CONFIRM_INFRANEXUM_ROLLBACK=YES make compose-rollback
```

The rollback SQL itself remains fail-closed and can refuse the operation when business data makes it unsafe.
After a successful schema rollback, the current Server remains stopped. Deploy a Server build explicitly compatible with the rolled-back schema before restarting it; the rollback target never restarts newer application code automatically on an older schema.

## Destructive reset

This removes all Compose-managed data volumes and therefore the database, generated credentials,
installation identity and independent temporal proof:

```bash
CONFIRM_INFRANEXUM_VOLUME_DELETE=YES make compose-reset
```

Without the exact confirmation token, the target refuses to proceed.

## Restore

A restore is intentionally explicit. Stop the Server, reset only after confirming that the selected backup is correct,
start PostgreSQL, restore with `pg_restore`, then run the migrator before restarting the Server. Use `make compose-restore`
with `BACKUP_FILE=<path>` and `CONFIRM_INFRANEXUM_RESTORE=YES`.
