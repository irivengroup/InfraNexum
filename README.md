# InfraNexum 2.0.0-alpha.0.11 — Maven Reactor Regression Repair

**InfraNexum — Infrastructure Control & Governance Platform**

This repository is the eleventh executable implementation increment derived from architecture baseline `2.0.0-draft.21` and the complete implementation roadmap.

## Source layout

All implementation sources are grouped below `src/`; generated validation evidence is written to `artifacts/validation/`. The canonical component identifiers and eight structural spaces remain unchanged. See `docs/source-layout.md`.

## Implemented foundation

- canonical eight-space repository structure and machine-readable ownership manifests;
- blocking Architecture-as-Code, secret-material, toolchain, paired-migration, event-contract, JDBC persistence, capability-policy and entitlement gates;
- Java Server composition root, standalone Node.js Web runtime and Go Agent runtime with strict startup configuration and health contracts;
- Core Domain Contract Pack, canonical transactional events, transactional outbox/inbox and JDBC persistence strategies;
- centralized capability registry with 21 governed capabilities and 119 quotas;
- strict separation between installation profiles and commercial allocation tiers;
- UUIDv7 installation identity and deterministic versioned SHA-256 fingerprint;
- strict `infranexum.activation-manifest/v2` schema and deterministic canonical JSON payload;
- offline Ed25519 signature verification with public-key validity and revocation controls;
- exact Lite J180/J210 lifecycle and Pro/Enterprise fixed 30-day grace lifecycle;
- HMAC-SHA-256 temporal evidence with database and independent filesystem proofs;
- compensating activation import coordinator and JDBC activation repository;
- paired PostgreSQL/Oracle migrations `0001` through `0004`;
- authoritative Server entitlement runtime described below.

## alpha.0.11 Maven reactor regression repair

This increment fixes two Java 25 CI regressions observed on the hosted runner:

- the UUIDv7 known-vector test now expects the timestamp actually encoded by the UUID prefix `018f22b27c00`, namely `2024-04-28T03:14:33.600Z`;
- the targeted PostgreSQL reactor command sets both `-DfailIfNoTests=false` and `-Dsurefire.failIfNoSpecifiedTests=false`, allowing upstream modules without the selected test class while retaining strict test requirements during the full reactor build.

The toolchain gate now blocks removal of either targeted-test flag. The dependency-free contract smoke also checks the same UUIDv7 known vector, so the regression is observable without Maven dependencies.

## alpha.0.10 authoritative Server runtime

The Server now composes the entitlement domain, JDBC adapters and platform capability registry into a single fail-closed runtime:

- durable entitlement state is initialized before the servlet container opens its port;
- Lite hard stop at J210 and paid-profile hard stop after grace prevent HTTP listener startup;
- POST, PUT, PATCH and DELETE requests below `/api/**` are guarded by the current entitlement decision;
- entitlement refresh updates the capability surface and effective quota plan atomically;
- refresh failure closes the Spring application context instead of continuing with stale authority;
- readiness exposes the entitlement health contributor;
- `GET /api/v1/platform/evaluation/status` returns the read-only runtime state with `Cache-Control: no-store`;
- entitlement access failures use `application/problem+json` with stable codes and correlation metadata;
- an uninitialized or unavailable authority returns typed HTTP 503 instead of an internal HTTP 500;
- activation trust keys, HMAC integrity material and independent proofs are externalized;
- MEMORY persistence is rejected whenever authoritative entitlements are enabled.

See `docs/server-entitlements-runtime.md` for the runtime contract, startup order and residual risks.

## Public platform API currently registered

```text
GET /api/v1/platform/capabilities
GET /api/v1/platform/capabilities/{code}
GET /api/v1/platform/quotas
GET /api/v1/platform/evaluation/status
```

These surfaces are read-only and use `Cache-Control: no-store` where entitlement or capability state is returned.

The profile-migration preflight and activation-import HTTP endpoints are intentionally not registered yet. Import remains an internal application service until IAM authorization, append-only audit and the complete preflight contract are implemented.

## Activation invariants

- Lite never accepts an activation manifest.
- Pro and Enterprise require a valid signed manifest.
- Client installations contain no commercial private signing key.
- `grace_period_days` is exactly 30.
- A commercial tier changes quota allocations only and never unlocks a capability by itself.
- The manifest quota key set must exactly equal the certified 119-key catalogue.
- `host_limit` must equal `rsot.managed_hosts.max`.
- A lower or conflicting activation sequence is rejected.
- Invalid signatures, revoked keys, revoked activations and clock rollback fail closed.
- Hard-stop startup decisions are made before HTTP port binding.
- Mutative API requests cannot bypass `EntitlementRuntimeAuthority`.

## Explicit limits

The product is **NON TERMINÉ**.

The following remain pending:

- corrected Maven reactor, Spring context, Spring Modulith, JUnit and JaCoCo execution under Java 25;
- live PostgreSQL 17/18 and Oracle 19c/26ai execution of migrations and activation repositories;
- installer-generated installation identity and first-start provisioning;
- IAM-protected and append-only audited activation import endpoint;
- complete `GET /api/v1/platform/profile-migrations/preflight` implementation;
- TPM/HSM or remote monotonic anchor beyond the implemented fsync-backed independent file proof store;
- coordinated-restore detection beyond two locally restored copies;
- external Python/PHP activation generators;
- React capability/activation consumption;
- Kafka, append-only audit, business bounded contexts, IAM and transactional installer;
- production Java packaging and Docker Compose execution environment.

Docker Compose is deliberately deferred until a fresh environment can actually build the Java 25 Server, apply migrations, provision installation identity and secrets, then pass health and smoke checks. No non-executable Compose façade is shipped.

Local compatibility validation uses Node.js 22.16.0, Go 1.23.2 and JDK 21. Exact Node.js 24.18.1/pnpm 11.17.0, Go 1.26.5 and Java 25 validation remains assigned to CI.

## Required toolchains

The exact catalogue is `toolchains.lock.json`. Principal targets:

- Eclipse Temurin/OpenJDK `25.0.4+7` (`setup-java` selector `25.0.4+7.0.LTS`);
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
make java-entitlement-runtime-smoke java-activation-operations-smoke
GOTOOLCHAIN=local make agent-vet agent-test agent-build
make web-verify
```

Exact target validation:

```bash
test "$(node --version)" = "v24.18.1"
test "$(pnpm --version)" = "11.17.0"
cd src/applications/web && pnpm install --frozen-lockfile --offline && pnpm run verify
GOTOOLCHAIN=go1.26.5 make agent-vet agent-test agent-build
./mvnw --batch-mode --no-transfer-progress verify
./mvnw --batch-mode --no-transfer-progress \
  -pl src/components/adapters/persistence-jdbc -am \
  -Dtest=PostgreSqlJdbcTransactionalEventStoreTest \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Sources of truth

- `BASELINE.json`: documentary baselines and immutable archive digests;
- `toolchains.lock.json`: exact build toolchain catalogue;
- `src/components/core/capabilities/`: governed capability and quota catalogues;
- `src/components/core/entitlements/`: activation contracts and authoritative domain runtime;
- `src/components/adapters/persistence-jdbc/`: JDBC activation/event persistence and independent proof store;
- `src/applications/server/src/main/java/io/infranexum/server/platform/entitlements/`: Server composition and HTTP boundary;
- `src/applications/server/src/main/resources/openapi/platform-entitlements.yaml`: public evaluation-status contract;
- `src/validation/entitlements/checker.py`: entitlement drift gate;
- `src/distribution/migrations/0004-core-entitlements`: paired persistence contract;
- `artifacts/validation/validation-status.json`: status of every applicable validation.
