# Web Functional Parity — RSOT and ITAM

## Purpose

`2.0.0-alpha.0.80` closes the administration-Web parity gap for functionality already delivered by RSOT PGM-06-E03 and ITAM PGM-07-E01/E02/E03. It is a corrective integration release, not a new business epic.

For an InfraNexum feature intended for administration, a browser HTTP client alone is **not** Web support. Web support requires all applicable elements below to exist and remain connected to the real runtime contract:

1. capability-gated route and navigation;
2. usable workspace with loading, empty, success, restricted and error states;
3. list/detail views and the mutations supported by the Server contract;
4. governed entity selectors instead of free-form identifier entry;
5. server-side RBAC/ABAC as the authorization authority;
6. locale-aware dates/datetimes and DE/EN/ES/FR/IT labels;
7. accessibility-oriented native form semantics preserved by the InfraNexum widgets;
8. interaction tests proving that the route, workspace and workflows are wired.

## RSOT workspace

The `#/rsot` workspace is published only when `rsot.core` is effectively available. It provides:

- canonical RSOT object list/detail, filtered by Organization;
- schema-registry list/detail/create/update/compatibility/publish/deprecate;
- schema-profile list/detail/create/publish/deprecate;
- governed Organization and schema selections;
- temporal controls for lifecycle dates.

Canonical object reads are exposed by the Server as an organization-scoped read surface. They do not introduce a second RSOT authority. Permission `rsot.read` is the normative organization-scoped read authorization and is provisioned by migration `0025-identity-access-rsot-read-permission` for PostgreSQL and Oracle.

## ITAM workspace

The `#/itam` workspace is published when at least one ITAM capability is available. Individual panels remain capability-gated.

### Partners

The UI consumes the canonical `Partner` aggregate and exposes the existing role-filtered catalogues and governed lifecycle. Organization/Subdivision context drives available records and forms.

### Assets

The UI consumes canonical RSOT objects and Partner catalogues as selectors for asset relationships. It exposes existing asset lifecycle actions and custody history. Custodian identifiers are selected from the chosen context instead of entered as arbitrary UUIDs.

### Compliance

The UI exposes the warranty, software-license contract, support authorization, support coverage, warranty-type, revision-history and deadline-alert functions already delivered by PGM-07-E03. Manufacturer, publisher, provider, authorization and asset references come from governed catalogues. Raw license/product/serial keys remain outside the contract until PGM-13-E02 Secret Service/PKI/KMS exists.

Additional read operations for support authorizations and compliance record details are query surfaces over the existing ITAM Compliance authority. They do not create new aggregates.

## Hierarchical selectors

The Web follows the project-wide entity-entry rule:

- Organization is selected from the Organization directory;
- Subdivision options are filtered by the selected Organization;
- downstream RSOT, Partner, Asset and Compliance choices are refreshed against that context;
- identifier values remain the canonical wire values, while operators select named entities.

Free-form UUID inputs are not used for governed relationships in the new RSOT/ITAM workflows.

## Temporal controls and server timezone

Dates and datetimes use the InfraNexum temporal controls rather than arbitrary formatted text. The existing Server temporal contract remains authoritative: timezone-less local datetimes are resolved using the Server timezone and ambiguous/nonexistent wall times fail closed.

## Authorization model

Capabilities control publication of routes and panels. Server RBAC/ABAC remains authoritative for every protected read and mutation; the browser does not grant permissions. Where the current-session contract does not expose an exhaustive effective-permission catalogue, the UI surfaces a restricted state on `403` rather than guessing privileges.

Direct navigation to a capability-disabled top-level workspace is rejected by the shell and falls back to Overview.

## Contract corrections in alpha.0.80

The parity work exposed and fixes three defects that were previously hidden by client-only Web tests:

- canonical RSOT objects lacked the governed read surface required by the Asset selector;
- support authorizations lacked the catalogue read needed by Compliance forms;
- several OpenAPI Compliance response schemas were incomplete or did not match their Java DTOs.

The browser source tree also contained a literal NUL byte in the prior ITAM Asset client. It is removed and the Web test suite now rejects NUL bytes in browser `.mjs` and `.css` assets.

## Acceptance gates

The Web parity gate verifies, at minimum:

- RSOT and ITAM top-level routes and real workspaces exist;
- capability-disabled routes are not exposed;
- governed relationship fields are selectors, not raw UUID inputs;
- Organization/Subdivision hierarchy is wired;
- calendar/temporal controls are present;
- all five supported locales contain the new domain vocabulary;
- canonical RSOT and Compliance selector clients call the governed read APIs;
- browser assets contain no NUL bytes;
- full Web tests and process smoke pass without lowering coverage thresholds.

The exact JDK25/Maven, Node 24.19.0, Go 1.26.5, Docker Desktop PRO/HA and live PostgreSQL/Oracle gates remain separate promotion requirements when the delivery runner cannot execute them.
