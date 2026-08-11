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

Direct Compose equivalent:

```powershell
docker compose -f docker/compose.yaml up --detach --build --wait server
```

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

```powershell
.\docker\dev-compose.ps1 logs
.\docker\dev-compose.ps1 down
```

```sh
make compose-logs
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
MIGRATION_ID=0006-core-workers \
CONFIRM_INFRANEXUM_ROLLBACK=YES \
make compose-rollback
```

Deleting named volumes is deliberately blocked unless explicitly confirmed:

```sh
CONFIRM_INFRANEXUM_VOLUME_DELETE=YES make compose-reset
```
