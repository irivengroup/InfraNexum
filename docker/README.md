# Docker Desktop / Compose developer environment

`docker/` is repository-level **development and test tooling**. It is deliberately outside `src/` because InfraNexum production deployments target standalone bare-metal or VM servers. The installer and production deployment model must not depend on Docker, Compose or Podman.

The topology provides `secret-init -> postgres -> migrate -> server`, plus an explicit maintenance-only `rollback` service. PostgreSQL and the Server are health-checked, the backend network is private, runtime secrets live in a named volume, and both developer-facing ports are published on host loopback only by default using the Compose short binding syntax: PostgreSQL on `127.0.0.1:5432` and Server HTTP on `127.0.0.1:8080`. Override them with `INFRANEXUM_POSTGRES_PUBLISHED_PORT` and `INFRANEXUM_SERVER_PUBLISHED_PORT`; no wildcard host binding is used. The smoke resolves Docker's effective bindings and falls back to `docker inspect` if `docker compose port` cannot render the binding.

Server readiness includes the bounded Workers runtime. The developer smoke test requires both `/actuator/health/readiness` and the low-cardinality `infranexum.workers.ready` metric to be available before accepting the topology as healthy. Worker concurrency, lease/heartbeat timing, shutdown and retry settings can be overridden through the `INFRANEXUM_WORKERS_*` variables documented in `.env.example`.

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

Verify the effective host bindings:

```powershell
docker compose port postgres 5432
docker compose port server 8080
```

Expected defaults:

```text
127.0.0.1:5432
127.0.0.1:8080
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

## HTTP correlation and structured logs

The Server defaults to ECS JSON console logs. Override only when an engineering workflow explicitly requires another Spring Boot structured format:

```powershell
$env:INFRANEXUM_LOG_FORMAT='ecs'
$env:INFRANEXUM_ENVIRONMENT='local'
```

Every HTTP response carries `X-Correlation-ID`. A caller may supply a canonical lowercase UUIDv7; malformed or non-v7 values receive HTTP 400 and are not reflected. `dev-compose.* smoke` validates both propagation and the fail-closed rejection path.
