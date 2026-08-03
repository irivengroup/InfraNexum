# InfraNexum 2.0.0-alpha.0.2 — Foundation, Contracts, Migrations & Web Runtime

**InfraNexum — Infrastructure Control & Governance Platform**

This repository is the third executable implementation increment derived from architecture baseline `2.0.0-draft.21` and the complete implementation roadmap.

## Implemented

- canonical eight-space repository structure and machine-readable ownership manifests;
- blocking Architecture-as-Code and high-confidence secret-material validation;
- exact polyglot toolchain catalogue and drift gates;
- Java Server composition root and Go Agent runtime with strict configuration and health contracts;
- Core Domain Contract Pack with UUIDv7, semantic compatibility and stable domain failures;
- paired PostgreSQL/Oracle migration catalogue with checksums, logical model, verification and rollback;
- standalone Node.js Web runtime host with validated public configuration;
- `/health/live`, `/health/ready`, `/health/startup`, `/runtime-config.json` and build identity contracts;
- bounded graceful shutdown, secure static assets, traversal/symlink protection and strict browser security headers;
- accessible, responsive operational bootstrap page that contains no authoritative business logic;
- regression gates with a project threshold of at least 98% coverage.

## Explicit limits

The product is **NON TERMINÉ**. The capability-driven React/TypeScript shell, i18n, business bounded contexts, database-backed migration executor, IAM, RSOT, DCIM, ITAM, DDI, Discovery collectors, activation, audit, automation, provisioning, transactional installer and production packaging remain outside this increment.

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
make java-contract-smoke
GOTOOLCHAIN=local make agent-vet agent-test agent-build
make web-test web-smoke
```

Exact target validation:

```bash
corepack enable
corepack prepare pnpm@11.17.0 --activate
cd applications/web && pnpm install --frozen-lockfile --offline && pnpm verify
GOTOOLCHAIN=go1.26.5 make agent-vet agent-test agent-build
./mvnw --batch-mode --no-transfer-progress verify
```

## Web runtime

```bash
cd applications/web
INFRANEXUM_WEB_LISTEN_ADDRESS=127.0.0.1:8080 \
INFRANEXUM_WEB_API_BASE_URL=/api \
INFRANEXUM_WEB_ENVIRONMENT=production \
node runtime/main.mjs

curl --fail http://127.0.0.1:8080/health/live
curl --fail http://127.0.0.1:8080/health/ready
curl --fail http://127.0.0.1:8080/health/startup
curl --fail http://127.0.0.1:8080/runtime-config.json
curl --fail http://127.0.0.1:8080/api/v1/system/build
```

## Sources of truth

- `BASELINE.json`: documentary baselines and immutable source-archive digests;
- `toolchains.lock.json`: exact build toolchain catalogue;
- `components/core/contracts/contract-pack.json`: Core public contract metadata;
- `distribution/migrations/catalogue.yaml`: ordered migration catalogue;
- `validation/architecture/policy.json`: executable repository constraints;
- `validation/reports/validation-status.json`: exact status of every applicable validation.
