# InfraNexum 2.0.0-alpha.0.117 — JDK25 Server compilation corrective

`alpha.0.117` is a corrective over `alpha.0.116`; it does **not** advance PGM-10-E06. The Docker/JDK25 build exposed a Java generic-inference defect in `ImmutableConnectorSyncHandlerRegistry`: `Objects.requireNonNullElse(handlers, List.of())` is inferred as an object-typed empty list in the enhanced `for` expression, so `javac` rejects the element as `ConnectorSyncHandler`.

The registry now uses an explicitly typed empty collection fallback, and the same hardening is applied to other `Objects.requireNonNullElse` empty Set/List/Map defaults in the affected Java surfaces. A dependency-free preflight now compiles the **real Server registry** with `-Xlint:all -Werror` and exercises empty, duplicate, null-handler and missing-key behavior, so this class of compile regression is caught before Maven/Docker.

No API operation, migration, RBAC permission, connector policy, synchronization semantics or Web behavior changes. The product contract remains **15 fragments / 200 operations**. Jira Assets and ServiceNow remain `FEDERATED_READ`; PGM-10-E05 remains formally open until exact hosted JDK25/JaCoCo/PostgreSQL 17/18 gates pass.

---

# InfraNexum 2.0.0-alpha.0.116 — durable connector synchronization and compensation runtime

**PGM-10-E06 remains EN COURS.** This increment turns the connector governance model delivered in `alpha.0.114` into a real provider-neutral synchronization execution boundary: durable runs, append-only checkpoints, bounded batch progression, pause/resume, active-run fencing and governed compensation. Jira Assets and ServiceNow remain strictly `FEDERATED_READ / EXTERNAL`; no mutating provider handler is registered until an explicit field-authority contract exists.

The runtime does **not** assume exactly-once delivery. Handler execution must be idempotent for a repeated durable cursor. Every checkpoint is append-only and revisioned; a successful compensation creates a new `COMPENSATION` checkpoint restoring the prior cursor instead of rewriting history. Compensation is fenced against a newer connector revision so an older run cannot overwrite later progress.

Persistence is provided for PostgreSQL and Oracle by migrations `0038` (sync state/runs/checkpoints) and `0039` (platform RBAC permissions `integrations.sync.read|execute|compensate`). Public API responses expose only cursor SHA-256, never the raw provider cursor. Five operations expose run history, checkpoint history, execute, resume and compensate under capability `integrations.connectors`, with mandatory authorization and idempotency on mutations.

The Web Integrations workspace exposes the synchronization runtime and history. Execution remains disabled while no governed mutating connector exists. Resume and compensation require an explicit operator reason, and no provider credential or raw cursor reaches the browser.

**PGM-10-E05 remains formally NON TERMINÉ** until exact hosted Temurin 25/Maven/JaCoCo and PostgreSQL 17/18 evidence passes. Exact JDK25/JaCoCo verification of the new sync JUnit suites must therefore be supplied by the hosted quality gates; local Java 21 smokes are auxiliary only.

See `docs/integrations-connector-sync-runtime.md` and `docs/integrations-connector-governance.md`.

---

# InfraNexum 2.0.0-alpha.0.115 — hosted Java quality-gate corrective

`alpha.0.115` is a qualification corrective over `alpha.0.114`; it does **not** advance PGM-10-E06. The hosted Temurin/JDK 25 verification of `alpha.0.114` exposed four independent Java quality-gate defects that local dependency-free smokes could not certify: DDI JaCoCo line coverage at 96 %, an Integrations notification fixture that did not actually register the disabled endpoint it intended to test, JDBC independent-module coverage at 93 % lines / 83 % branches, and five Server Spring-context errors in the MEMORY test runtime.

The corrective keeps every JaCoCo threshold at **98 % lines and branches**. DDI receives additional domain-contract coverage; the notification fail-closed test now exercises a registry containing the disabled endpoint; the durable outbound-notification JDBC repository receives deterministic PostgreSQL/Oracle coverage for admission, claims, fencing, retries, DLQ state, Oracle uniqueness races, replay/resume, counts, transaction restoration and failure-code bounds. No production fail-closed rule is weakened to satisfy a test.

Server composition is corrected rather than mocked around: durable RSOT HTTP/CLI boundaries are not composed when `infranexum.persistence.mode=MEMORY`, while PostgreSQL/Oracle behavior remains unchanged. The executable-entrypoint test now supplies MEMORY/entitlement/worker overrides as command-line properties, so `application.yaml` cannot override the intended isolated test runtime through lower-precedence `SpringApplicationBuilder.properties(...)` defaults. A Spring `ApplicationContextRunner` regression explicitly verifies that MEMORY mode does not instantiate the durable RSOT controllers.

Exact Temurin 25 Maven/JUnit/JaCoCo results for **this** `alpha.0.115` source snapshot remain an external CI gate until the corrected commit is rerun on GitHub Actions. Local Java 21 dependency-free smokes and deterministic JUnit-compatible coverage probes are preflight evidence only and are not reported as a replacement for that hosted gate.

---

# InfraNexum 2.0.0-alpha.0.114 — PGM-10-E06 connector governance

`alpha.0.114` advances PGM-10-E06 with a provider-neutral **Connector Governance** runtime over the existing Jira Assets and ServiceNow providers. Authority, synchronization direction, conflict strategy, deletion policy, field-level authority and rollback strategy are now executable Server policy rather than documentation-only metadata.

The current Jira Assets and ServiceNow connectors remain deliberately `FEDERATED_READ / EXTERNAL / REJECT / IGNORE / NONE_REQUIRED`. A fail-closed dry-run planner therefore allows their existing non-mutating federated-read behavior and denies import, write-back, deletion propagation or bidirectional plans. No provider or InfraNexum object is mutated by this increment, and no database migration is introduced.

Three capability/RBAC-gated operations expose the governance catalogue, policy detail and sync-plan dry-run. The Integrations workspace displays direction, authority, conflict/deletion/rollback policy and the dry-run result without exposing provider credentials. The contract now contains **15 OpenAPI fragments / 195 operations with debt 0/0/0/0**.

This phase establishes the admission model required by the roadmap gate `Authority mapping + sync direction + rollback de connecteur`; it does **not** claim executable mutating synchronization or compensation. Actual durable sync checkpoints, mutation execution, compensation/rollback verification and controlled deletion propagation remain later PGM-10-E06 work. OpenService also remains unimplemented until an authoritative provider/API/authentication/data contract is supplied. See `docs/integrations-connector-governance.md`.

---

# InfraNexum 2.0.0-alpha.0.113 — enterprise Web/UX consistency corrective

`alpha.0.113` is a transverse Web/UX corrective over `alpha.0.112`; it does not advance the roadmap. The shared enterprise DataTable/CRUD layer now owns bounded geometry, content-aware column sizing, technical-ID presentation and dynamically injected create/edit controls, so IAM, DCIM, ITAM, RSOT, DDI and Integrations receive the same behavior instead of per-screen fixes. One additive read-only IAM operation is introduced to support correct group-member administration: direct membership edges are listed separately from recursively effective users; no persistence migration, new permission or capability is introduced.

Primary ID/UUID columns are no longer part of list presentation; the selected technical identifier is surfaced read-only in the detail/editor view. IAM action density is reduced by moving related operations into contextual editor facets: User Modify includes parameters, memberships, roles and ACTIVE/SUSPENDED status; Group Modify includes parameters and roles while Members owns the paginated direct-member list, add/remove operations and the separate effective-members projection; Role Modify includes parameters, assignments and revocation. The account dropdown is visually integrated with the InfraNexum light/dark theme, and editor facets provide keyboard focus traversal.

The table container is always bounded to the workspace width. Columns keep natural compact widths when their content is short, long-content columns absorb the remaining space, and horizontal scrolling is contained inside the responsive table wrapper only when the viewport cannot physically accommodate the minimum readable widths.

---

# InfraNexum 2.0.0-alpha.0.112 — explicit Spring constructor binding corrective

`alpha.0.112` is a startup corrective over `alpha.0.111`. The previous corrective removed ambiguous empty YAML maps, but the Docker PRO runtime then exposed the next binding defect: `IntegrationRuntimeProperties` is an immutable record with both its canonical constructor and a compatibility constructor. Spring Boot 4.1 therefore cannot implicitly select constructor binding and falls back to JavaBean instantiation, which fails because the record intentionally has no default constructor.

The canonical compact constructor is now explicitly annotated with Spring Boot `@ConstructorBinding`. The compatibility constructor is preserved, no mutable no-args constructor is introduced, and all validated normalization/default behavior remains unchanged. The real ConfigData `ApplicationContextRunner` regression test plus an architecture gate protect this boundary.

Jira Assets federated read, ServiceNow CMDB federated read and durable signed outbound notifications remain unchanged. No API operation, migration, permission, capability or business-domain contract is added or removed. PGM-10-E05 remains formally pending its exact hosted JDK25/JaCoCo and PostgreSQL 17/18 promotion gates; PGM-10-E06 remains in progress.

---

## 2.0.0-alpha.0.106

Corrective de qualification et d’administration : filtrage IAM par statut et réactivation visible des comptes suspendus, prévention de l’auto-verrouillage par suspension/suppression, menu utilisateur topbar, navigation DCIM verticale Location/Infrastructure, correction d’affichage avancé IAM, durcissement ReDoc/DataTables, correction du bootstrap Organization Server et renforcement des tests JaCoCo/JDBC/Security sans réduction du seuil 98 %. PGM-10-E05 reste formellement non terminé jusqu’aux gates hébergés JDK 25 et PostgreSQL 17/18.

# InfraNexum 2.0.0-alpha.0.105 — hosted coverage, DataTable and ReDoc corrective

`alpha.0.105` is a qualification/Web corrective over `alpha.0.104`; it does **not** advance the roadmap. It preserves the JaCoCo policy at **98 % lines and branches** with no production-class exclusion, disabled test or lowered threshold. The corrective expands executable branch regressions across Workers, Compatibility, Organization, Identity, RSOT, ITAM, DCIM, DDI and Integrations, and makes previously existing JDBC smoke contracts visible to Surefire/JaCoCo through JUnit wrappers rather than duplicating their assertions.

JDBC deterministic coverage is extended repository-by-repository across idempotency, IAM, Organization, local identity/session, RSOT, audit, workers, ITAM, DCIM, DDI, schema registry and the connector inbox. The auxiliary Java 21 condition probe executes **129 deterministic JDBC tests** with zero failures and observes both sides of **407/411** source conditions (about **99.4 % of condition sides**); this is a preflight diagnostic only, not a substitute for the mandatory hosted Temurin 25 JaCoCo result.

The Web DataTable contract now consumes the available content width without internal horizontal or vertical scrolling and exposes themed page sizes **20 / 50 / 100 / 200**. ReDoc receives the same-origin certified OpenAPI projection as parsed JSON, validates it before renderer initialization and converts a renderer fatal screen into a controlled documentation error. Web verification executes **194/194** tests at **99.73 % lines / 98.53 % branches / 100 % functions**. The i18n version regression no longer hard-codes a release number and instead follows the canonical Web package version.

Architecture invariants were updated only where stricter production validation legitimately replaced older implementation-shape assertions (required positive DCIM building floor counts and the bounded terminal `.*` RSOT authority wildcard guard). Split Architecture execution is **200/200** with Architecture-as-Code free of violations. Exact Temurin 25 Maven/JUnit/JaCoCo and live PostgreSQL 17/18 remain the formal PGM-10-E05 promotion gates.

---

# InfraNexum 2.0.0-alpha.0.104 — hosted JDK25 qualification corrective

`alpha.0.104` is a qualification corrective over `alpha.0.103`; it does **not** advance the roadmap. It consumes the complete hosted JDK 25/PostgreSQL diagnostics from the previous candidate and keeps the JaCoCo policy at **98 % lines and branches** with no class exclusions or disabled tests.

The connector inbox now stores the authenticated webhook body in a dedicated raw-text column while retaining JSONB/CLOB as the structured representation. This prevents PostgreSQL JSONB canonicalization from changing whitespace in signed payloads and therefore preserves durable replay/idempotency evidence. Forward-only migration `0035-integrations-connector-raw-payload` adds and backfills that raw column on PostgreSQL and Oracle without rewriting migrations `0033`/`0034`.

Server composition is also hardened: IAM HTTP controllers/handlers follow the same runtime enablement condition as their services, authenticated actor context is owned by the neutral HTTP boundary instead of the local-identity package, and correlation/problem components no longer create `http↔identity` or `http↔observability` Modulith cycles. Local-auth bootstrap secrets reject empty decoded files. Domain validation remains fail-closed by rejecting ISO control characters before normalization across Identity, Compatibility, ITAM, DCIM, DDI and Integrations.

Branch-oriented regression suites are expanded for Workers, Compatibility, Organization, Identity Local/Access, RSOT, ITAM, DCIM, DDI and Integrations. Local dependency-free preflight executes **386 Core/domain tests** and **56 deterministic JDBC tests**, all passing; the Web suite remains **192/192** at **99.73 % lines / 98.53 % branches / 100 % functions**. Exact Temurin 25 Maven/JUnit/JaCoCo and live PostgreSQL 17/18 remain the formal qualification authority before PGM-10-E05 can be promoted.

---

# InfraNexum 2.0.0-alpha.0.102 — CI closure and Web/UX consistency corrective

`alpha.0.102` is a corrective over `alpha.0.101`; it does **not** advance the roadmap or add a provider connector. It closes the hosted-JDK defects exposed after the PGM-10-E05 runtime implementation and aligns the Web shell with the validated product ergonomics.

Java/CI corrections keep the existing contracts intact: the schema-registry JUnit fixture no longer captures reassigned locals in lambdas, the Jackson 3 HTTP boundary catches its unchecked `JacksonException` rather than an impossible checked `IOException`, every injected Server `Clock` is explicitly `platformClock`-qualified, connector metric tags are distinguished from tracing span attributes, and `IdempotencyLedger.Entry` now has exhaustive branch-oriented coverage without reducing the JaCoCo 98 % gate.

Web/UX corrections make tab-header surfaces solid while preserving the established blue tone; table headers use one midnight-to-blue gradient painted by the complete `thead`, with white/turquoise high-contrast text and sort indicators. Identity & Access gives `Identity`, `Access control` and `Authorization policy` distinct contextual badges while managed entities remain neutral. Workspace headers, filters and data containers now share the same scanability rules as Overview. The login split widens the product-promise panel so `Operate infrastructure` remains on one line at normal desktop widths without shrinking the accepted typography, replaces the internal security-boundary copy with the dynamic Iriven Group copyright, and treats the initial session probe as advisory so a transient GET failure cannot contradict a working login POST.

ReDoc is repaired at the contract source: the invalid `#/src/components/schemas/EvaluationStatus` reference is corrected to `#/components/schemas/EvaluationStatus`, the generated Web OpenAPI projection is regenerated, and API governance now rejects malformed internal or unmanaged external `$ref` values before documentation can be published. PGM-05-E01 remains delivered with **15 fragments / 179 operations and debt 0/0/0/0**; PGM-10-E05 remains pending formal target-gate closure until `alpha.0.102` is verified under the exact hosted JDK/PostgreSQL matrix.

---

# InfraNexum 2.0.0-alpha.0.101 — PGM-10-E05 phase 2: durable connector runtime

`alpha.0.101` implements the second PGM-10-E05 tranche on top of the versioned Connector SDK delivered in `alpha.0.100`. The Server now has a generic connector runtime for **authenticated durable webhook admission, connector inbox processing, operational DLQ, controlled replay/resume and per-connector observability**. This remains provider-neutral: no Jira, ServiceNow or other vendor connector is invented.

Webhook endpoints are explicitly configured and fail closed. Secrets are never persisted in InfraNexum tables or configuration values: an endpoint references only an external `env:` or absolute `file:` secret. Admission validates the endpoint, JSON payload, bounded body, delivery ID, timestamp and HMAC-SHA256 signature before persistence. `(connector, delivery-id)` is a durable idempotency key: identical redelivery is accepted as duplicate while semantic drift returns a conflict. The authenticated raw payload is preserved byte-for-byte rather than normalized.

The inbox is implemented for PostgreSQL and Oracle with bounded leasing/`SKIP LOCKED`, at-least-once dispatch, retries with bounded backoff/jitter, dead-letter transition, automatic connector suspension and explicit operator resume. DLQ replay is allowed only from `DEAD_LETTER`, is RBAC-protected and audited, and never silently resumes a suspended connector. Persisted failure diagnostics contain only the exception class; webhook payloads and secret material are excluded from operator responses, audit metadata and metric labels.

OpenAPI now contains **15 fragments / 179 operations** and preserves the PGM-05-E01 zero-debt ratchet `0/0/0/0`. External webhook admission uses its own governed `connector-signature` authorization and `connector-delivery` idempotency modes rather than pretending to be an authenticated `Idempotency-Key` mutation. Migrations `0033` and `0034` add the durable runtime structures and IAM permissions symmetrically for PostgreSQL/Oracle.

Under the strict delivery policy, PGM-10-E05 is **not yet promoted to DELIVERED in this local artifact**: the exact Temurin 25 Maven/JUnit/JaCoCo run and live PostgreSQL 17/18 integration gate are defined in CI but cannot execute in the current runner. All locally available targeted gates pass; see `docs/integrations-connector-runtime.md` and `docs/implementation-status.md` for exact executed/non-executed status.

---

# InfraNexum 2.0.0-alpha.0.100 — PGM-10-E05 phase 1: versioned connector SDK

`alpha.0.100` starts **PGM-10-E05** with the connector-authoring boundary required by draft.21: a dependency-free Python 3.13 SDK v1, a strict `infranexum.connector-manifest/v1` contract, deterministic offline certification and HMAC-SHA256 webhook signing/verification primitives. Manifests make provider compatibility, capabilities/permissions, secret declarations, exact HTTPS egress, synchronization authority, data classification, idempotency/checkpoint/replay policy, resource limits and support lifecycle explicit; wildcard compatibility/egress and embedded secret values fail closed.

The SDK is independently versioned as `1.0.0`, rejects a manifest that requires a newer SDK, canonicalizes/fingerprints manifests and exposes them as deeply immutable data. The certification CLI never imports connector code. CI now has a dedicated `connector-sdk` job that enforces branch coverage ≥98 %, contract metadata consistency and byte-reproducible pure-Python wheel construction/import. The webhook helper uses bounded payloads, timezone-aware timestamps, constant-time HMAC comparison and replay guards; the in-memory guard is explicitly non-production.

This is **phase 1, not completion of PGM-10-E05**. No provider connector, Server route, migration or credential persistence is invented. Durable incoming webhook admission, connector inbox/DLQ operations, authorization-controlled audited replay and per-connector runtime observability remain the next phase of the same epic, reusing the Core transactional eventing delivered by PGM-02-E03. PGM-10-E06 and DNS/DHCP remain downstream until the runtime gate is closed. See `docs/integrations-connector-sdk.md`.

---

# InfraNexum 2.0.0-alpha.0.99 — enterprise CRUD navigation and embedded API documentation

`alpha.0.99` is a Web/UX corrective over the delivered `alpha.0.98` PGM-05-E01 baseline. Entity tabs are now **list-first**: a sortable DataTable is the default surface, `+ New` is shown only where the API supports creation, contextual row actions open one dedicated editor surface, successful mutations return to the same list, and user-initiated deletions require confirmation. Identity & Access receives the same tab-header treatment as the other workspaces, table headers use one restrained continuous surface, the duplicate environment indicator is removed from the topbar, and the previously accepted login product-promise proportions are restored.

A new **DOCUMENTATION** section under PLATFORM exposes **Swagger** and **ReDoc** inside the authenticated InfraNexum shell. Both views consume a deterministic Web projection of the certified 14-fragment / 174-operation OpenAPI product contract; an architecture test regenerates that projection and requires byte-for-byte equality. Swagger UI and ReDoc are version-pinned and styled through the InfraNexum design system as far as their supported theming APIs permit. This corrective does not alter PGM-05-E01 status, API debt (still `0/0/0/0`), migrations or authorization contracts. See `docs/web-enterprise-crud-and-api-documentation.md`.

---

# InfraNexum 2.0.0-alpha.0.98 — PGM-05-E01 delivered: zero-debt API contract governance

`alpha.0.98` completes **PGM-05-E01**. The canonical Server API catalogue now governs **14 OpenAPI 3.1 fragments and 174 operations**, including the previously undocumented Platform Runtime routes for build diagnostics, capability discovery and quotas. The contract ratchet is fully closed at **idempotency=0, pagination=0, capability=0, permission=0**.

Every operation declares a capability from the Core registry and a structured authorization mode rather than an arbitrary permission string. Permission-backed operations are checked against the IAM `PermissionCodes` registry and actual Server enforcement references; special boundaries are explicit (`anonymous`, `authenticated-self`, `organization-visibility`, `platform-admin`, `conditional`). Runtime publication is also capability-aware: `ApiCapabilityFilter` fail-closes unavailable business surfaces before authentication, while the minimal `platform.bootstrap` diagnostic/control surface remains available to avoid circular capability-registry gating.

The API governance tool can now generate both the deterministic product-complete contract and an **installation-effective OpenAPI contract** filtered from the installed capability set. A Java route-capability smoke checks every one of the 174 registered operations against the runtime resolver. With the roadmap exit gate — unique OpenAPI source, component/context tags and contract tests — satisfied, **PGM-05-E01 is DELIVERED** and the dependency on **PGM-10-E05** is unblocked. See `docs/api-platform-contract-governance.md`.

---

# InfraNexum 2.0.0-alpha.0.97 — PGM-05-E01 phase 4: canonical idempotency

`alpha.0.97` closes the historical idempotency debt of **PGM-05-E01** from **39 to 0**. Thirty-two IAM/RSOT mutations now require the canonical `Idempotency-Key` contract (8..200 safe characters). A durable Core ledger scopes keys by authenticated actor and operation, fingerprints method/path/query/body, replays completed successful responses, rejects key reuse with different semantics, and blocks automatic re-execution when a process interruption leaves a mutation `IN_PROGRESS` or `INDETERMINATE`. The ledger is implemented for PostgreSQL and Oracle by migration `0032-core-api-idempotency`.

Seven POST/DELETE operations are explicitly classified instead of being forced into unsafe generic replay semantics: authorization/permission/password-policy evaluations are `repeatable`; Local Auth session creation, session revocation and password rotation are `security-exempt` because replay persistence could retain or re-emit security-sensitive session/credential state. Existing Organization/ITAM/DCIM/DDI idempotency parameters are normalized to the same canonical header constraints. The API ratchet now reports **idempotency=0, pagination=0, capability=56, permission=85**. PGM-05-E01 remains **IN PROGRESS** until capability and permission metadata reach zero. See `docs/api-platform-contract-governance.md`.

---

# InfraNexum 2.0.0-alpha.0.96 — PGM-05-E01 phase 3: bounded pagination

`alpha.0.96` closes the historical pagination debt of **PGM-05-E01** from **15 to 0** without breaking existing list consumers. The 15 affected operations now declare and execute a bounded pagination strategy: **8 cursor/keyset** collections for mutable/high-volume DCIM/DDI data and **7 bounded-offset** collections for administration/reference sets. JSON list bodies remain arrays; continuation is additive through `X-Page-Limit`, `X-Next-Cursor` or `X-Next-Offset` headers. Offset pagination is bounded to **1,000,000** at the core contract, runtime, OpenAPI and Web adapter layers.

The Web adapters preserve their historical `payload` contract and additionally expose immutable `pagination` metadata so callers can follow continuation without manually parsing headers. The OpenAPI checker requires `x-infranexum-pagination: cursor|offset`, parameter bounds and continuation headers and the ratchet now reports **idempotency=39, pagination=0, capability=56, permission=85**. PGM-05-E01 remains **IN PROGRESS**; idempotency is the next debt-remediation phase before capability/permission metadata closure and PGM-10-E05. See `docs/api-platform-contract-governance.md`.

---

# InfraNexum 2.0.0-alpha.0.95 — PGM-05-E01 phase 2: canonical API problem runtime

`alpha.0.95` advances **PGM-05-E01** by making the error contract certified in phase 1 real at every Server HTTP boundary. MVC exception handlers and terminal correlation/authentication/RBAC/ABAC filters now use one `ApiProblem` representation and one `ApiProblemSupport` serializer. Every public problem carries the RFC 9457 core fields plus InfraNexum `code`, `occurred_at`, `correlation_id` and `trace_id`; the historical `message`, `details` and `timestamp` fields are preserved as compatibility aliases. Unexpected failures remain fail-closed and public text is redacted and bounded.

All registered OpenAPI 4xx/5xx responses now resolve to the same canonical `Problem` schema over `application/problem+json` and declare the `X-Correlation-ID` response header. The API contract checker resolves reusable response references and rejects drift in the canonical problem schema or correlation header. The existing PGM-05-E01 debt ratchet is unchanged at idempotency 39, pagination 15, capability 56 and permission 85; phase 2 therefore remains **IN PROGRESS**, with pagination/idempotency and operation metadata remediation still required before PGM-10-E05 can start. See `docs/api-platform-contract-governance.md`.

---

# InfraNexum 2.0.0-alpha.0.94 — dense enterprise filters and data-navigation refinement

`alpha.0.94` is a Web/UX corrective over the `alpha.0.93` PGM-05-E01 contract-governance foundation. Search, list-filter and scope-filter surfaces are consolidated into responsive `.inx-filter-bar` toolbars: controls stay in one horizontal line when desktop width permits and collapse predictably on narrow screens. Labels, controls and actions use a reduced vertical rhythm without changing native form values, stable-select semantics, temporal controls or API payloads.

Tables and tab navigation receive a stronger enterprise visual hierarchy using the InfraNexum/IONOS-derived Midnight, Blue, Turquoise, Green and Orange palette. Table headers use a high-contrast Midnight/Blue surface with restrained Turquoise/Green/Orange accents; tab bars use a subtle spectral surface and a clearly selected Midnight/Blue active state. The change is presentation-only: no route, migration, authorization rule, OpenAPI operation or PGM-05-E01 debt baseline is modified.

---

# InfraNexum 2.0.0-alpha.0.93 — PGM-05-E01 API contract governance foundation

`alpha.0.93` starts **PGM-05-E01** without pretending to complete it. The release registers all 13 Server OpenAPI 3.1 fragments (170 operations) in one canonical catalogue, introduces a product-wide fail-closed contract validator and generates one deterministic product-complete OpenAPI contract from the certified fragments. The generated contract is a build artifact; the registered fragments remain the only manually maintained API sources of truth.

The gate certifies release-version parity, global `operationId` and route uniqueness, component/context functional grouping, security declarations and `application/problem+json` documentation for every defined HTTP error. Historical gaps are frozen by exact operation ID in a non-expandable ratchet: idempotency 39, pagination 15, capability metadata 56 and permission metadata 85. These counts may only decrease in later PGM-05-E01 phases. No existing route, migration or authorization rule is removed or renamed in phase 1. See `docs/api-platform-contract-governance.md`.

---

# InfraNexum 2.0.0-alpha.0.92 — effective Settings, IAM navigation and login refinement

`alpha.0.91` is a Web/UX corrective over `alpha.0.90`. Settings now applies every persisted presentation preference to the live shell before closing: Page/Fluid layout, comfortable/compact density, responsive/expanded/compact navigation, operational refresh cadence and the shared Light/Dark theme contract. The primary sidebar keeps its established background surfaces; only text/icon states use the IONOS-derived palette. Identity & Access vertical facets use full-width uniform active surfaces with WCAG-readable text. The language control no longer depends on emoji flags and displays native language names plus alpha-2 (`Deutsch DE`, `English EN`, `Español ES`, `Français FR`, `Italiano IT`) with a single Bootstrap chevron. The login capability chips are removed and the core product promise is promoted with stronger, enterprise-grade typography. No business epic, API, migration or authorization contract changes are introduced.

---

# InfraNexum 2.0.0-alpha.0.90 — premium login, settings drawer and display controls

`alpha.0.90` is a corrective presentation/configuration release over `alpha.0.89`. Country selectors now display only localized country names while retaining ISO 3166-1 alpha-2 values; the language switcher exposes only flag + language code; the login screen is rebuilt as a product showcase with top-aligned InfraNexum identity and a high-contrast sign-in card; Settings is restored as a narrow right-side drawer and now controls Page/Fluid workspace layout plus the persisted Light/Dark theme; sidebar active/hover/focus/disabled states use the IONOS-inspired Midnight/Blue/Turquoise/Orange palette without blue-on-blue text. No business epic, API, database migration or authorization contract changes in this release.

The shell is designed as one coherent administration product rather than a set of independently themed Bootstrap pages: split-screen secure login, branded sidebar, responsive mobile drawer, glassy topbar, contextual hero, KPI surfaces, workspace frames, tables, tabs, forms, alerts, dropdowns and dialogs share the same spacing, radius, elevation, focus and state vocabulary. Custom classes are permitted only under the `inx-*` namespace; arbitrary third-party or unscoped presentation classes remain rejected by architecture tests.

The release also fixes the Windows/Chromium single-select regression in depth. The native `<select class="form-select">` remains present, owns the `name`, selected value, constraint validation and `FormData`, but single-select pointer interaction is rendered through an accessible `.inx-select` combobox/listbox. It cannot close merely because the mouse button is released: it closes only after an explicit option choice, Escape, Tab or a pointer interaction outside the component. Choosing an option writes the native value and dispatches native `input` and `change` events, preserving all Organization → Subdivision → Site and RSOT/ITAM/DCIM/DDI dependency flows. Dynamic catalogue replacement explicitly resynchronizes the visible control and `form.reset()` resynchronizes it after the browser resets the native value. Multi-selects remain native.

No business epic, migration, RBAC/ABAC rule or public API contract is added by this corrective. The dependency chain remains **PGM-05-E01 → PGM-10-E05 → PGM-08-E02/PGM-08-E03**.

---

# InfraNexum 2.0.0-alpha.0.87 — premium Bootstrap 5 administration experience

`alpha.0.87` was the first premium-admin corrective over `alpha.0.86`: it hardened asynchronous forms, entity cascades and the responsive administration shell while keeping a Bootstrap-only presentation contract. Operator feedback on Windows/Chromium then demonstrated that the native single-select picker still closed on pointer release and that suppressing the product-specific visual layer reduced the perceived quality of the interface. `alpha.0.88` supersedes that presentation decision without changing the delivered business capabilities.

---

# InfraNexum 2.0.0-alpha.0.85 — Server contract stabilization

`alpha.0.85` is a corrective release over `alpha.0.84`. The operator Java 25 Docker build confirmed the previous text-block repair and then exposed two stale Server composition contracts: DCIM Physical referenced the obsolete `Asset.organizationId()` accessor instead of the authoritative ITAM `Asset.owningOrganizationId()`, and DDI/IPAM still called an obsolete three-argument `JdbcRsotRepository` constructor even though the RSOT repository is read-only and its current contract is `(DataSource, JdbcDatabaseDialect)`. Both call sites are corrected against the existing domain/JDBC contracts; no public API, migration or business rule changes.

Regression tests now pin these two contracts explicitly, and a repository-wide constructor-arity scan covers all `Jdbc*Repository` instantiations in the Server composition root. The business dependency chain remains **PGM-05-E01 → PGM-10-E05 → PGM-08-E02/PGM-08-E03**; this corrective release does not claim a new epic.

Target Java 25 Maven/Docker compilation of `alpha.0.85` remains a promotion gate because the delivery runner provides Java 21 and cannot resolve the exact Temurin 25 archive. The operator Docker Desktop PRO build is therefore the authoritative next runtime verification.

---

# InfraNexum 2.0.0-alpha.0.84 — build stabilization and Bootstrap 5 Web contract

`alpha.0.84` is a corrective release over the `alpha.0.83` PGM-08-E01 DDI/IPAM baseline. It repairs malformed Java text-block openings in the DCIM Physical and DDI/IPAM Server CLIs that prevented the Java 25 Docker build from compiling, and hardens the developer Compose diagnostics so a build/start failure with no container is reported as such instead of being misreported as `health=unknown`.

The Web presentation layer is realigned on **Bootstrap 5.3.6 as the sole presentation contract**. InfraNexum-specific presentation classes and CSS variables are removed; the visual identity is applied only by overriding Bootstrap design tokens and native Bootstrap components. Alerts use Bootstrap contextual `alert` components, entity selectors remain authoritative native `<select class="form-select">` controls, and temporal values use native `date`/`datetime-local` inputs with `form-control`. The previous cloned combobox/calendar presentation layers are removed while existing API payloads, FormData semantics, dependent entity filtering, accessibility, capability gating and DE/EN/ES/FR/IT behavior remain intact.

No new business epic is claimed by this corrective release. The roadmap dependency chain remains **PGM-05-E01 → PGM-10-E05 → PGM-08-E02/PGM-08-E03** because PGM-10-E05 requires both PGM-02-E03 (already delivered) and PGM-05-E01 (still pending). DNS and DHCP therefore remain intentionally blocked.

`alpha.0.83` remains the implementation baseline for PGM-08-E01: VRFs, VLAN/VXLANs, address blocks/prefixes/subnets, pools, explicit reservations and atomic address allocation. See `docs/ddi-ipam.md` and `docs/implementation-status.md`.

---

`alpha.0.82` implements **PGM-07-E05** as a same-release vertical slice for multi-vendor equipment models, racks, rack-unit occupancy, installed equipment, physical ports and point-to-point cabling. DCIM owns physical placement and connectivity while RSOT, ITAM, Partner and Organization remain external authorities referenced through validated weak references. Equipment ports are instantiated from governed model templates rather than entered ad hoc.

The slice adds capability `dcim.physical`, 17 organization-scoped atomic permissions, paired PostgreSQL/Oracle migrations `0028`/`0029`, JDBC persistence with transactional rack/port locking, idempotency/outbox, HTTP/OpenAPI 3.1 with 14 native operations, Server CLI and **real Web administration in the same release**. The Web workspace extends DCIM with governed selectors for manufacturers, rooms, racks, models, RSOT objects, ITAM assets and cable endpoints; model/rack lifecycle and equipment move/install/cabling workflows are usable without free-form business UUIDs.

See `docs/dcim-physical-infrastructure.md`, `docs/dcim-facility-hierarchy.md`, `docs/web-functional-parity.md` and `docs/implementation-status.md`. Target-runtime promotion gates remain mandatory where the delivery environment cannot execute them.

---

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
