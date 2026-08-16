# InfraNexum Web enterprise CRUD and API documentation contract

## Release

This contract is introduced by `2.0.0-alpha.0.100` as a Web/UX corrective over the delivered PGM-05-E01 API foundation. It does not add a business epic, route, database migration or authorization permission.

## Tab and entity navigation

An entity tab is list-first. Its default surface is a sortable DataTable plus compact filter/context controls. Mutating forms are not stacked below the table.

For an entity that supports creation, the list toolbar exposes `+ New`. A contextual `Actions` column contains only operations supported by the entity and its current state. Create, edit and lifecycle actions open one dedicated editor surface at a time. A successful mutation emits the shared form/action success event and returns the operator to the same entity list. Row selection alone never opens an editor.

Read-only or derived resources do not receive fabricated mutation controls. They may expose `Open` for a read-only governed detail surface. User-initiated deletion, including a lifecycle transition whose target is `deleted`, requires confirmation before the mutation is sent.

All current DataTables support keyboard-accessible client-side sorting from non-action headers. Sorting is stable and the `Actions` header is deliberately non-sortable.

## Visual hierarchy

Identity & Access uses the same spectral tab-header treatment as the other workspaces. Table headers use one restrained continuous Blue/Turquoise surface across the complete header row; individual first/last columns are not colored independently. The primary data cells remain neutral so status badges and contextual actions carry meaning without visual noise.

The topbar does not repeat the environment label already available from Overview/runtime. The login product promise `Operate infrastructure with clarity.` uses the earlier product-showcase proportions restored from the prior accepted design.

## API documentation navigation

The primary sidebar contains a `DOCUMENTATION` section after `PLATFORM`, with `Swagger` and `ReDoc` routes rendered inside the authenticated InfraNexum shell.

The OpenAPI file served to both views is `assets/generated/infranexum-openapi.yaml`. It is a deterministic generated projection of the canonical Server OpenAPI catalogue and is protected by an architecture test that regenerates the product contract and requires byte-for-byte equality. It is never a second manually maintained API source of truth.

The current renderer integration pins Swagger UI `5.32.13` and ReDoc CE `2.5.3`. Renderer JavaScript/CSS is lazy-loaded from the explicitly allow-listed `cdn.jsdelivr.net` origin. The local OpenAPI document remains available even if the renderer cannot be loaded. Swagger mutation submission is disabled in the embedded viewer; ReDoc receives InfraNexum light/dark palette tokens and re-renders when the product theme changes.

The runtime serves `.yaml`/`.yml` documentation assets with `application/yaml; charset=utf-8` and keeps the rest of the existing Web security headers. Renderer availability and pixel-perfect visual integration must still be verified in the operator browser because the delivery runner does not execute a full graphical browser session.
