# InfraNexum 2.0.0-alpha.0.81 — PGM-07-E04 DCIM physical facilities hierarchy

`alpha.0.81` implements **PGM-07-E04** as a same-release vertical slice for Sites, Buildings, Floors, Rooms and technical Zones. The DCIM aggregate enforces hierarchy, kind-specific metadata, optimistic concurrency, idempotency and lifecycle while Organization/Subdivision remain weak cross-context references. Sites now carry the structured address required by the CDC (line 1, postal code, city, ISO country and IANA timezone), and Site archival/deletion is blocked only by active Buildings.

The slice adds capability `dcim.facilities`, 26 organization-scoped atomic permissions, paired PostgreSQL/Oracle migrations `0026`/`0027`, JDBC persistence, transactional events/outbox, HTTP/OpenAPI 3.1 with 25 native operations, Server CLI and **real Web administration in the same release**. The Web workspace provides governed cascading selectors `Organization → Subdivision → Site → Building → Floor → Room`, a Site country filter, resource-specific forms, lifecycle actions and DE/EN/ES/FR/IT vocabulary; no parent entity is entered as a free-form UUID.

See `docs/dcim-facility-hierarchy.md`, `docs/web-functional-parity.md` and `docs/implementation-status.md`. PGM-07-E05 may start only after target-runtime promotion gates for this snapshot are evaluated.

---

# InfraNexum 2.0.0-alpha.0.80 — Web Functional Parity for RSOT and ITAM

`alpha.0.80` is a corrective vertical-integration release. It does not add a new PGM business epic: it closes the Web delivery gap discovered after `alpha.0.79`, where RSOT and ITAM had domain, persistence, API/CLI and browser HTTP clients but no real administration routes, navigation or operator workspaces. A feature intended for administration is no longer counted as Web-supported merely because a JavaScript API client exists.

RSOT and ITAM are now first-level capability-gated workspaces (`#/rsot`, `#/itam`) with list/detail/create and governed lifecycle actions for the functionality already delivered by PGM-06-E03 and PGM-07-E01/E02/E03. Entity relationships are selected from governed catalogues instead of accepting free-form UUIDs; Organization filters Subdivision and downstream catalogues; dates and datetimes use the InfraNexum temporal controls; loading, empty, success, restricted and error states are explicit; DE/EN/ES/FR/IT are first-class. Direct navigation to an unavailable capability fails closed.

The release also closes two backend read gaps required by a correct UI rather than introducing duplicate authorities: organization-scoped canonical RSOT reads protected by the normative `rsot.read` permission, and governed support-authorization/detail reads consumed by ITAM Compliance selectors. Paired migration `0025-identity-access-rsot-read-permission` adds `rsot.read` for PostgreSQL and Oracle. OpenAPI Compliance response schemas are corrected to match the Java DTO contracts exactly, and browser assets are guarded against embedded NUL bytes after one was found in the prior ITAM Asset client.

See `docs/web-functional-parity.md` and `docs/implementation-status.md`. `PGM-07-E04` remains the next business epic after this parity correction and target-runtime promotion gates.

---

# InfraNexum 2.0.0-alpha.0.79 — PGM-07-E03 ITAM warranty, support and license compliance

`alpha.0.79` implements **PGM-07-E03** on top of the canonical Partner and Asset contexts. Hardware warranties, software-license contracts, third-party support authorizations/coverages, governed warranty types, append-only contractual revisions and deterministic deadline alerts are now first-class ITAM state. The E02 `AssetOperationalReadinessPolicy` is backed by real compliance evidence: hardware must match its canonical manufacturer and verified warranty/support coverage; software must match its canonical publisher and verified license contract.

The slice adds paired PostgreSQL/Oracle migrations `0023`/`0024`, capability `itam.compliance`, seven organization-scoped atomic permissions, transactional outbox/idempotency/versioning, HTTP/OpenAPI 3.1, Server CLI and capability-gated Web client. The asset model gains a backward-compatible nullable `producerPartnerId`: legacy assets are never silently backfilled, and source-compatible E02 Java factories remain available, but operational readiness stays fail-closed until a governed producer and required evidence exist.

Raw software activation secrets (`licenseKey`, `productKey`, `serialKey`, etc.) are intentionally **not stored or accepted** because `PGM-13-E02 — Secret Service/PKI/KMS` is not implemented. Contractual metadata/evidence is complete for E03; future secret-bearing licensing functions must use the dedicated secret service rather than adding plaintext fields. See `docs/itam-compliance.md` and `docs/itam-asset-lifecycle.md`.

`alpha.0.78` remains the canonical PGM-07-E02 asset-lifecycle baseline and `alpha.0.77` the Partner-catalogue baseline consumed by this increment.

---

# InfraNexum 2.0.0-alpha.0.77 — PGM-07-E01 ITAM Partner catalogues

`alpha.0.77` implements **PGM-07-E01** as the first ITAM catalogue slice: one governed `Partner` aggregate is authoritative for manufacturers, software publishers, suppliers, third-party support providers, integrators and recyclers. Partner lifecycle is explicit (`draft → pending_approval → active`, with `suspended` and terminal `retired` states), organization/subdivision references remain weak cross-context references, and duplicate identities, quotas, optimistic versions and idempotent mutations are enforced fail-closed. Role-filtered catalogues are views of the same aggregate; no duplicate Manufacturer or Support Provider aggregate is introduced.

The slice includes paired PostgreSQL/Oracle migrations `0019`/`0020`, the six normative ITAM Partner permissions, capability `itam.partners`, transactional events/audit, dynamic organization-scoped RBAC/ABAC, HTTP/OpenAPI, Server CLI and a capability-gated Web client. The HTTP surface remains disabled unless `INFRANEXUM_ITAM_PARTNER_API_ENABLED` is explicitly enabled; the Web publication is independently fail-closed through `INFRANEXUM_WEB_ITAM_PARTNERS_ENABLED`. See `docs/itam-partner-catalogue.md`.

`alpha.0.76` remains the Core Schema Registry baseline for **PGM-06-E03** and is not functionally altered by this increment.

The `alpha.0.75` Identity & Access entity selectors, deterministic calendars and InfraNexum visual theme remain unchanged and form the Web baseline for this increment.

# InfraNexum 2.0.0-alpha.0.74 — calendar-first temporal input and Server timezone resolution

`alpha.0.74` extends the stabilized Identity & Access experience with calendar-first temporal input. IAM and advanced-authorization effective dates are no longer free-text ISO fields: the Web uses native `datetime-local` pickers, while the Server accepts either an explicit offset/zoned ISO-8601 value or a timezone-less local date-time. When the client omits a timezone, the Server JVM/host timezone is authoritative. Ambiguous and nonexistent daylight-saving wall times are rejected fail-closed instead of being silently shifted. The RBAC/ABAC permission model, database schema and migration catalogue remain unchanged from `alpha.0.73`.

**InfraNexum — Infrastructure Control & Governance Platform**

`alpha.0.73` is a focused Web/IAM authorization-boundary and operator-experience correction. It preserves the RBAC/ABAC contracts introduced by `alpha.0.68` through `alpha.0.70` and the FreeIPA-inspired information architecture introduced by `alpha.0.72`, while fixing three observed browser defects: one denied `*.search` permission no longer fails the complete Identity & Access workspace, administration pages use the full available width with InfraNexum-themed data surfaces, and all Bootstrap `select` controls are enhanced by a deterministic accessible combobox/listbox layer.

IAM list loading is independent per resource family. A user who can administer Users or Groups but does not hold `iam.role.search` now sees a scoped restricted state only in the Roles list; other areas continue to work. The same containment applies after mutations, so a successful write is never reported as failed merely because its follow-up list refresh is not authorized.

The stable select layer keeps the native `<select>` as the authoritative form value for `FormData`, HTML validation and `change` listeners, while mouse and keyboard interaction use an InfraNexum-themed combobox/listbox. This removes the Windows/Chromium close-on-mouse-release failure without changing API payloads. The administration canvas is fluid, IAM forms are no longer capped at 58 rem, and global/IAM data tables use themed sticky headers, alternating rows, hover/focus treatment and explicit restricted/error states. DE/EN/ES/FR/IT remain first-class UI locales.

`alpha.0.70` remains the advanced-authorization baseline (PAP/PDP/PEP/PIP/PRP, declarative ABAC and static SoD). No authorization rule, database migration or public API contract is weakened by this correction. Docker/Compose remains local development/test tooling only; product deployment targets remain standalone bare metal/VM.

## alpha.0.68 — PGM-03-E03 RBAC Foundation

`alpha.0.68` delivered the `identity-access` bounded context with IAM users, temporal Organization/Subdivision memberships, groups, protected roles, approved atomic permissions, scoped USER/GROUP assignments and deny-by-default Server authorization. Its Docker Desktop PRO `up`, `smoke` and `ha-smoke` gates were subsequently reported successful by the operator before development advanced to `alpha.0.69`.

`alpha.0.67` closes the gap between HTTP authentication smokes and the real browser interaction boundary. The local-auth form is now a first-class critical path: login/password handlers are wired synchronously before the initial session probe, explicit click and form-submit paths share one serialized attempt, the static buttons remain disabled until wiring is complete, and preferences/notifications/admin navigation are initialized only after authentication succeeds. The Docker smoke now performs a real bootstrap-credential login through the Web ingress while `must_change=true`, verifies both session and CSRF cookies, and logs out without mutating the credential.

The PRO HA harness also removes noisy Patroni REST transport tracebacks at their source: Docker health checks, HAProxy PostgreSQL role checks and primary discovery use header-only `HEAD` probes. Retry loops no longer dump service logs for expected transient states, while `ha-smoke` fails closed if a new Patroni `Traceback`, `ConnectionResetError` or `BrokenPipeError` is emitted during the HA window.

`alpha.0.66` normalized PowerShell HTTP response bodies before JSON parsing. PowerShell 7 may surface `application/problem+json` content as `byte[]`/byte enumerables; casting that content to `[string]` renders decimal byte values (`123 34 115 ...`) instead of UTF-8 JSON. The developer smoke decodes `string`, `byte[]`, `HttpContent`, `Stream`, and byte-enumerable shapes explicitly while preserving the strict `401 + X-Correlation-ID + body.correlation_id` contract.

`alpha.0.65` hardens the terminal Local Auth rejection contract observed on Docker Desktop: `401/403` responses are now explicitly reset, serialized as `application/problem+json`, sized and committed at the authentication boundary so the canonical `correlation_id` cannot disappear before the response crosses the Web ingress. The PowerShell smoke keeps the strict body assertion and now includes the raw response body in any diagnostic. The login card is simplified to **Secure Area** and the authentication-service status is hidden while healthy; it appears only when the backend authentication service is unavailable. No authentication privilege, migration or HA invariant is relaxed.


`alpha.0.61` delivers **PGM-03-E02**, the secure local-human-authentication foundation required before RBAC. It adds the `identity-local` bounded context, a dedicated security adapter, durable PostgreSQL/Oracle account and session persistence, paired migration `0011-local-identity-foundation`, Server authentication/CSRF boundaries, and a same-origin Web login gate integrated into the professional administration dashboard. RBAC, MFA and external identity providers remain deliberately pending; this increment does not simulate them.

The authoritative local password policy is enforced server-side: **12–128 characters**, at least one uppercase ASCII letter, one lowercase ASCII letter, one digit and one special character; control characters are rejected and passwords are never truncated. Passwords are hashed with **Argon2id**, per-password random salt and versioned work factors. Authentication failures are generic to prevent account enumeration; unknown, malformed, locked or suspended identities consume equivalent password-hash work.

Browser sessions are opaque, durable and revocable. Only SHA-256 fingerprints of session and CSRF tokens are persisted. The PRO baseline uses a 30-minute idle timeout and 12-hour absolute timeout; password replacement increments the account security epoch and immediately revokes every previous session. Browser mutations require both a valid `HttpOnly` `INX_SESSION` cookie and a double-submit `X-CSRF-Token` matching the script-readable `INX_XSRF` cookie. Cookies are `SameSite=Strict`; insecure cookies are refused outside the explicit local developer environment.

The Docker PRO development topology generates a random bootstrap administrator secret in the developer-only runtime-secret volume. It is never emitted by Server/Web logs and is disclosed only by the explicit operator command `./docker/dev-compose.sh credentials` or `.\docker\dev-compose.ps1 credentials`. The bootstrap administrator is forced to replace that secret at first sign-in before protected v1 APIs are usable. The command continues to display the original bootstrap secret after replacement; it is not a password-reset mechanism and the original secret no longer authenticates after the mandatory change.

The Web runtime now has an authentication gate before rendering the admin shell. Local authentication remains same-origin through the Web HA router, the existing DE/EN/ES/FR/IT internationalization and stable language listbox are preserved, and no secret is stored in browser local storage. Bootstrap 5.3.6 and the adapted InfraNexum theme remain unchanged.

Docker/Compose remains development/test tooling only. Production deployment targets standalone bare metal or VM.

## Source layout

InfraNexum now enforces a strict production-source boundary: every product space required for compilation, packaging, installation, upgrade or runtime is below `src/`; tests and engineering-only support remain outside it. Java physical roots stay short and Java packages/Maven coordinates are unchanged.

```text
src/
  applications/
  components/
  engines/
  provisioning/
  installer/
  deployment/
  distribution/
  sdk/
tests/
validation/
tools/
docker/
docs/
```

Java tests live under `tests/java/...`, Go tests under `tests/go/agent`, and Web tests under `tests/web`. Source Integrity blocks tests under `src/`, legacy product roots outside `src/`, repository-relative paths over 120 characters, path components over 80 characters, and invalid release-manifest references after layout moves. See `docs/source-layout.md`.

## Docker Desktop / Compose development runtime

The complete developer topology is versioned under `docker/`. From the repository root:

```sh
make compose-config
make compose-build
make compose-up
make compose-smoke
```

Windows / VS Code PowerShell can start the same topology with:

```powershell
.\docker\dev-compose.ps1 up
```

Direct Compose commands from the repository root are:

```sh
docker compose up --detach --build --wait web
docker compose logs migrate
```

The canonical file can still be selected explicitly with `-f docker/compose.yaml`.

See `docker/README.md` for logs, stop, backup, restore, rollback and controlled volume deletion. These files are repository engineering tooling, not the production deployment mechanism.

## alpha.0.21 — Product Source Containment

This increment supersedes the physical placement introduced by `alpha.0.20`: the shallow-path guarantees are preserved, but all production solution spaces are now contained below `src/`. Test trees are deliberately external. Maven modules use explicit repository-level test sources, Go same-package tests are materialized into an isolated temporary workspace by `tools/materialize_go_tests.py`, and Web tests import the product runtime from `src/applications/web`.

The longest canonical repository-relative path remains below the 120-character fail-closed budget. `source-integrity` additionally verifies the `src/` boundary and the relative references in `src/distribution/release-manifest.json`, preventing a future move from silently redirecting baseline or release-evidence paths. Runtime contracts, Java package names, Maven artifacts, database schemas and logical component identifiers are unchanged.

## alpha.0.20 — Repository Layout Hardening

This historical increment fixed the Windows extraction/path-depth defect as an architecture invariant rather than relying on `LongPathsEnabled`, extractor-specific behavior or a local Git configuration. At `alpha.0.20`, product modules were temporarily lifted to the repository root and Java physical source roots were shortened. `alpha.0.21` keeps the shallow roots while restoring the explicit `src/` production boundary. Logical component identity, Java package names, Maven artifacts, APIs and database contracts remain unchanged.

The Source Integrity gate blocks any canonical path over 120 characters or path component over 80 characters. Architecture-as-Code allows code only inside governed top-level spaces even though the repository root is the configured source root. GitHub Actions remains Unix/Linux-only; a dedicated Ubuntu archive-compatibility gate validates the published ZIP against Windows extraction constraints, including member-length budgets, reserved names, case-insensitive collisions, symlinks and exact Git/archive parity.

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
- **Core Audit append-only foundation** introduced in `alpha.0.12`;
- **Core Workers bounded runtime foundation** introduced in `alpha.0.18`;
- **durable PostgreSQL/Oracle Workers persistence** introduced in `alpha.0.19`.


## alpha.0.19 — Durable Workers Persistence

This increment continues **PGM-02-E07** with a production JDBC implementation of the `TaskStore` port. `JdbcTaskStore` preserves the Core Workers semantics on PostgreSQL and Oracle: semantic idempotent submission, ordered due-task claims with `FOR UPDATE SKIP LOCKED`, versioned lease fencing, atomic checkpoint + lease renewal, cancellation, bounded optimistic compare-and-set recovery of expired leases, retry backoff and fail-closed `AT_MOST_ONCE` recovery. Expiry recovery is deliberately non-locking and bounded to avoid holding a large recovery lock set.

Paired migration `0006-core-workers` creates `worker_task` and `worker_task_parameter`, enforces status/lease/checkpoint invariants in the database, adds the `(task_type, idempotency_key)` uniqueness contract, and provides due/lease indexes. PostgreSQL uses bounded `VARCHAR(4096)` payloads; Oracle uses `CLOB` for checkpoint tokens and parameter values with invariant triggers where LOB-dependent checks are required. Rollback is refused once any durable task exists.

Paired migration `0007-core-installation-uuidv7` closes the persisted identity contract. PostgreSQL automatically repairs the alpha.0.31 UUIDv4 bootstrap defect only when no entitlement state, integrity proof or activation manifest references the installation identity; otherwise migration fails closed. Oracle was not affected by that Docker bootstrap path and rejects any pre-existing non-UUIDv7 identity for explicit offline repair. Both dialects end with a database-level UUIDv7 constraint.

Paired migration `0008-core-entitlement-time-precision` closes the temporal precision contract exposed after the UUIDv7 repair. PostgreSQL normalizes only `core_installation_identity.created_at`, which is unsigned installation metadata, then rejects fractional seconds in runtime state, HMAC integrity proofs and activation manifests. Oracle fails closed on any pre-existing fractional entitlement timestamp. Both dialects install database constraints preventing future violations, while the Compose bootstrap inserts `created_at` at whole-second precision from the start.

The PostgreSQL 17/18 CI job now applies migration `0006` and includes `PostgreSqlJdbcTaskStoreTest`, including a four-worker concurrent claim contract. A dependency-free `java-jdbc-workers-smoke` exercises submission replay/conflict, claim reconstruction, checkpointing, retries, cancellation, stale-lease fencing and at-most-once expiry recovery with `javac -Xlint:all -Werror`.

**PGM-02-E07 remains NON TERMINÉ** only for target-environment proof and operational readiness/metrics hardening; Server lifecycle composition is now implemented. Oracle live execution on 19c/26ai remains required.

## alpha.0.18 — Core Workers Foundation

This increment starts **PGM-02-E07** with an executable `src/components/core/workers` module. It adds idempotent scheduling, bounded worker concurrency, retry-safety contracts, versioned claim leases, heartbeat renewal, atomic checkpoints, cooperative cancellation, deterministic lease-expiry recovery and bounded graceful/forced shutdown.

The runtime is deliberately fail-closed: stale lease holders cannot mutate a reclaimed task; `AT_MOST_ONCE` work is never automatically retried after an uncertain lease expiry; and a handler that ignores interruption leaves the pool in `STOPPING` with `ShutdownReport.terminated=false` instead of producing a false termination signal.

`make java-workers-smoke` compiles the dependency-free Core path with `javac -Xlint:all -Werror` and exercises the critical concurrency/recovery scenarios. It passed 10/10 repeated local executions; the 30 JUnit-source scenarios also passed a JUnit-compatible behavioral harness under OpenJDK 21. A 2,000-task correctness stress completed with 2,000 terminal successes and zero duplicate executions. JUnit/JaCoCo gates remain fixed at 98% line and branch coverage in the Maven module, and the toolchain validator requires this smoke in the Java-enabled Foundation architecture job.

**PGM-02-E07 remains NON TERMINÉ**: production completion still requires Server composition/lifecycle integration and target-environment proof, including Oracle 19c/26ai. See `docs/core-workers.md`.

## alpha.0.17 — staged repository closure hardening

The second hosted failure proved that archive completeness is not sufficient: ten canonical files were still absent from the pushed Git snapshot even though they existed in the `alpha.0.16` source archive. This increment closes that delivery gap without weakening any existing validation.

The source-integrity gate now supports `--require-staged-snapshot`. When enabled it materializes the exact Git index with `git checkout-index` into an isolated directory and runs the full inventory, Java graph, Maven reactor and Makefile preflight against that candidate commit. A complete working tree can therefore no longer mask an incomplete staged snapshot.

A repository-local pre-commit hook is provided in `.githooks/pre-commit`. Install it once in an existing clone with `make source-integrity-hook-install`. The hook executes `make source-integrity-precommit`, which runs the source-integrity tests, validates Git tracking, validates the exact staged snapshot, verifies the staged Git-blob checksum manifest and executes `git diff --cached --check`. CI performs the same fail-closed validations after checkout.
The pre-commit target is side-effect free: coverage and diagnostic reports are written only to temporary files, so committing cannot silently mutate release evidence or invalidate archive checksums.
The tracked `src/distribution/source-files.sha256` covers the Git-tracked source snapshot (excluding itself) by hashing the immutable **Git index blobs**, not working-tree bytes. This keeps the manifest deterministic across checkout filters such as LF/CRLF conversion. The release bundle separately carries `artifacts/validation/release-files.sha256`, which hashes the actual packaged bytes, including validation evidence. This separation keeps Git recovery patches and release verification independently coherent.

Before every InfraNexum commit that changes tracked sources, stage the intended change, run `make source-checksum-update`, stage `src/distribution/source-files.sha256`, then run `make source-integrity-precommit`. The installed hook is defense in depth; CI remains fail-closed and authoritative.

The `alpha.0.17` recovery patch is built against the exact incomplete `alpha.0.16` state observed in the supplied hosted log: the ten missing paths are recreated explicitly and staged by `git apply --index`, while the staged-snapshot hardening is applied in the same change. This removes reliance on archive overlay behavior for the immediate repair.

## alpha.0.16 — Repository closure repair

The hosted `source-integrity` gate exposed a delivery-state defect rather than a Java implementation defect: 17 canonical files were present in the `alpha.0.15` source archive and in `source-inventory.json`, but absent from the Git commit executed by GitHub Actions. Those sources remain part of this delivery and are intentionally trackable; no `.gitignore` rule excludes them.

This increment keeps Git tracking mandatory and tightens the diagnostic contract:

- a canonical file that exists locally but is absent from the Git index still produces `CHECK-SOURCE-GIT-002`;
- an inventory entry that is absent from both the checkout and the Git index is reported by `CHECK-SOURCE-INVENTORY-002` only, avoiding duplicate noise;
- a dedicated regression reproduces the missing-and-untracked checkout state;
- the hosted CI remains authoritative: the gate can only pass after every canonical source is actually committed.

Before pushing this increment from an existing repository, stage modifications to tracked files plus the 17 restored sources, then run `SOURCE_INTEGRITY_REQUIRE_GIT=1 make source-integrity-test source-integrity-check`.

## alpha.0.15 — Source integrity / checkout hardening

This increment generalizes the checkout regression fixes instead of maintaining per-file exceptions:

- `src/distribution/source-inventory.json` is the canonical path inventory for source, tests, configuration, CI and documentation;
- `validation.source_integrity` rejects missing or undeclared canonical files before language builds start;
- when Git metadata is available, every inventory entry must be present in `git ls-files`; a file that exists locally but was not committed is rejected with `CHECK-SOURCE-GIT-002`;
- project-local Java imports must resolve to a main-source definition, top-level filenames must match their declared type, and duplicate FQCNs are rejected;
- case-insensitive path collisions are rejected to preserve Windows/Linux checkout equivalence;
- every Maven reactor module must have its declared `pom.xml`;
- all Foundation build/test jobs depend on the dedicated `source-integrity` GitHub Actions job.

The gate explicitly inventories `CapabilityUnavailableException.java`, `JdbcDatabaseDialect.java` and `JdbcTransactionalEventStore.java`, the three files observed missing from the hosted checkout while present in the release archive.

## alpha.0.12 — Core Audit (baseline conservée)

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


## alpha.0.13 — CI regression repair

This increment fixes two Java 25 runner regressions without weakening any quality gate:

- Core Events keeps the JaCoCo thresholds at **98% lines and 98% branches** and expands its JUnit suite from 17 to **34 scenarios** covering value-object validation, retry/backoff boundaries, transaction rollback, interruption preservation, lease ownership/recovery, Inbox state transitions, dispatcher retry/dead-letter paths and temporal overflow.
- targeted PostgreSQL reactor tests preserve strict `failIfNoTests=true` for normal builds while using the overridable `infranexum.surefire.failIfNoTests` project property for upstream modules that do not contain the selected JDBC tests.

The exact Java 25/JUnit/JaCoCo and PostgreSQL runner executions remain required before these regressions can be declared closed.

## alpha.0.14 — Capabilities/Persistence CI repair

The Java 25 runner evidence for `alpha.0.13` confirms that Core Contracts and Core Events now pass, including the unchanged 98% JaCoCo gates. The next failures were in Core Capabilities and an incomplete JDBC checkout. This increment therefore:

- expands Core Capabilities from 17 to **37 JUnit scenarios**, covering defensive constructors, profile/tier/topology matrices, catalogue parsing, every quota allocation tier, guards, threshold boundaries and malformed inputs;
- keeps JaCoCo at **98% lines and 98% branches** with no exclusions;
- removes a redundant Pro Advanced ratio branch from `QuotaCatalog` because `QuotaDefinition` already certifies the invariant and every override is bounded by that certified ceiling;
- fixes `QuotaPolicy` utilization arithmetic so quotas near `Long.MAX_VALUE` cannot overflow while computing 80%/90% thresholds;
- restores `JdbcTransactionalEventStore.java` as an explicit release source and makes `persistence-test` depend on `persistence-check`, so an incomplete checkout is rejected before fixture setup;
- adds a regression proving that a missing JDBC store produces `CHECK-JDBC-STORE-001` instead of ten `FileNotFoundError` failures.

The exact Java 25 JaCoCo result for Core Capabilities and the PostgreSQL 17/18 targeted reactor remain required on the hosted runner.

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
make source-integrity-test source-integrity-check
# One-time clone hardening: install the repository-local pre-commit gate.
make source-integrity-hook-install
# Validate the exact Git index that will become the next commit.
make source-integrity-precommit
# After intentionally adding/removing canonical files, refresh and review the inventory:
make source-integrity-update
make source-integrity-check
make architecture-test architecture-check
make toolchain-test toolchain-check
make migration-test migration-check
make eventing-test eventing-check
make persistence-test persistence-check
make capabilities-test capabilities-check
make entitlements-test entitlements-check
make audit-test audit-check
make java-contract-smoke java-eventing-smoke java-audit-smoke java-jdbc-smoke java-jdbc-workers-smoke
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
  -pl src/components/adapters/jdbc -am \
  -Dtest=PostgreSqlJdbcTransactionalEventStoreTest,PostgreSqlJdbcAuditJournalTest,PostgreSqlJdbcTaskStoreTest \
  -Dinfranexum.surefire.failIfNoTests=false \
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

A developer/test Docker Compose topology is available for the executable Java 25 Server with PostgreSQL, checksum-validated migrations, installation identity/secret bootstrap, health checks, backup/restore, controlled rollback and smoke tests. It is not the production deployment mechanism; standalone bare-metal/VM deployment remains authoritative. Web and Agent remain outside this developer topology.

Spring scheduled processing is explicitly owned by a bounded `ThreadPoolTaskScheduler` bean named `taskScheduler`; the framework fallback executor is not used. Configure it with `INFRANEXUM_SCHEDULING_POOL_SIZE` (default `2`) and `INFRANEXUM_SCHEDULING_SHUTDOWN_TIMEOUT` (default `PT10S`). Core durable Workers keep their independent `workerTaskScheduler` domain service and do not share the Spring scheduling executor.

## Required toolchains

The authoritative catalogue is `toolchains.lock.json`. Principal targets include Java/Temurin 25, Spring Boot 4.1, Go 1.26.5, Node.js 24.18.1 LTS, pnpm 11.17.0 and Python 3.13.5.

## Sources of truth

- `BASELINE.json` — documentary baselines and digests;
- `src/distribution/source-inventory.json` — canonical checkout/source inventory enforced before every build job;
- `toolchains.lock.json` — build toolchain catalogue;
- `src/components/core/audit/` — Core Audit contract;
- `src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcAuditJournal.java` — JDBC audit adapter;
- `src/distribution/migrations/0005-core-audit/` — paired audit persistence;
- `validation/audit/` — blocking audit drift gate;
- `artifacts/validation/validation-status.json` — validation status.
