# InfraNexum 2.0.0-alpha.0.60 — Operational Administration Experience

**InfraNexum — Infrastructure Control & Governance Platform**

`alpha.0.60` extends the internationalized administration shell with structured local operator preferences, a truthful operational notification center and live platform insight widgets. The Overview now reads the existing secret-free Server APIs `/api/v1/platform/capabilities` and `/api/v1/platform/quotas` through the same-origin Web router and displays the effective installation profile, allocation tier, evaluated capability count, HA/Split-Web decisions and effective Organization/Server/Web limits. No dashboard value is synthesized when the backend contract is unavailable.

Dashboard preferences are stored as one versioned browser-local document under `infranexum.preferences.v1`. Operators can select comfortable/compact information density, responsive/expanded/compact sidebar behavior and an operational refresh cadence of off/30s/60s/5min. Locale (`infranexum.locale`) and light/dark theme (`infranexum.theme`) remain independent for backward compatibility. These settings are explicitly browser-local until IAM-backed user profiles exist.

The language control is implemented as a stable accessible listbox rather than a native `<select>`. Its DOM is never rebuilt by runtime, notification or platform-insight refreshes, so an operator's open language menu remains open until an explicit selection, Escape key or outside pointer interaction. Keyboard navigation supports ArrowUp/ArrowDown, Home/End, Enter/Space and Escape.

The notification center contains only facts observed by the current browser session: validated Web runtime state and successful/failed reads of the platform capability/quota APIs. It does not fabricate backend alerts, incidents or inventory events. `Ctrl/Cmd+K` now also exposes the Preferences and Operational Notifications actions while future business modules remain non-actionable and fail-closed until their bounded contexts and authorization surfaces are delivered.

Bootstrap 5.3.6 remains vendored locally and loaded before the adapted InfraNexum visual theme. No CDN, Bootstrap JavaScript, predecessor runtime or predecessor business template is introduced. Browser API calls remain same-origin through the Web HA router. Docker/Compose remains development/test tooling only; production deployment targets standalone bare metal or VM.

The authoritative Organization/Subdivision foundation from `alpha.0.57` remains unchanged: UUIDv7 identities, strict lifecycle, optimistic versions, capability-driven quotas, paired PostgreSQL/Oracle migration `0010`, idempotency/outbox and the fail-closed local-only pre-IAM API are preserved. The PRO HA baseline remains validated on Docker Desktop with PostgreSQL failover/rejoin, Server failover, Web failover and final readiness `UP`.

## Source layout

InfraNexum now enforces a strict production-source boundary: every product space required for compilation, packaging, installation, upgrade or runtime is below `src/`; tests and engineering-only support remain outside it. Java physical roots stay short and Java packages/Maven coordinates are unchanged.

```text
src/
  applications/
  components/
  engines/
  provisioning/
  installer/
  deployment/
  distribution/
  sdk/
tests/
validation/
tools/
docker/
docs/
```

Java tests live under `tests/java/...`, Go tests under `tests/go/agent`, and Web tests under `tests/web`. Source Integrity blocks tests under `src/`, legacy product roots outside `src/`, repository-relative paths over 120 characters, path components over 80 characters, and invalid release-manifest references after layout moves. See `docs/source-layout.md`.

## Docker Desktop / Compose development runtime

The complete developer topology is versioned under `docker/`. From the repository root:

```sh
make compose-config
make compose-build
make compose-up
make compose-smoke
```

Windows / VS Code PowerShell can start the same topology with:

```powershell
.\docker\dev-compose.ps1 up
```

Direct Compose commands from the repository root are:

```sh
docker compose up --detach --build --wait web
docker compose logs migrate
```

The canonical file can still be selected explicitly with `-f docker/compose.yaml`.

See `docker/README.md` for logs, stop, backup, restore, rollback and controlled volume deletion. These files are repository engineering tooling, not the production deployment mechanism.

## alpha.0.21 — Product Source Containment

This increment supersedes the physical placement introduced by `alpha.0.20`: the shallow-path guarantees are preserved, but all production solution spaces are now contained below `src/`. Test trees are deliberately external. Maven modules use explicit repository-level test sources, Go same-package tests are materialized into an isolated temporary workspace by `tools/materialize_go_tests.py`, and Web tests import the product runtime from `src/applications/web`.

The longest canonical repository-relative path remains below the 120-character fail-closed budget. `source-integrity` additionally verifies the `src/` boundary and the relative references in `src/distribution/release-manifest.json`, preventing a future move from silently redirecting baseline or release-evidence paths. Runtime contracts, Java package names, Maven artifacts, database schemas and logical component identifiers are unchanged.

## alpha.0.20 — Repository Layout Hardening

This historical increment fixed the Windows extraction/path-depth defect as an architecture invariant rather than relying on `LongPathsEnabled`, extractor-specific behavior or a local Git configuration. At `alpha.0.20`, product modules were temporarily lifted to the repository root and Java physical source roots were shortened. `alpha.0.21` keeps the shallow roots while restoring the explicit `src/` production boundary. Logical component identity, Java package names, Maven artifacts, APIs and database contracts remain unchanged.

The Source Integrity gate blocks any canonical path over 120 characters or path component over 80 characters. Architecture-as-Code allows code only inside governed top-level spaces even though the repository root is the configured source root. GitHub Actions remains Unix/Linux-only; a dedicated Ubuntu archive-compatibility gate validates the published ZIP against Windows extraction constraints, including member-length budgets, reserved names, case-insensitive collisions, symlinks and exact Git/archive parity.

## Implemented foundation

The repository currently contains:

- Architecture-as-Code and exact toolchain governance;
- Server, Web and Agent composition roots and health contracts;
- Core Domain Contract Pack and UUIDv7 identities;
- transactional events, outbox/inbox and idempotence primitives;
- PostgreSQL/Oracle JDBC unit-of-work and paired migrations;
- centralized capabilities, entitlements and 119-quota policy engine;
- signed activation, Lite J180/J210 lifecycle and Pro/Enterprise grace lifecycle;
- authoritative Server entitlement runtime and activation persistence;
- **Core Audit append-only foundation** introduced in `alpha.0.12`;
- **Core Workers bounded runtime foundation** introduced in `alpha.0.18`;
- **durable PostgreSQL/Oracle Workers persistence** introduced in `alpha.0.19`.


## alpha.0.19 — Durable Workers Persistence

This increment continues **PGM-02-E07** with a production JDBC implementation of the `TaskStore` port. `JdbcTaskStore` preserves the Core Workers semantics on PostgreSQL and Oracle: semantic idempotent submission, ordered due-task claims with `FOR UPDATE SKIP LOCKED`, versioned lease fencing, atomic checkpoint + lease renewal, cancellation, bounded optimistic compare-and-set recovery of expired leases, retry backoff and fail-closed `AT_MOST_ONCE` recovery. Expiry recovery is deliberately non-locking and bounded to avoid holding a large recovery lock set.

Paired migration `0006-core-workers` creates `worker_task` and `worker_task_parameter`, enforces status/lease/checkpoint invariants in the database, adds the `(task_type, idempotency_key)` uniqueness contract, and provides due/lease indexes. PostgreSQL uses bounded `VARCHAR(4096)` payloads; Oracle uses `CLOB` for checkpoint tokens and parameter values with invariant triggers where LOB-dependent checks are required. Rollback is refused once any durable task exists.

Paired migration `0007-core-installation-uuidv7` closes the persisted identity contract. PostgreSQL automatically repairs the alpha.0.31 UUIDv4 bootstrap defect only when no entitlement state, integrity proof or activation manifest references the installation identity; otherwise migration fails closed. Oracle was not affected by that Docker bootstrap path and rejects any pre-existing non-UUIDv7 identity for explicit offline repair. Both dialects end with a database-level UUIDv7 constraint.

Paired migration `0008-core-entitlement-time-precision` closes the temporal precision contract exposed after the UUIDv7 repair. PostgreSQL normalizes only `core_installation_identity.created_at`, which is unsigned installation metadata, then rejects fractional seconds in runtime state, HMAC integrity proofs and activation manifests. Oracle fails closed on any pre-existing fractional entitlement timestamp. Both dialects install database constraints preventing future violations, while the Compose bootstrap inserts `created_at` at whole-second precision from the start.

The PostgreSQL 17/18 CI job now applies migration `0006` and includes `PostgreSqlJdbcTaskStoreTest`, including a four-worker concurrent claim contract. A dependency-free `java-jdbc-workers-smoke` exercises submission replay/conflict, claim reconstruction, checkpointing, retries, cancellation, stale-lease fencing and at-most-once expiry recovery with `javac -Xlint:all -Werror`.

**PGM-02-E07 remains NON TERMINÉ** only for target-environment proof and operational readiness/metrics hardening; Server lifecycle composition is now implemented. Oracle live execution on 19c/26ai remains required.

## alpha.0.18 — Core Workers Foundation

This increment starts **PGM-02-E07** with an executable `src/components/core/workers` module. It adds idempotent scheduling, bounded worker concurrency, retry-safety contracts, versioned claim leases, heartbeat renewal, atomic checkpoints, cooperative cancellation, deterministic lease-expiry recovery and bounded graceful/forced shutdown.

The runtime is deliberately fail-closed: stale lease holders cannot mutate a reclaimed task; `AT_MOST_ONCE` work is never automatically retried after an uncertain lease expiry; and a handler that ignores interruption leaves the pool in `STOPPING` with `ShutdownReport.terminated=false` instead of producing a false termination signal.

`make java-workers-smoke` compiles the dependency-free Core path with `javac -Xlint:all -Werror` and exercises the critical concurrency/recovery scenarios. It passed 10/10 repeated local executions; the 30 JUnit-source scenarios also passed a JUnit-compatible behavioral harness under OpenJDK 21. A 2,000-task correctness stress completed with 2,000 terminal successes and zero duplicate executions. JUnit/JaCoCo gates remain fixed at 98% line and branch coverage in the Maven module, and the toolchain validator requires this smoke in the Java-enabled Foundation architecture job.

**PGM-02-E07 remains NON TERMINÉ**: production completion still requires Server composition/lifecycle integration and target-environment proof, including Oracle 19c/26ai. See `docs/core-workers.md`.

## alpha.0.17 — staged repository closure hardening

The second hosted failure proved that archive completeness is not sufficient: ten canonical files were still absent from the pushed Git snapshot even though they existed in the `alpha.0.16` source archive. This increment closes that delivery gap without weakening any existing validation.

The source-integrity gate now supports `--require-staged-snapshot`. When enabled it materializes the exact Git index with `git checkout-index` into an isolated directory and runs the full inventory, Java graph, Maven reactor and Makefile preflight against that candidate commit. A complete working tree can therefore no longer mask an incomplete staged snapshot.

A repository-local pre-commit hook is provided in `.githooks/pre-commit`. Install it once in an existing clone with `make source-integrity-hook-install`. The hook executes `make source-integrity-precommit`, which runs the source-integrity tests, validates Git tracking, validates the exact staged snapshot, verifies the staged Git-blob checksum manifest and executes `git diff --cached --check`. CI performs the same fail-closed validations after checkout.
The pre-commit target is side-effect free: coverage and diagnostic reports are written only to temporary files, so committing cannot silently mutate release evidence or invalidate archive checksums.
The tracked `src/distribution/source-files.sha256` covers the Git-tracked source snapshot (excluding itself) by hashing the immutable **Git index blobs**, not working-tree bytes. This keeps the manifest deterministic across checkout filters such as LF/CRLF conversion. The release bundle separately carries `artifacts/validation/release-files.sha256`, which hashes the actual packaged bytes, including validation evidence. This separation keeps Git recovery patches and release verification independently coherent.

Before every InfraNexum commit that changes tracked sources, stage the intended change, run `make source-checksum-update`, stage `src/distribution/source-files.sha256`, then run `make source-integrity-precommit`. The installed hook is defense in depth; CI remains fail-closed and authoritative.

The `alpha.0.17` recovery patch is built against the exact incomplete `alpha.0.16` state observed in the supplied hosted log: the ten missing paths are recreated explicitly and staged by `git apply --index`, while the staged-snapshot hardening is applied in the same change. This removes reliance on archive overlay behavior for the immediate repair.

## alpha.0.16 — Repository closure repair

The hosted `source-integrity` gate exposed a delivery-state defect rather than a Java implementation defect: 17 canonical files were present in the `alpha.0.15` source archive and in `source-inventory.json`, but absent from the Git commit executed by GitHub Actions. Those sources remain part of this delivery and are intentionally trackable; no `.gitignore` rule excludes them.

This increment keeps Git tracking mandatory and tightens the diagnostic contract:

- a canonical file that exists locally but is absent from the Git index still produces `CHECK-SOURCE-GIT-002`;
- an inventory entry that is absent from both the checkout and the Git index is reported by `CHECK-SOURCE-INVENTORY-002` only, avoiding duplicate noise;
- a dedicated regression reproduces the missing-and-untracked checkout state;
- the hosted CI remains authoritative: the gate can only pass after every canonical source is actually committed.

Before pushing this increment from an existing repository, stage modifications to tracked files plus the 17 restored sources, then run `SOURCE_INTEGRITY_REQUIRE_GIT=1 make source-integrity-test source-integrity-check`.

## alpha.0.15 — Source integrity / checkout hardening

This increment generalizes the checkout regression fixes instead of maintaining per-file exceptions:

- `src/distribution/source-inventory.json` is the canonical path inventory for source, tests, configuration, CI and documentation;
- `validation.source_integrity` rejects missing or undeclared canonical files before language builds start;
- when Git metadata is available, every inventory entry must be present in `git ls-files`; a file that exists locally but was not committed is rejected with `CHECK-SOURCE-GIT-002`;
- project-local Java imports must resolve to a main-source definition, top-level filenames must match their declared type, and duplicate FQCNs are rejected;
- case-insensitive path collisions are rejected to preserve Windows/Linux checkout equivalence;
- every Maven reactor module must have its declared `pom.xml`;
- all Foundation build/test jobs depend on the dedicated `source-integrity` GitHub Actions job.

The gate explicitly inventories `CapabilityUnavailableException.java`, `JdbcDatabaseDialect.java` and `JdbcTransactionalEventStore.java`, the three files observed missing from the hosted checkout while present in the release archive.

## alpha.0.12 — Core Audit (baseline conservée)

`src/components/core/audit` now provides:

- immutable scoped `AuditEntry` values containing actor, action, target, authorization decision, UTC timestamp, correlation ID, result and origin;
- strict metadata sanitation with rejection of secret-bearing keys and a 4 KiB aggregate UTF-8 bound;
- a per-scope SHA-256 integrity chain;
- a thread-safe in-memory reference journal;
- deterministic JSON Lines audit snapshots;
- deterministic ZIP packaging;
- SHA-256 export manifests;
- Ed25519 signatures and independent verification;
- a double-approver regulatory purge tombstone model.

`JdbcAuditJournal` persists the same contract on PostgreSQL and Oracle. Scope heads are locked with `SELECT ... FOR UPDATE`; the default transaction isolation is `READ_COMMITTED`, because the head-row lock already serializes writers for the same scope.

Paired migration `0005-core-audit` creates:

```text
audit_chain_head
audit_entry
audit_purge_tombstone
```

Database triggers reject `UPDATE` and `DELETE` on persisted audit entries and tombstones. Rollback refuses to destroy audit storage if evidence exists.

See `docs/core-audit.md`.


## alpha.0.13 — CI regression repair

This increment fixes two Java 25 runner regressions without weakening any quality gate:

- Core Events keeps the JaCoCo thresholds at **98% lines and 98% branches** and expands its JUnit suite from 17 to **34 scenarios** covering value-object validation, retry/backoff boundaries, transaction rollback, interruption preservation, lease ownership/recovery, Inbox state transitions, dispatcher retry/dead-letter paths and temporal overflow.
- targeted PostgreSQL reactor tests preserve strict `failIfNoTests=true` for normal builds while using the overridable `infranexum.surefire.failIfNoTests` project property for upstream modules that do not contain the selected JDBC tests.

The exact Java 25/JUnit/JaCoCo and PostgreSQL runner executions remain required before these regressions can be declared closed.

## alpha.0.14 — Capabilities/Persistence CI repair

The Java 25 runner evidence for `alpha.0.13` confirms that Core Contracts and Core Events now pass, including the unchanged 98% JaCoCo gates. The next failures were in Core Capabilities and an incomplete JDBC checkout. This increment therefore:

- expands Core Capabilities from 17 to **37 JUnit scenarios**, covering defensive constructors, profile/tier/topology matrices, catalogue parsing, every quota allocation tier, guards, threshold boundaries and malformed inputs;
- keeps JaCoCo at **98% lines and 98% branches** with no exclusions;
- removes a redundant Pro Advanced ratio branch from `QuotaCatalog` because `QuotaDefinition` already certifies the invariant and every override is bounded by that certified ceiling;
- fixes `QuotaPolicy` utilization arithmetic so quotas near `Long.MAX_VALUE` cannot overflow while computing 80%/90% thresholds;
- restores `JdbcTransactionalEventStore.java` as an explicit release source and makes `persistence-test` depend on `persistence-check`, so an incomplete checkout is rejected before fixture setup;
- adds a regression proving that a missing JDBC store produces `CHECK-JDBC-STORE-001` instead of ten `FileNotFoundError` failures.

The exact Java 25 JaCoCo result for Core Capabilities and the PostgreSQL 17/18 targeted reactor remain required on the hosted runner.

## Current public platform API

```text
GET /api/v1/platform/capabilities
GET /api/v1/platform/capabilities/{code}
GET /api/v1/platform/quotas
GET /api/v1/platform/evaluation/status
```

No public Audit API is exposed yet. IAM authorization and self-auditing of reads/searches/exports must exist before publication of sensitive audit surfaces.

## Local validation

```bash
python3 -m pip install --requirement requirements/ci.txt
make source-integrity-test source-integrity-check
# One-time clone hardening: install the repository-local pre-commit gate.
make source-integrity-hook-install
# Validate the exact Git index that will become the next commit.
make source-integrity-precommit
# After intentionally adding/removing canonical files, refresh and review the inventory:
make source-integrity-update
make source-integrity-check
make architecture-test architecture-check
make toolchain-test toolchain-check
make migration-test migration-check
make eventing-test eventing-check
make persistence-test persistence-check
make capabilities-test capabilities-check
make entitlements-test entitlements-check
make audit-test audit-check
make java-contract-smoke java-eventing-smoke java-audit-smoke java-jdbc-smoke java-jdbc-workers-smoke
make java-capabilities-smoke java-entitlements-smoke
make java-entitlement-runtime-smoke java-activation-operations-smoke
GOTOOLCHAIN=local make agent-vet agent-test agent-build
make web-verify
```

Target-environment validation:

```bash
test "$(node --version)" = "v24.18.1"
test "$(pnpm --version)" = "11.17.0"
cd src/applications/web && pnpm install --frozen-lockfile --offline && pnpm run verify
cd ../../..
GOTOOLCHAIN=go1.26.5 make agent-vet agent-test agent-build
./mvnw --batch-mode --no-transfer-progress verify
./mvnw --batch-mode --no-transfer-progress \
  -pl src/components/adapters/jdbc -am \
  -Dtest=PostgreSqlJdbcTransactionalEventStoreTest,PostgreSqlJdbcAuditJournalTest,PostgreSqlJdbcTaskStoreTest \
  -Dinfranexum.surefire.failIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Explicit limits

The product is **NON TERMINÉ**.

For Core Audit specifically, the following still require implementation or target-environment proof:

- Java 25 JUnit/JaCoCo execution;
- PostgreSQL 17/18 and Oracle 19c/26ai live execution;
- advanced scoped search with IAM authorization;
- auditing of sensitive reads, searches and exports themselves;
- encrypted export storage, expiration/availability policy and secure delivery;
- complete regulatory purge workflow covering replicas, indexes, caches and backups;
- audit API/CLI/UI after IAM is available;
- load tests against the documented P95 objectives.

Broader platform limits remain: installer provisioning, production Server packaging, IAM bounded contexts, Kafka transport, business domains and deployment topologies.

A developer/test Docker Compose topology is available for the executable Java 25 Server with PostgreSQL, checksum-validated migrations, installation identity/secret bootstrap, health checks, backup/restore, controlled rollback and smoke tests. It is not the production deployment mechanism; standalone bare-metal/VM deployment remains authoritative. Web and Agent remain outside this developer topology.

Spring scheduled processing is explicitly owned by a bounded `ThreadPoolTaskScheduler` bean named `taskScheduler`; the framework fallback executor is not used. Configure it with `INFRANEXUM_SCHEDULING_POOL_SIZE` (default `2`) and `INFRANEXUM_SCHEDULING_SHUTDOWN_TIMEOUT` (default `PT10S`). Core durable Workers keep their independent `workerTaskScheduler` domain service and do not share the Spring scheduling executor.

## Required toolchains

The authoritative catalogue is `toolchains.lock.json`. Principal targets include Java/Temurin 25, Spring Boot 4.1, Go 1.26.5, Node.js 24.18.1 LTS, pnpm 11.17.0 and Python 3.13.5.

## Sources of truth

- `BASELINE.json` — documentary baselines and digests;
- `src/distribution/source-inventory.json` — canonical checkout/source inventory enforced before every build job;
- `toolchains.lock.json` — build toolchain catalogue;
- `src/components/core/audit/` — Core Audit contract;
- `src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcAuditJournal.java` — JDBC audit adapter;
- `src/distribution/migrations/0005-core-audit/` — paired audit persistence;
- `validation/audit/` — blocking audit drift gate;
- `artifacts/validation/validation-status.json` — validation status.
