# InfraNexum 2.0.0-alpha.0 — Engineering Foundation

**InfraNexum — Infrastructure Control & Governance Platform**

This repository is the first real implementation increment derived from architecture baseline `2.0.0-draft.21` and the complete implementation roadmap. It establishes the canonical monorepo, executable architecture constraints, and Server/Agent bootstrap surfaces.

## Implemented in this increment

- canonical eight-space repository structure;
- machine-readable ownership, dependency, lifecycle and source-traceability manifests;
- blocking Architecture-as-Code validation with deterministic JSON evidence;
- high-confidence secret-material detection that never echoes the detected value;
- Java 25, Spring Boot 4.1.0 and Spring Modulith 2.1.0 Server composition root;
- Go Agent composition root with strict typed JSON configuration, liveness, readiness, build identity, Discovery scope mode and graceful shutdown;
- Agent scope rule: explicit assigned subnets take precedence; otherwise the site default scope applies;
- Maven bootstrap pinned to Maven 3.9.16 and verified with SHA-512;
- unit, integration, race, architecture and regression tests;
- immutable-action GitHub Actions workflow for architecture, Agent and Java gates.

## Explicitly not implemented yet

The product is **NON TERMINÉ**. Domain features, persistence, IAM, RSOT, Web UI, activation, audit, Discovery collectors, DDI, automation, provisioning, installation and production packaging are outside this first increment. Their governed structural spaces exist so subsequent work cannot violate the approved topology.

## Toolchains

- required CI/runtime build target: OpenJDK 25;
- required CI Agent target: Go 1.26.5;
- minimum Go language level declared by the Agent module: Go 1.23;
- validation tooling: Python 3.13;
- Maven wrapper distribution: 3.9.16 with an enforced SHA-512 digest.

## Commands

```bash
python3 -m pip install --requirement requirements/ci.txt
make architecture-test
make architecture-check
GOTOOLCHAIN=local make agent-vet agent-test agent-build
make verify-foundation
```

The Java build requires JDK 25 and outbound HTTPS to the Maven distribution host and Maven Central on its first run:

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

## Runtime examples

Agent:

```bash
cp applications/agent/configs/agent.example.json /tmp/infranexum-agent.json
./bin/infranexum-agent --config /tmp/infranexum-agent.json
curl --fail http://127.0.0.1:8091/health/live
curl --fail http://127.0.0.1:8091/health/ready
curl --fail http://127.0.0.1:8091/api/v1/system/build
```

Server after a successful Maven build:

```bash
java -jar applications/server/target/infranexum-server-2.0.0-alpha.0.jar
curl --fail http://127.0.0.1:8080/actuator/health/liveness
curl --fail http://127.0.0.1:8080/actuator/health/readiness
curl --fail http://127.0.0.1:8080/api/v1/system/build
```

## Source of truth

`BASELINE.json` records the documentary baselines and source archive digests. `validation/architecture/policy.json` defines executable repository constraints. A structural rule change requires an approved ADR/RFC plus synchronized policy and regression-test updates.
