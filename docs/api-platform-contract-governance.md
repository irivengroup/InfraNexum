# API Platform contract governance — PGM-05-E01 phase 1

## Status and scope

InfraNexum `2.0.0-alpha.0.94` starts **PGM-05-E01 — REST/OpenAPI standard, errors, pagination and idempotency** with the contract-governance foundation. This phase does **not** claim completion of the epic and does not change existing public route semantics. It establishes the canonical API catalogue, product-wide OpenAPI validation, deterministic consolidated product contract, and a fail-closed debt ratchet that prevents existing inconsistencies from spreading while later phases remediate them.

Canonical sources remain the registered OpenAPI 3.1 fragments under `src/applications/server/resources/openapi/` plus `catalogue.yaml`. The generated `artifacts/validation/openapi-product.yaml` is a certified build output, never a second manually maintained source of truth.

## Canonical catalogue

`src/applications/server/resources/openapi/catalogue.yaml` registers every public Server OpenAPI fragment with its functional component and business context. The catalogue is exhaustive: an unregistered `*.yaml` fragment or a catalogue entry without a corresponding file fails certification.

Current registered surface:

- 13 OpenAPI fragments;
- 170 operations;
- 120 distinct API paths;
- one global `operationId` namespace;
- deterministic component/context tag hierarchy.

The validator requires OpenAPI 3.1.x, release-version parity, unique routes and operation identifiers, functional tags, `x-tagGroups`, operation summaries, security declarations and structured error responses.

## Functional organization

OpenAPI navigation is business-oriented rather than implementation-oriented:

`component → business context/resource → operation`

Technical catch-all tags such as `Default`, `Misc`, `Utils`, `Helpers`, `Common`, `Divers`, `Autres` or `Général` are rejected. Each operation belongs to exactly one declared functional tag, and each declared tag belongs to exactly one group.

## Product-complete generated contract

`validation.api_contracts` can assemble all certified fragments into a single deterministic OpenAPI 3.1 product contract. Assembly:

- namespaces fragment-local reusable components to prevent collisions;
- rewrites local `$ref` targets;
- materializes inherited security on operations;
- merges paths only when method/path pairs are unique;
- merges functional tags and `x-tagGroups` deterministically;
- records the exact source fragment list.

Generate and validate with:

```bash
make api-contract-test api-contract-check
```

The generated artifacts are:

- `artifacts/validation/api-contracts.json` — machine-readable validation report;
- `artifacts/validation/openapi-product.yaml` — complete generated product contract;
- `artifacts/validation/api-contracts-coverage.txt` — validator coverage evidence.

Generated validation artifacts are not product sources and are excluded from published source archives.

## Error contract

Every documented HTTP 4xx/5xx response in the registered OpenAPI surface must resolve to `application/problem+json` or to a reusable response component that does so. This phase certifies the documentation contract. Runtime normalization of handlers that still emit legacy bodies remains an explicit PGM-05-E01 remediation item; it must be completed without breaking clients that consume the current `code`, `title`, `detail` or `message` fields.

## Pagination and idempotency ratchet

Historical API inconsistencies pre-date the platform standard. They are recorded by exact `operationId` in `validation/api_contracts/baseline.json`. The baseline is a **ratchet**, not an exemption:

- existing debt may be removed;
- existing debt may never be replaced by another operation;
- new debt is forbidden;
- an operation absent from the historical baseline but violating the standard fails `CHECK-API-028`.

Debt frozen at the start of PGM-05-E01:

Machine-readable summary: `idempotency=39, pagination=15, capability=56, permission=85`.

| Dimension | Historical operations |
|---|---:|
| Mutations without canonical `Idempotency-Key` declaration | 39 |
| List/search operations without recognized bounded pagination | 15 |
| Operations without explicit capability metadata | 56 |
| Operations without explicit permission metadata | 85 |

Later PGM-05-E01 phases must drive these counts monotonically toward zero.

For list/search operations the checker recognizes bounded cursor or offset controls already used by InfraNexum (`cursor`, `page_size`, `limit`, `offset`, `page`, `after_version`). The target contract remains cursor pagination for mutable/high-volume resources and bounded offset pagination only for administration/reference sets where justified.

## CI enforcement

`api-contract-test` runs branch coverage over the validator with the repository-wide minimum of 98%. `api-contract-check` validates the complete registered surface and generates the consolidated product contract. Both are part of `verify-foundation` and the GitHub Actions architecture job.

Certification fails on, among other conditions:

- duplicate YAML mapping keys;
- catalogue drift;
- OpenAPI version or release-version drift;
- undeclared/duplicate technical tags;
- route or global `operationId` collisions;
- missing summaries or security declarations;
- non-`problem+json` documented errors;
- unresolved local operation references;
- any increase in idempotency, pagination, capability or permission debt.

## Compatibility and next remediation phases

`alpha.0.94` is intentionally additive at the governance layer. Existing HTTP routes, payload schemas, status codes, authorization rules, migrations and Web clients are not removed or renamed by phase 1.

PGM-05-E01 remains **IN PROGRESS**. The following work remains before the epic can unblock PGM-10-E05:

1. normalize runtime error envelopes and correlation metadata on all handlers;
2. standardize bounded pagination contracts and response envelopes;
3. implement/reconcile idempotency semantics and replay conflict behavior for every sensitive mutation;
4. attach canonical capability and permission metadata to every protected operation and validate it against the authoritative registries;
5. certify route/runtime/OpenAPI coherence and effective-installation contract filtering;
6. reach zero ratchet debt and pass the complete target-runtime contract suite.

Only after those acceptance conditions are closed can PGM-05-E01 be marked delivered and `PGM-10-E05` become the next dependency target.
