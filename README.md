# InfraNexum 2.0.0-alpha.0.6 — Signed Activation & Lite Lifecycle Foundation

**InfraNexum — Infrastructure Control & Governance Platform**

This repository is the seventh executable implementation increment derived from architecture baseline `2.0.0-draft.21` and the complete implementation roadmap.

## Source layout

All implementation sources are grouped below `src/`; generated validation evidence is written to `artifacts/validation/`. The canonical component identifiers and eight structural spaces remain unchanged. See `docs/source-layout.md`.

## Implemented

- canonical eight-space repository structure and machine-readable ownership manifests;
- blocking Architecture-as-Code, secret-material, toolchain, paired-migration, event-contract, JDBC persistence, capability-policy and entitlement gates;
- Java Server composition root, standalone Node.js Web runtime and Go Agent runtime with strict startup configuration and health contracts;
- Core Domain Contract Pack, canonical transactional events, transactional outbox/inbox and JDBC persistence strategies;
- centralized capability registry with 21 governed capabilities and 119 quotas;
- strict separation between installation profiles and commercial allocation tiers;
- UUIDv7 installation identity and deterministic versioned SHA-256 fingerprint;
- strict `infranexum.activation-manifest/v2` schema and deterministic canonical JSON payload;
- offline Ed25519 signature verification with public-key validity and revocation controls;
- customer, installation, fingerprint, profile, catalogue, capability, quota and monotone-sequence binding;
- exact Lite lifecycle: full usage before J180, conversion-only mode from J180 to J210, hard stop from J210;
- exact Pro/Enterprise lifecycle: active period, fixed 30-day grace, then hard stop;
- startup and mutation guards with stable domain error codes;
- HMAC-SHA-256 temporal evidence, dual-store consistency checks and clock-rollback rejection;
- paired PostgreSQL/Oracle migrations `0001` through `0004`.

## Activation invariants

- Lite never accepts an activation manifest.
- Pro and Enterprise require a valid signed manifest.
- Client installations contain no commercial private signing key.
- `grace_period_days` is exactly 30.
- A manifest tier changes quota allocations only and never unlocks a capability by itself.
- The manifest quota key set must exactly equal the certified 119-key catalogue.
- `host_limit` must equal `rsot.managed_hosts.max`.
- A lower or conflicting activation sequence is rejected.
- Invalid signatures, revoked keys, revoked activations and clock rollback fail closed.

See `docs/activation-entitlements.md` for the complete contract and residual risks.

## Existing capability API

```text
GET /api/v1/platform/capabilities
GET /api/v1/platform/capabilities/{code}
GET /api/v1/platform/quotas
```

These surfaces are read-only and use `Cache-Control: no-store`. Activation status, preflight and import endpoints are not yet registered; the Server must not claim that activation persistence is operational until the authoritative repositories and independent temporal store are integrated.

## Explicit limits

The product is **NON TERMINÉ**.

The offline entitlement core, lifecycle policies, access guards, schema, migration and drift gate are implemented. The complete Maven reactor under Java 25 has not been executed locally. Consequently, Spring wiring, JUnit and JaCoCo results on the target runtime remain assigned to CI.

The following remain pending:

- JDBC repositories and atomic activation import;
- authoritative Server integration and activation APIs;
- real independent temporal storage and TPM/HSM or remote monotonic anchor;
- coordinated-restore detection beyond two locally restored copies;
- external Python/PHP activation generators;
- live PostgreSQL/Oracle migration and concurrency execution;
- React capability/activation consumption;
- Kafka, audit append-only, business bounded contexts, IAM, installers and production packaging.

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
make capabilities-test capabilities-check
make entitlements-test entitlements-check
make java-contract-smoke java-eventing-smoke java-jdbc-smoke
make java-capabilities-smoke java-entitlements-smoke
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

## Sources of truth

- `BASELINE.json`: documentary baselines and immutable archive digests;
- `toolchains.lock.json`: exact build toolchain catalogue;
- `src/components/core/capabilities/...`: governed capability and quota catalogues;
- `src/components/core/entitlements/.../activation-manifest.schema.json`: signed activation schema;
- `src/components/core/entitlements/.../entitlement-contract-pack.json`: entitlement invariants and schema digest;
- `src/validation/entitlements/checker.py`: entitlement drift gate;
- `src/distribution/migrations/0004-core-entitlements`: paired persistence contract;
- `artifacts/validation/validation-status.json`: exact status of every applicable validation.
