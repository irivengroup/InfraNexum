# InfraNexum 2.0.0-alpha.0.12 — Core Audit Foundation

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

## alpha.0.12 — Core Audit

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
  -DfailIfNoTests=false \
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
- `toolchains.lock.json` — build toolchain catalogue;
- `src/components/core/audit/` — Core Audit contract;
- `src/components/adapters/persistence-jdbc/JdbcAuditJournal.java` — JDBC audit adapter;
- `src/distribution/migrations/0005-core-audit/` — paired audit persistence;
- `src/validation/audit/` — blocking audit drift gate;
- `artifacts/validation/validation-status.json` — validation status.
