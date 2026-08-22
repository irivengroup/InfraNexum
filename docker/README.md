# Docker Desktop / Compose developer environment

`docker/` is repository-level **development and test tooling**. It is deliberately outside `src/` because InfraNexum production deployments target standalone bare-metal or VM servers. The installer and production deployment model must not depend on Docker, Compose or Podman.

The default topology models the **PRO `single_cluster` reference shape**: three PostgreSQL 17 nodes managed by Patroni with a three-member etcd DCS, one required synchronous standby plus a second replica, four InfraNexum Server nodes behind HAProxy, and two InfraNexum Web nodes behind a dedicated HAProxy router. PostgreSQL writes are routed only to the current Patroni primary; a second loopback endpoint exposes healthy replicas for diagnostics. Web nodes wait for the Server router before becoming eligible and expose only immutable assets plus runtime/health contracts.

Only stable routers are published to the workstation: PostgreSQL writer `127.0.0.1:5432`, PostgreSQL replicas `127.0.0.1:5433`, Server HTTP `127.0.0.1:8080`, and Web HTTP `127.0.0.1:8081`. etcd, Patroni REST, raw PostgreSQL and individual Server ports remain private to the Compose bridge. The smoke resolves effective Docker bindings and rejects any non-loopback exposure.

This is an **HA topology harness**, not an activation bypass for production. Server nodes run with `PRO` + `HIGH_AVAILABILITY`, while Entitlements enforcement is disabled only inside this developer topology so HA can be exercised without embedding customer activation material. Signed activation remains separately mandatory for production.

Server readiness includes the bounded Workers runtime. The developer smoke test requires both `/actuator/health/readiness` and the low-cardinality `infranexum.workers.ready` metric to be available before accepting the topology as healthy. Worker concurrency, lease/heartbeat timing, shutdown and retry settings can be overridden through the `INFRANEXUM_WORKERS_*` variables documented in `.env.example`.

### PostgreSQL diagnostic database contexts

Developer health diagnostics deliberately distinguish two superuser connections. Cluster-wide replication state (`pg_stat_replication`) is queried in the `postgres` maintenance database, while InfraNexum schema/history checks (`infranexum_core`, `infranexum_iam`, and other application schemas) connect to the `infranexum` database. PostgreSQL schemas are database-local; using the cluster helper for application objects is a contract violation guarded by deployment tests.


## Dockerfiles

- `server.Dockerfile`: reproducible Java 25 build and non-root Server runtime.
- `web.Dockerfile`: checksum-verified Node.js 24.19.0 amd64/arm64 runtime, non-root Web process and immutable application payload.
- `postgres-tools.Dockerfile`: pinned PostgreSQL tooling image containing secret initialization, HA bootstrap, migration and rollback scripts.
- `patroni-postgres.Dockerfile`: PostgreSQL 17.10 + Patroni 4.1.4 HA node for the PRO developer topology.

## Start from the repository root

### Windows / VS Code PowerShell

```powershell
.\docker\dev-compose.ps1 config
.\docker\dev-compose.ps1 build
.\docker\dev-compose.ps1 up
.\docker\dev-compose.ps1 smoke
.\docker\dev-compose.ps1 ha-smoke
```

Direct Compose equivalent from the repository root:

```powershell
docker compose up --detach --build --wait web
```

Verify the effective host bindings:

```powershell
docker compose port postgres 5432
docker compose port postgres 5433
docker compose port server 8080
docker compose port web 8080
```

Expected defaults:

```text
127.0.0.1:5432
127.0.0.1:5433
127.0.0.1:8080
127.0.0.1:8081
```

The canonical model remains `docker/compose.yaml`; the root `compose.yaml` only includes it.

### WSL / Unix

```sh
./docker/dev-compose.sh config
./docker/dev-compose.sh build
./docker/dev-compose.sh up
./docker/dev-compose.sh smoke
./docker/dev-compose.sh ha-smoke
```

Or through the root Makefile:

```sh
make compose-config
make compose-build
make compose-up
make compose-smoke
```

## Developer-only test fixtures and administrator recovery

`dev-compose.ps1 up` and `dev-compose.sh up` load representative developer fixtures only **after** the PRO Web/Server/PostgreSQL topology reaches the Compose `--wait web` readiness boundary. The canonical seed is `docker/dev-seed-postgresql.sql`; it is intentionally outside `src/distribution/migrations`, is mounted read-only only by the maintenance database tooling, runs as the `infranexum` application database role, uses deterministic fictional values (including `.invalid` domains), and never creates local authentication credentials. Replaying it is safe: inserts are idempotent and do not delete, truncate, or overwrite existing rows.

Replay the fixtures explicitly without recreating volumes:

```powershell
.\docker\dev-compose.ps1 seed
```

```sh
./docker/dev-compose.sh seed
```

A direct `docker compose up ...` invocation does **not** run this post-start seed; use the developer wrapper when fixtures are required.

If the canonical local `admin` account is suspended or locked, use the bounded recovery command:

```powershell
.\docker\dev-compose.ps1 admin-reactivate
```

```sh
./docker/dev-compose.sh admin-reactivate
```

The recovery changes the local account and IAM projection back to `ACTIVE`, clears failed-attempt/lock state and increments `security_epoch` so pre-recovery sessions become invalid. It then requires an effective protected `system.platform_admin` PLATFORM assignment. A missing platform-admin assignment is reported as an error and is **not** silently created.

## Stop and diagnostics

Compose log commands use **service names** (`migrate`, `postgres`, `server`), not generated container names such as `infranexum-dev-migrate-1`. From the repository root:

```powershell
.\docker\dev-compose.ps1 logs
.\docker\dev-compose.ps1 logs migrate
docker compose logs migrate

Migration `0007-core-installation-uuidv7` repairs the alpha.0.31 UUIDv4 installation identity only while it has no entitlement/activation dependents, then enforces UUIDv7 at the database boundary.
Migration `0008-core-entitlement-time-precision` then repairs the alpha.0.32 fractional `created_at` metadata and enforces whole-second Entitlements timestamps. The bootstrap itself uses `date_trunc('second', CURRENT_TIMESTAMP)` so new developer databases are correct before Server startup.
Migration `0009-core-worker-correlation` adds nullable UUIDv7-constrained `worker_task.correlation_id` metadata so a validated request correlation can survive durable scheduling, restart and worker execution without persisting raw HTTP or security context.
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

## etcd healthchecks

The official etcd 3.6 container is distroless and must not be probed through `CMD-SHELL`. Each etcd member is health-checked with the absolute `/usr/local/bin/etcdctl` executable in Compose exec form:

```text
/usr/local/bin/etcdctl --endpoints=http://127.0.0.1:2379 endpoint health
```

This checks a successful etcd proposal rather than merely testing whether TCP/2379 is open.

## PostgreSQL bootstrap

`db-bootstrap` runs only after the PostgreSQL writer router is healthy. It then waits, with a bounded 60-attempt timeout, for the full PRO invariant: two `streaming` standbys and at least one `sync`/`quorum` standby. A non-converging cluster fails closed with exit code 69 and reports the last observed count.

Application-role password SQL is read by `psql` from stdin. The password is imported with `\getenv` from the process environment so psql can safely apply `:'db_password'` SQL-literal interpolation; it is never passed in a `--command` or `--set=db_password=...` process argument.

For a bootstrap failure, inspect only the relevant one-shot service first:

```powershell
docker compose logs --no-color db-bootstrap
```

Then inspect replication state through the stable writer endpoint if needed:

```powershell
.\docker\dev-compose.ps1 smoke
```

## PRO HA validation

`smoke` requires the three etcd members, the three Patroni/PostgreSQL nodes, both database/Server routers, all four Server nodes, both Web nodes and the Web router to be healthy. It also requires two streaming standbys, at least one synchronous/quorum standby, Server readiness/worker metrics, Web readiness, and a runtime configuration whose API URL matches the effective loopback Server binding.

Replication statistics are intentionally queried through the bootstrap `postgres` identity, because PostgreSQL restricts detailed dynamic-statistics fields for sessions owned by other roles. Ordinary connectivity and application queries continue to use `infranexum`; the application role is **not** granted `pg_monitor` or `pg_read_all_stats` merely for the developer smoke test.

`ha-smoke` is deliberately disruptive but bounded: it identifies and stops the Patroni primary, requires election of a different primary, validates the stable writer and Server readiness, restarts the former primary, then requires the cluster to return to two streaming standbys. It never deletes volumes.

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
$env:INFRANEXUM_ENVIRONMENT='local'
```

Every HTTP response carries `X-Correlation-ID`. A caller may supply a canonical lowercase UUIDv7; malformed or non-v7 values receive HTTP 400 and are not reflected. `dev-compose.* smoke` validates both propagation and the fail-closed rejection path.


### Developer network boundary

The Compose network is intentionally **not** marked `internal`. Docker internal bridge networks can make published host ports ineffective; that contradicts this developer topology, which must expose PostgreSQL and Server to the local workstation. Ingress remains restricted to `127.0.0.1` for both published ports. This is development/test tooling only; production standalone bare-metal/VM deployment does not rely on this Compose network.

### Structured logging safety

The Server console format is fixed to ECS. `INFRANEXUM_LOG_FORMAT` is intentionally not supported because changing the formatter could bypass the mandatory `SensitiveDataStructuredLoggingCustomizer` redaction boundary.
