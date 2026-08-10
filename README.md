# InfraNexum 2.0.0-alpha.0.16 — Repository Closure Repair

**InfraNexum — Infrastructure Control & Governance Platform**

This repository is an executable implementation increment derived from architecture baseline `2.0.0-draft.21` and the complete implementation roadmap.

## Source layout

All implementation sources, tests, migrations, validation code and build support remain below the single `src/` source root. Generated validation evidence is stored under `artifacts/validation/`.

```text
src/
├── applications/
├── components/
├── engines/
├── provisioning/
├── installer/
├── deployment/
├── distribution/
├── sdk/
├── tests/
├── validation/
└── tools/
```

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
- **Core Audit append-only foundation** introduced in `alpha.0.12`.

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
make java-contract-smoke java-eventing-smoke java-audit-smoke java-jdbc-smoke
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
  -pl src/components/adapters/persistence-jdbc -am \
  -Dtest=PostgreSqlJdbcTransactionalEventStoreTest,PostgreSqlJdbcAuditJournalTest \
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

Docker Compose is deliberately not shipped yet. A Compose topology will only be delivered when a fresh environment can build/start the Java 25 Server, run migrations, provision installation identity/secrets, and pass health/smoke checks with PostgreSQL, Web and Agent.

## Required toolchains

The authoritative catalogue is `toolchains.lock.json`. Principal targets include Java/Temurin 25, Spring Boot 4.1, Go 1.26.5, Node.js 24.18.1 LTS, pnpm 11.17.0 and Python 3.13.5.

## Sources of truth

- `BASELINE.json` — documentary baselines and digests;
- `src/distribution/source-inventory.json` — canonical checkout/source inventory enforced before every build job;
- `toolchains.lock.json` — build toolchain catalogue;
- `src/components/core/audit/` — Core Audit contract;
- `src/components/adapters/persistence-jdbc/JdbcAuditJournal.java` — JDBC audit adapter;
- `src/distribution/migrations/0005-core-audit/` — paired audit persistence;
- `src/validation/audit/` — blocking audit drift gate;
- `artifacts/validation/validation-status.json` — validation status.
