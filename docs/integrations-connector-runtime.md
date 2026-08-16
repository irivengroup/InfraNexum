# PGM-10-E05 — Connector runtime phase 2

## 1. Purpose and boundary

`alpha.0.101` adds the provider-neutral runtime behind the Connector SDK v1. The runtime accepts signed webhooks, persists each delivery before acknowledging it, dispatches work asynchronously, exposes a bounded dead-letter queue (DLQ), supports explicit replay/resume operations and publishes low-cardinality connector metrics.

This runtime does **not** implement a vendor connector. Vendor-specific protocol mapping remains a plugin/connector responsibility built against the SDK contract.

## 2. Security model

### 2.1 Webhook endpoints

Each configured endpoint has a canonical connector key, handler name, external secret reference, maximum clock skew and enabled state. Enabled endpoints are startup-validated. Startup fails closed when the handler is absent or the referenced secret cannot be resolved.

Secret references support only:

- `env:VARIABLE_NAME` with a strict uppercase environment-variable name;
- `file:/absolute/path`, opened as a regular file with `NOFOLLOW_LINKS`; reads are bounded to 4097 bytes so an oversized file cannot trigger an unbounded allocation.

Resolved secret values are bounded to 32..4096 bytes and are never stored in the connector inbox, runtime state, OpenAPI model, audit metadata or logs.

### 2.2 Admission authentication

The webhook caller sends:

- `X-InfraNexum-Delivery-ID`;
- `X-InfraNexum-Timestamp` as an epoch-second value;
- `X-InfraNexum-Signature` as `sha256=<hex HMAC>`.

The HMAC input is the exact UTF-8 byte sequence `timestamp + "." + raw-payload`. The server enforces the configured clock-skew window and uses constant-time signature comparison. The request is rejected before persistence when authentication fails.

Only a JSON object or array is accepted. The body is bounded by configuration (default 1 MiB). The accepted raw JSON string is persisted without trimming or normalization so that the durable evidence corresponds to the authenticated body.

## 3. Durable idempotence

The durable identity is `(connector_key, external_delivery_id)`, enforced by a unique database constraint.

- First occurrence: stored as `PENDING` and acknowledged `202`.
- Same delivery ID and same SHA-256 payload fingerprint: returned as an idempotent duplicate.
- Same delivery ID with a different fingerprint: rejected as a semantic conflict (`409`).

The runtime intentionally documents **at-least-once processing**, not exactly-once execution. A leased delivery can be processed again after worker failure; connector handlers must therefore respect the SDK idempotence/checkpoint contract.

## 4. Inbox processing

The dispatcher claims a bounded batch using database row locking with `SKIP LOCKED`. Leases have a finite expiry and processing is decoupled from the HTTP request thread.

A successful handler invocation marks the delivery `PROCESSED` and clears the connector's consecutive dead-letter/suspension state. A failed invocation stores only the exception class, never its message or payload. Retry delay uses bounded exponential backoff with jitter. After the configured attempt limit the delivery transitions to `DEAD_LETTER`.

The scheduler prevents overlapping dispatch loops inside one Server process. Database leasing provides cross-node exclusion for the Pro/Enterprise Server cluster.

## 5. Automatic suspension

Each connector has durable runtime state. Repeated dead-letter transitions increment the consecutive-dead-letter counter atomically. PostgreSQL uses an atomic conflict update and Oracle closes the first-row insert race with a savepoint plus retry; concurrent dead letters therefore cannot silently lose the suspension threshold. When the configured threshold is reached, the connector receives a finite `suspended_until` value.

Claim queries exclude suspended connectors. Suspension is observable through the runtime API and metrics. A successful processed delivery resets the consecutive-dead-letter state.

An operator can explicitly resume a connector. Resume is permission-protected and audited.

## 6. DLQ and replay

The DLQ endpoint is offset-paginated and bounded. Operator responses expose identifiers, lifecycle state, timestamps, attempt/replay counts and the safe failure class, but never the webhook payload.

Replay is accepted only for a delivery currently in `DEAD_LETTER`; otherwise the repository raises an explicit state-conflict error. Replay returns the delivery to `PENDING`, resets processing attempts and increments the replay count. Replay does **not** resume a suspended connector; the operator must perform the separate resume action when appropriate.

Replay and resume operations require canonical `Idempotency-Key`, IAM permission enforcement, authenticated actor context and correlation context. Both append elevated audit events without payload or secret material.

## 7. API governance

Canonical OpenAPI source: `src/applications/server/resources/openapi/integrations-connectors.yaml`.

The fragment contributes five operations:

1. webhook admission — connector HMAC boundary;
2. list DLQ — `integrations.dlq.read`;
3. replay DLQ delivery — `integrations.dlq.replay`;
4. read connector runtime state — `integrations.connector.read`;
5. resume connector — `integrations.connector.resume`.

Webhook admission uses dedicated governance modes:

- authorization: `connector-signature`;
- idempotence: `connector-delivery`.

This avoids incorrectly requiring a local authenticated user or the generic human/API `Idempotency-Key` ledger at an external webhook boundary.

## 8. Database model

Migration `0033-integrations-connector-inbox` adds the inbox and connector runtime-state structures for PostgreSQL and Oracle, including status, payload fingerprint, lease, attempts, replay counter and suspension invariants.

Migration `0034-identity-access-integrations-permissions` adds the four operator permissions and grants them to the platform-administrator role according to the existing IAM migration model.

Both migrations provide PostgreSQL/Oracle `up`, `down`, verification metadata and canonical migration hashes.

## 9. Observability

`MicrometerConnectorRuntimeObserver` publishes low-cardinality metrics using only the configured connector key and bounded enumerated outcomes/reasons:

- backlog gauge;
- dead-letter gauge;
- webhook admissions/rejections;
- processing success/retry/dead-letter counters;
- processing latency;
- replay counter;
- suspension counter.

Rejection reasons are sanitized before becoming metric labels. Arbitrary exception messages, delivery IDs and payload values are never metric labels.

## 10. Configuration

`infranexum.integrations.enabled` defaults to `false`. Runtime parameters are typed and validated: payload limit, polling interval, lease, batch size, attempt limit, minimum/maximum backoff, jitter, suspension threshold/duration and endpoint definitions.

The durable runtime requires PostgreSQL or Oracle. MEMORY persistence fails closed rather than silently providing a non-durable production inbox.

## 11. Validation and promotion gate

Locally executed gates cover the SDK, OpenAPI governance, targeted architecture/RBAC/idempotence, migrations, capability catalogue, Compose contracts, Web regression suite, Java offline domain/JDBC/eventing/runtime smokes and strict Java 21 compilation of affected dependency-free/JDBC surfaces.

Promotion remains blocked until the exact target CI executes:

- Temurin 25.0.4+7 Maven/JUnit/JaCoCo Server and JDBC suites with project coverage thresholds;
- live PostgreSQL 17 and 18 repository tests, including durable idempotence, retry/DLQ/suspension and replay/resume behavior.

Because these checks are not executable in the current local runner, this document describes `alpha.0.101` as implemented phase 2 but does not certify PGM-10-E05 as fully delivered.
