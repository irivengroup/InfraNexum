# InfraNexum 2.0.0-alpha.0.4 — JDBC Persistence Foundation

**InfraNexum — Infrastructure Control & Governance Platform**

This repository is the fifth executable implementation increment derived from architecture baseline `2.0.0-draft.21` and the complete implementation roadmap.

## Source layout

All implementation sources are grouped below `src/`; generated validation evidence is written to `artifacts/validation/`. The canonical component identifiers and eight structural spaces remain unchanged. See `docs/source-layout.md`.

## Implemented

- canonical eight-space repository structure and machine-readable ownership manifests;
- blocking Architecture-as-Code, secret-material, toolchain, migration, event-contract and persistence gates;
- Java Server composition root, standalone Node.js Web runtime and Go Agent runtime with strict startup configuration and health contracts;
- Core Domain Contract Pack with UUIDv7, semantic compatibility and stable domain failures;
- canonical transactional-event envelope with schema-drift enforcement;
- framework-independent unit-of-work, transactional outbox and inbox contracts;
- thread-safe in-memory reference store for deterministic contract tests;
- JDBC `TransactionalEventStore` adapter using a deployment-provided `DataSource`;
- PostgreSQL and Oracle SQL strategies without vendor-driver coupling in production code;
- atomic business writes, inbox receipts and outbox events on one physical JDBC connection;
- bounded leased claims, retry, dead-letter state, lease ownership and post-commit publication;
- paired PostgreSQL/Oracle migrations `0001` through `0003`, including logical models, verification queries, rollback and checksums;
- PostgreSQL 17/18 CI matrix for migration application and live transaction/concurrency contracts;
- regression gates with a project threshold of at least 98% coverage.

## Persistence modes

The Server supports exactly three persistence selections:

- `MEMORY`: restricted to `STANDALONE`, region `local`, site `local`;
- `POSTGRESQL`: requires a deployment-provided `DataSource` and uses the PostgreSQL JDBC strategy;
- `ORACLE`: requires a deployment-provided `DataSource` and uses the Oracle JDBC strategy.

There is no silent fallback from a JDBC mode to memory. The Server intentionally does not enable implicit Spring JDBC auto-configuration in this increment; deployment wiring must supply the intended pool and credentials.

See `docs/jdbc-persistence.md` for transaction, concurrency and operational contracts.

## Explicit limits

The product is **NON TERMINÉ**.

The JDBC adapter and its dependency-free driver simulation are implemented. Local execution against real PostgreSQL and Oracle engines was not possible in the current environment. The PostgreSQL 17/18 live suite is configured in GitHub Actions but has not been observed on a hosted runner. Oracle execution requires the dedicated licensed compatibility laboratory.

Kafka transport, durable broker-side DLQ/replay, scheduler integration, connection-pool packaging, the capability-driven React/TypeScript shell, i18n, business bounded contexts, IAM, RSOT, DCIM, ITAM, DDI, Discovery collectors, activation, audit, automation, provisioning, transactional installer and production packaging remain outside this increment.

Local compatibility validation uses Node.js 22.16.0, Go 1.23.2 and JDK 21. Exact Node.js 24.18.1/pnpm 11.17.0, Go 1.26.5 and Java 25 validation remains assigned to the corresponding CI jobs.

## Required toolchains

The exact catalogue is `toolchains.lock.json`. Principal targets:

- Eclipse Temurin/OpenJDK `25.0.4+7`;
- Spring Boot `4.1.0` and Spring Modulith `2.1.0`;
- Go `1.26.5`;
- Node.js `24.18.1` LTS and pnpm `11.17.0`;
- Python `3.13.5`;
- CMake `3.31.6` and GCC `14.2.0`.

## Local validation

```bash
python3 -m pip install --requirement requirements/ci.txt
make architecture-test architecture-check
make toolchain-test toolchain-check
make migration-test migration-check
make eventing-test eventing-check
make persistence-test persistence-check
make java-contract-smoke java-eventing-smoke java-jdbc-smoke
GOTOOLCHAIN=local make agent-vet agent-test agent-build
make web-test web-smoke
```

Exact target validation:

```bash
corepack enable
corepack prepare pnpm@11.17.0 --activate
cd src/applications/web && pnpm install --frozen-lockfile --offline && pnpm verify
GOTOOLCHAIN=go1.26.5 make agent-vet agent-test agent-build
./mvnw --batch-mode --no-transfer-progress verify
```

## Eventing semantics

The canonical envelope contains exactly:

```text
eventId, eventType, schemaVersion, occurredAt,
source, correlationId, causationId, payload
```

Delivery is at least once. Outbox state is committed before publication, and inbox deduplication uses the consumer name plus event identifier. No exactly-once or global-ordering guarantee is claimed.

## Sources of truth

- `BASELINE.json`: documentary baselines and immutable source-archive digests;
- `toolchains.lock.json`: exact build toolchain catalogue;
- `src/components/core/contracts/contract-pack.json`: Core public contract metadata;
- `src/components/core/events/event-contract-pack.json`: transactional-event semantics;
- `src/components/core/events/event-envelope.schema.json`: canonical event envelope;
- `src/distribution/migrations/catalogue.yaml`: ordered paired-migration catalogue;
- `src/validation/architecture/policy.json`: executable repository constraints;
- `src/validation/persistence/checker.py`: JDBC architecture and SQL contract gate;
- `artifacts/validation/validation-status.json`: exact status of every applicable validation.
