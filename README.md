# InfraNexum 2.0.0-alpha.0.1 — Foundation, Contracts & Migrations

**InfraNexum — Infrastructure Control & Governance Platform**

This repository is the second executable implementation increment derived from architecture baseline `2.0.0-draft.21` and the complete implementation roadmap. It preserves the canonical monorepo and adds exact polyglot toolchain governance, the first Core Domain Contract Pack, and the first paired PostgreSQL/Oracle migration.

## Implemented

- canonical eight-space repository structure and machine-readable ownership manifests;
- blocking Architecture-as-Code and high-confidence secret-material validation;
- exact toolchain lock for Java, Maven, Spring Boot, Spring Modulith, Go, Node.js, pnpm, TypeScript, Python, CMake and GCC;
- Maven Wrapper distribution pinned to Maven `3.9.16` with SHA-512 verification;
- Core Domain Contract Pack `1.0.0` with semantic compatibility, UUIDv7 identifiers and stable domain failures;
- locally monotonic RFC 9562 UUIDv7 generation, including clock-regression and payload-exhaustion handling;
- paired migration catalogue with immutable checksums, PostgreSQL/Oracle apply scripts, paired rollback scripts, logical schema and verification queries;
- Java Server composition root and Go Agent runtime with strict configuration, health endpoints, build identity and graceful shutdown;
- regression gates with a project threshold of at least 98% coverage.

## Explicit limits

The product is **NON TERMINÉ**. No business bounded context is yet active. The Web application, database-backed migration executor, PostgreSQL persistence, IAM, RSOT, DCIM, ITAM, DDI, Discovery collectors, activation, audit, automation, provisioning, transactional installer and production packaging remain outside this increment.

The local environment does not provide JDK 25, Go 1.26.5, PostgreSQL or Oracle. Exact target-toolchain and database-engine validations are therefore recorded as `NON EXÉCUTÉ` in `validation/reports/validation-status.json`.

## Required toolchains

The exact catalogue is `toolchains.lock.json`. The principal targets are:

- Eclipse Temurin/OpenJDK `25.0.4+7`;
- Spring Boot `4.1.0` and Spring Modulith `2.1.0`;
- Go `1.26.5`;
- Node.js `24.18.1` LTS, pnpm `11.17.0`, TypeScript `7.0.2`;
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
```

Exact target validation:

```bash
GOTOOLCHAIN=go1.26.5 make agent-vet agent-test agent-build
./mvnw --batch-mode --no-transfer-progress verify
```

## Agent smoke

```bash
cp applications/agent/configs/agent.example.json /tmp/infranexum-agent.json
./bin/infranexum-agent --config /tmp/infranexum-agent.json
curl --fail http://127.0.0.1:8091/health/live
curl --fail http://127.0.0.1:8091/health/ready
curl --fail http://127.0.0.1:8091/api/v1/system/build
```

## Server smoke after Java 25 build

```bash
java -jar applications/server/target/infranexum-server-2.0.0-alpha.0.1.jar
curl --fail http://127.0.0.1:8080/actuator/health/liveness
curl --fail http://127.0.0.1:8080/actuator/health/readiness
curl --fail http://127.0.0.1:8080/api/v1/system/build
```

## Sources of truth

- `BASELINE.json`: documentary baselines and immutable source-archive digests;
- `toolchains.lock.json`: exact build toolchain catalogue;
- `components/core/contracts/contract-pack.json`: Core public contract metadata;
- `distribution/migrations/catalogue.yaml`: ordered migration catalogue;
- `validation/architecture/policy.json`: executable repository constraints;
- `validation/reports/validation-status.json`: exact status of every applicable validation.
