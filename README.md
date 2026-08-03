# InfraNexum 2.0.0-alpha.0.3 — Transactional Events Foundation

**InfraNexum — Infrastructure Control & Governance Platform**

This repository is the fourth executable implementation increment derived from architecture baseline `2.0.0-draft.21` and the complete implementation roadmap.

## Source layout

All implementation sources are grouped below `src/`; generated validation evidence is written to `artifacts/validation/`. The canonical component identifiers and eight structural spaces are unchanged. See `docs/source-layout.md`.

## Implemented

- canonical eight-space repository structure and machine-readable ownership manifests;
- blocking Architecture-as-Code and high-confidence secret-material validation;
- exact polyglot toolchain catalogue and drift gates;
- Java Server composition root, standalone Node.js Web runtime and Go Agent runtime with strict startup configuration and health contracts;
- Core Domain Contract Pack with UUIDv7, semantic compatibility and stable domain failures;
- canonical transactional-event envelope with schema-drift enforcement;
- framework-independent unit-of-work, transactional outbox and inbox ports;
- thread-safe in-memory reference store with copy-on-write commit/rollback semantics;
- post-commit publication hooks, bounded leased claims, retry, dead-letter state and consumer deduplication;
- paired PostgreSQL/Oracle migrations `0001` and `0002`, including logical models, verification queries, rollback and checksums;
- regression gates with a project threshold of at least 98% coverage.

## Explicit limits

The product is **NON TERMINÉ**. The current event store is a contract/reference adapter, not production persistence. JDBC PostgreSQL/Oracle adapters, execution of migrations on supported engines, Kafka transport, durable DLQ/replay, the capability-driven React/TypeScript shell, i18n, business bounded contexts, IAM, RSOT, DCIM, ITAM, DDI, Discovery collectors, activation, audit, automation, provisioning, transactional installer and production packaging remain outside this increment.

Local validation uses Node.js 22.16.0, Go 1.23.2 and JDK 21. Exact Node.js 24.18.1/pnpm 11.17.0, Go 1.26.5 and Java 25 validation remains assigned to the corresponding CI jobs.

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
make java-contract-smoke java-eventing-smoke
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

See `src/components/core/events/README.md` for the complete contract and explicit production limitations.

## Sources of truth

- `BASELINE.json`: documentary baselines and immutable source-archive digests;
- `toolchains.lock.json`: exact build toolchain catalogue;
- `src/components/core/contracts/contract-pack.json`: Core public contract metadata;
- `src/components/core/events/event-contract-pack.json`: transactional-event semantics;
- `src/components/core/events/event-envelope.schema.json`: canonical event envelope;
- `src/distribution/migrations/catalogue.yaml`: ordered paired-migration catalogue;
- `src/validation/architecture/policy.json`: executable repository constraints;
- `artifacts/validation/validation-status.json`: exact status of every applicable validation.
