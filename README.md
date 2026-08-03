# InfraNexum 2.0.0-alpha.0.5 — Capabilities & Quotas Foundation

**InfraNexum — Infrastructure Control & Governance Platform**

This repository is the sixth executable implementation increment derived from architecture baseline `2.0.0-draft.21` and the complete implementation roadmap.

## Source layout

All implementation sources are grouped below `src/`; generated validation evidence is written to `artifacts/validation/`. The canonical component identifiers and eight structural spaces remain unchanged. See `docs/source-layout.md`.

## Implemented

- canonical eight-space repository structure and machine-readable ownership manifests;
- blocking Architecture-as-Code, secret-material, toolchain, migration, event-contract, JDBC persistence and capability-policy gates;
- Java Server composition root, standalone Node.js Web runtime and Go Agent runtime with strict startup configuration and health contracts;
- Core Domain Contract Pack, canonical transactional events, transactional outbox/inbox and JDBC persistence strategies;
- centralized capability registry with 21 governed capabilities;
- installation-profile, topology, role, trait, dependency, activation and entitlement decisions with stable reason codes;
- capability guards for domain/application entry points, without profile-name branching in business modules;
- immutable capability snapshots and a deterministic functional-surface hash;
- 119 effective quotas loaded from the normative catalogue: 108 commercially scalable and 11 architecturally fixed;
- strict separation between installation profiles (`LITE`, `PRO`, `ENTERPRISE`) and allocation tiers (`STANDARD`, `ADVANCED`, `ULTIMATE`);
- enforcement of Pro Advanced and Enterprise Ultimate ceilings, including the strict Pro-to-Enterprise ratio;
- non-destructive quota reductions: existing data remains accessible while new augmentative allocations are blocked;
- read-only Server endpoints for the capability snapshot, decision explanations and effective quota plan;
- paired PostgreSQL/Oracle migrations `0001` through `0003` and PostgreSQL 17/18 live-contract workflow.

## Capability and quota API

```text
GET /api/v1/platform/capabilities
GET /api/v1/platform/capabilities/{code}
GET /api/v1/platform/quotas
```

Responses are read-only and use `Cache-Control: no-store`. Feature routes remain responsible for conditional registration; these endpoints expose the authoritative decision snapshot and do not authorize mutations by themselves.

See `docs/capabilities-and-quotas.md` for the complete decision and enforcement contracts.

## Profile and tier invariants

- `Lite`, `Pro` and `Enterprise` are installation profiles.
- `Pro Advanced` and `Enterprise Ultimate` are quota-allocation tiers only.
- A tier never unlocks a capability, topology, role, trait or component.
- Lite is non-issuable and uses the standard allocation tier only.
- Oracle and distributed/multi-region capabilities remain Enterprise-only.
- External IAM and split/high-availability deployment capabilities remain Pro-or-Enterprise.
- Domain code must branch on capability or quota decisions, never on profile or tier names.

The embedded quota policy declares `catalog_version` as `2.0.0-draft.20`. This value is preserved exactly because it is the catalogue supplied inside the `2.0.0-draft.21` architecture archive; the implementation does not silently rewrite documentary source data.

## Explicit limits

The product is **NON TERMINÉ**.

The capability registry, quota allocator, guards, Server read API and static drift gate are implemented. The complete Maven reactor under Java 25 has not been executed locally. Consequently, Spring wiring, JUnit and JaCoCo results on the target runtime remain assigned to CI.

Signed activation manifests, installation identity, offline trust validation, grace periods, revocation, clock-rollback protection and profile-upgrade workflows are not part of this increment. The React shell does not yet consume the capability snapshot. Kafka transport, real PostgreSQL/Oracle execution, business bounded contexts, IAM, audit, installers and production packaging also remain pending.

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
make java-contract-smoke java-eventing-smoke java-jdbc-smoke java-capabilities-smoke
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
- `src/components/core/capabilities/.../capability-catalog.csv`: governed functional capabilities;
- `src/components/core/capabilities/.../quota-catalog.csv`: normative quota values;
- `src/components/core/capabilities/.../quota-policy.json`: profile/tier and allocation rules;
- `src/components/core/capabilities/.../capability-contract-pack.json`: immutable catalogue hashes;
- `src/validation/capabilities/checker.py`: drift and no-profile-branching gate;
- `artifacts/validation/validation-status.json`: exact status of every applicable validation.
