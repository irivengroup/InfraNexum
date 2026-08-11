# Docker Desktop / Compose developer environment

`docker/` is repository-level **development and test tooling**. It is deliberately outside `src/` because InfraNexum production deployments target standalone bare-metal or VM servers. The installer and production deployment model must not depend on Docker, Compose or Podman.

The topology provides `secret-init -> postgres -> migrate -> server`, plus an explicit maintenance-only `rollback` service. PostgreSQL and the Server are health-checked, the backend network is private, runtime secrets live in a named volume and the Server is published only on loopback by default.

## Dockerfiles

- `server.Dockerfile`: reproducible Java 25 build and non-root Server runtime.
- `postgres-tools.Dockerfile`: pinned PostgreSQL tooling image containing secret initialization, migration and rollback scripts.

## Start from the repository root

### Windows / VS Code PowerShell

```powershell
.\docker\dev-compose.ps1 config
.\docker\dev-compose.ps1 build
.\docker\dev-compose.ps1 up
.\docker\dev-compose.ps1 smoke
```

Direct Compose equivalent from the repository root:

```powershell
docker compose up --detach --build --wait server
```

The canonical model remains `docker/compose.yaml`; the root `compose.yaml` only includes it.

### WSL / Unix

```sh
./docker/dev-compose.sh config
./docker/dev-compose.sh build
./docker/dev-compose.sh up
./docker/dev-compose.sh smoke
```

Or through the root Makefile:

```sh
make compose-config
make compose-build
make compose-up
make compose-smoke
```

## Stop and diagnostics

Compose log commands use **service names** (`migrate`, `postgres`, `server`), not generated container names such as `infranexum-dev-migrate-1`. From the repository root:

```powershell
.\docker\dev-compose.ps1 logs
.\docker\dev-compose.ps1 logs migrate
docker compose logs migrate

Migration `0007-core-installation-uuidv7` repairs the alpha.0.31 UUIDv4 installation identity only while it has no entitlement/activation dependents, then enforces UUIDv7 at the database boundary.
Migration `0008-core-entitlement-time-precision` then repairs the alpha.0.32 fractional `created_at` metadata and enforces whole-second Entitlements timestamps. The bootstrap itself uses `date_trunc('second', CURRENT_TIMESTAMP)` so new developer databases are correct before Server startup.
.\docker\dev-compose.ps1 down
```

If only a generated container name is available, use the Docker command rather than the Compose command:

```powershell
docker logs infranexum-dev-migrate-1
```

```sh
make compose-logs
SERVICES=migrate make compose-logs
make compose-down
```

## Backup, restore and rollback

Backups are written below `.infranexum-dev/state/backups/`, which is ignored by Git.

Restore is fail-closed:

```powershell
$env:BACKUP_FILE='.infranexum-dev\state\backups\infranexum-YYYYMMDDTHHMMSSZ.dump'
$env:CONFIRM_INFRANEXUM_RESTORE='YES'
.\docker\dev-compose.ps1 restore
```

```sh
BACKUP_FILE=.infranexum-dev/state/backups/infranexum-YYYYMMDDTHHMMSSZ.dump \
CONFIRM_INFRANEXUM_RESTORE=YES \
make compose-restore
```

Rollback always creates a backup and leaves the Server stopped:

```sh
MIGRATION_ID=0007 \
CONFIRM_INFRANEXUM_ROLLBACK=YES \
make compose-rollback
```

Deleting named volumes is deliberately blocked unless explicitly confirmed:

```sh
CONFIRM_INFRANEXUM_VOLUME_DELETE=YES make compose-reset
```

## PostgreSQL control-script portability

Migration and rollback control files contain `psql` meta-commands such as `\set`, `\gset` and `\if`. They are emitted with `printf`, never `echo`: POSIX permits `echo` implementations to interpret backslash escapes differently, and Alpine/BusyBox behavior can otherwise corrupt the generated control file before `psql` reads it.
