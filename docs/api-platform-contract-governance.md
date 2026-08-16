# API Platform contract governance — PGM-05-E01 phases 1–3

## Status and scope

InfraNexum `2.0.0-alpha.0.93` established the phase-1 contract-governance foundation for **PGM-05-E01 — REST/OpenAPI standard, errors, pagination and idempotency**. `2.0.0-alpha.0.95` added phase 2: the Server runtime emits the canonical problem contract certified by OpenAPI. `2.0.0-alpha.0.96` added phase 3: every historically unbounded list/search operation uses an explicit bounded cursor or offset contract. `2.0.0-alpha.0.97` adds phase 4: all historical idempotency debt is either protected by a canonical durable key contract or explicitly classified as repeatable/security-exempt. The epic remains **IN PROGRESS** while capability/permission metadata debt is remediated.

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

## Canonical error contract and runtime boundary

Every documented HTTP 4xx/5xx response in the registered OpenAPI surface resolves to `application/problem+json`, the canonical `#/components/schemas/Problem`, and an `X-Correlation-ID` response header. The checker resolves reusable response `$ref` values before validating this contract, so an indirect legacy response can no longer escape certification.

The Server runtime uses one immutable `ApiProblem` representation and one `ApiProblemSupport` factory/serializer across MVC exception handlers and terminal correlation, local-authentication, RBAC and ABAC filters. The canonical public fields are:

- RFC 9457 core: `type`, `title`, `status`, `detail`, `instance`;
- InfraNexum extensions: `code`, `details`, `metadata`, `occurred_at`, `correlation_id`, `trace_id`;
- compatibility aliases: `message` (alias of `detail`) and `timestamp` (alias of `occurred_at`).

Compatibility aliases remain present during PGM-05-E01 so existing clients do not break. New clients should consume the canonical fields. Problem text and structured details are sanitized through the platform redactor, public text is bounded, terminal filters use the same serializer as MVC, and unexpected exceptions return a generic fail-closed detail rather than internal exception data. `correlation_id` is taken only from the validated `CorrelationContext`; raw untrusted correlation headers are never reflected.

The checker additionally enforces the exact canonical `Problem` property/required set with `additionalProperties: false` and requires the reusable `CorrelationId` header contract.

## Pagination and idempotency ratchet

Historical API inconsistencies pre-date the platform standard. They are recorded by exact `operationId` in `validation/api_contracts/baseline.json`. The baseline is a **ratchet**, not an exemption:

- existing debt may be removed;
- existing debt may never be replaced by another operation;
- new debt is forbidden;
- an operation absent from the historical baseline but violating the standard fails certification.

Current machine-readable summary after phase 3: `idempotency=39, pagination=0, capability=56, permission=85`.

| Dimension | Remaining operations |
|---|---:|
| Mutations without canonical `Idempotency-Key` declaration | 39 |
| List/search operations without recognized bounded pagination | **0** |
| Operations without explicit capability metadata | 56 |
| Operations without explicit permission metadata | 85 |

### Phase-3 bounded pagination contract

The 15 historical pagination-debt operations are now partitioned by data semantics rather than forced into one mechanism:

- **cursor/keyset (8 operations):** DCIM racks, installed equipment and cables, plus IPAM VRFs, VLANs, networks, pools and addresses;
- **bounded offset (7 operations):** DCIM equipment models and ports, IAM user memberships and role assignments, ITAM support authorizations, warranty types and compliance alerts.

Cursor collections use the stable ordered identifier and a strict `id > cursor` predicate. Repositories fetch `limit + 1`, emit only `limit` items, and expose `X-Next-Cursor` only when another page exists. This avoids offset drift on mutable/high-volume collections.

Offset collections use zero-based `offset` plus bounded `limit`. `offset` is constrained to **0..1,000,000** in the core domain contract, Server runtime, OpenAPI and Web adapters so pathological scans cannot be requested accidentally or used as a simple application-level resource-exhaustion vector.

Every paginated operation declares `x-infranexum-pagination: cursor|offset`. The API checker validates the mode, parameter types/bounds and mandatory response headers:

- `X-Page-Limit` for both modes;
- `X-Next-Cursor` for cursor mode;
- `X-Next-Offset` for offset mode.

For backward compatibility, phase 3 deliberately preserves the existing **JSON array response bodies**. Continuation metadata is carried in response headers. Web adapters preserve their historical `payload` field and additionally expose immutable `pagination` metadata (`limit`, `nextCursor`, `nextOffset`, `hasNext`). Existing callers therefore continue to work while new callers can traverse pages explicitly.

The pagination ratchet is now zero and cannot increase. PGM-05-E01 still remains open because idempotency and capability/permission metadata debt are non-zero.

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

`alpha.0.97` remains additive/compatible at the API boundary. Existing HTTP routes, business status codes, authorization rules and JSON bodies are not removed or renamed. Phase 2 problem compatibility aliases and phase 3 pagination contracts remain available. Phase 4 requires a canonical `Idempotency-Key` only on the 32 IAM/RSOT mutations governed by the durable ledger; existing Organization/ITAM/DCIM/DDI domain-native idempotency semantics remain authoritative for those bounded contexts.

PGM-05-E01 remains **IN PROGRESS**. The following work remains before the epic can unblock PGM-10-E05:

1. implement/reconcile idempotency semantics and replay conflict behavior for every sensitive mutation;
2. attach canonical capability and permission metadata to every protected operation and validate it against the authoritative registries;
3. certify route/runtime/OpenAPI coherence and effective-installation contract filtering;
4. reach zero ratchet debt and pass the complete target-runtime contract suite.

Only after those acceptance conditions are closed can PGM-05-E01 be marked delivered and `PGM-10-E05` become the next dependency target.


## Phase 4 — canonical idempotency (`2.0.0-alpha.0.97`)

The mutation contract now distinguishes three explicit semantics. `required` operations expose the canonical required `Idempotency-Key` header (`^[A-Za-z0-9._:-]+$`, 8..200 characters); `repeatable` operations are side-effect-free POST evaluations that require no replay ledger; `security-exempt` Local Auth operations deliberately avoid generic replay because persisting/re-emitting session or credential rotation material would expand the security boundary.

Thirty-two IAM/RSOT mutations are protected by the Core API idempotency ledger introduced by migration `0032`. Keys are scoped by authenticated actor and operation. The request fingerprint includes method, request URI, raw query and body bytes. Successful 2xx/3xx results are stored and replayed with `X-Idempotent-Replay: true`; semantic key reuse returns `409 INFRANEXUM_IDEMPOTENCY_CONFLICT`. A reservation that cannot be proven completed is retained as `IN_PROGRESS`/`INDETERMINATE` and is never automatically expired, because a business transaction may already have committed before the process interruption. This deliberately prefers fail-closed manual recovery over duplicate mutation execution.

The phase-4 ratchet is `idempotency=0`, `pagination=0`, `capability=56`, `permission=85`.
