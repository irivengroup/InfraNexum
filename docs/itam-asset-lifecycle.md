# ITAM Asset lifecycle — PGM-07-E02

## Purpose and authority boundary

`PGM-07-E02` adds the canonical **patrimonial lifecycle** of an ITAM asset without taking authority away from neighboring bounded contexts. An ITAM asset stores the RSOT canonical object UUID, owning Organization/Subdivision UUIDs, acquisition Partner UUID and custody Actor/Partner UUIDs as **weak references** validated through application ports. RSOT remains authoritative for operational identity; Organization remains authoritative for organizational structure; Partner remains authoritative for external parties; DCIM remains authoritative for physical placement.

The asset aggregate owns acquisition date/value/currency, current lifecycle state, current accountable custodian, optimistic version, audit actors/timestamps and the append-only custody chronology. Warranty, manufacturer support and software-license contracts are deliberately not duplicated here; they belong to `PGM-07-E03`.

## Lifecycle

The governed states are:

`ACQUIRED → RECEIVED → IN_STOCK → ASSIGNED → DEPLOYED`

Maintenance and return are explicit side transitions, while transfer changes custody without inventing a new patrimonial state. Retirement is explicit and disposition is terminal:

`… → MAINTENANCE → RETURNED → … → RETIRED → DISPOSED`

Every mutation increments the optimistic `version` exactly once and appends one custody event with the same sequence number. The event records actor, correlation identifier, reason, resulting custodian and optional evidence. `DISPOSED` requires a non-empty evidence reference, allowing certificates of erasure, destruction, recycling or transfer to be retained without placing their binary contents in the lifecycle table or event payload.

## Mandatory warranty/license readiness

The CDC requires complete warranty/support information for physical hardware and complete license-contract information for software before those assets become operational. `PGM-07-E03`, which follows this epic in the roadmap, owns those contracts. Consequently `alpha.0.78` exposes a mandatory `AssetOperationalReadinessPolicy` port and the current Server composition supplies `PendingAssetComplianceReadinessPolicy`.

That implementation is intentionally **fail-closed**: transitions to `IN_STOCK`, `ASSIGNED` or `DEPLOYED` fail with `ITAM_ASSET_COMPLIANCE_GATE_UNAVAILABLE` until `PGM-07-E03` replaces the pending policy with the warranty/license-aware implementation. Acquisition, receipt, transfer, maintenance, return, retirement and evidenced disposition remain governed by E02 and do not bypass this gate.

## Persistence and concurrency

Migration `0021-itam-asset-lifecycle` creates the asset current-state table, append-only custody history and command de-duplication store on PostgreSQL and Oracle. The only relational foreign key is internal to the ITAM lifecycle (`custody_event → asset`); there are no FKs to RSOT, IAM, Organization or Partner tables.

Mutations use the same JDBC unit of work as the transactional outbox. Reads required to make a mutation decision are performed on that transaction connection, avoiding a time-of-check/time-of-use window. Updates use `WHERE version = expectedVersion`; a concurrent modification fails with `VERSION_CONFLICT`. One RSOT canonical object may be linked to at most one ITAM asset.

`Idempotency-Key` is required for mutations. A replay with the same operation and canonical payload returns the existing aggregate; reuse with a different operation or payload returns `IDEMPOTENCY_CONFLICT`.

## Capability, quota and authorization

The capability is `itam.assets`, present on Lite, Pro and Enterprise server roles. Allocation is limited by the existing effective quota `itam.assets.max`; the quota is read from the current capability allocation rather than cached at service construction.

Migration `0022-identity-access-itam-asset-permissions` adds the organization-scoped permissions:

- `itam.asset.read`
- `itam.asset.create`
- `itam.asset.update`

The platform administrator bootstrap receives those permissions during upgrade. HTTP and CLI enforce RBAC/ABAC against the asset’s real owning organization. Collection reads without an organization filter require platform scope rather than broadening an organization-scoped role.

## HTTP API and OpenAPI

The API is rooted at `/api/v1/itam/assets` and is disabled by default with `INFRANEXUM_ITAM_ASSET_API_ENABLED=false`. It provides bounded portfolio reads, aggregate read, custody history, acquisition and explicit lifecycle commands (`receive`, `stock`, `assign`, `deploy`, `transfer`, maintenance start/return, `retire`, `dispose`). It does not expose a generic free-form state PATCH.

Mutations require CSRF protection, `Idempotency-Key`, and—after creation—`If-Match: "ver-N"`. Responses use ETags and `application/problem+json` failures. `src/applications/server/resources/openapi/itam-assets.yaml` is a native OpenAPI 3.1 document with unique operation IDs and the ITAM functional tag; no custom response-map preprocessor is required.

## CLI and Web

The Server CLI exposes the same lifecycle use cases. Authentication uses a local password file rather than command-line plaintext, mutations support `--dry-run`, and structured acquisition input is read from a JSON file. Exit codes distinguish usage/authentication/authorization/conflict/not-found/internal failures.

The Web library `itam-assets.mjs` is capability-gated and same-origin. It validates bounded query inputs, CSRF token, `Idempotency-Key`, ETag/`If-Match`, timeouts and RFC problem responses. `itamAssetsEnabled` can only be published when `itamPartnersEnabled` is also true; its default remains false outside an explicitly enabled runtime.

## Upgrade and rollback

Upgrade order is mandatory:

1. apply `0021-itam-asset-lifecycle`;
2. execute its PostgreSQL/Oracle verification queries;
3. apply `0022-identity-access-itam-asset-permissions`;
4. execute its verification queries;
5. start Server nodes with the new binary while keeping the asset HTTP surface disabled until smoke validation succeeds;
6. enable the surface only where the capability/runtime policy requires it.

Rollback is only safe while no consumer requires E02 data. Disable the asset API/Web surface first, stop writers, export/backup the ITAM asset tables, then apply rollback for `0022` before `0021`. The `0021` rollback drops only objects owned by this ITAM slice; it never modifies RSOT, Organization, IAM actor, Partner or DCIM data. A software rollback alone cannot preserve assets created after the schema is removed, so database backup/restore is the recovery mechanism for a destructive rollback.

## Promotion gates

The source snapshot is not production-promoted until the project’s target gates pass: Maven/JUnit/JaCoCo under JDK 25, PostgreSQL upgrade/verification/rollback on the supported live database matrix, Oracle equivalent validation, Docker Desktop PRO/HA smoke and failover, exact Node target validation, and all existing Source Integrity/Architecture/Capability/Entitlement/Audit gates.
