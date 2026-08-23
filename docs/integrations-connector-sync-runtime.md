# Connector synchronization runtime — PGM-10-E06 phase 5

## Scope

The provider-neutral synchronization runtime provides durable runs, revisioned checkpoints, bounded batch execution, pause/resume, active-run fencing and governed compensation. PGM-10-E06 now layers two provider implementations on top of it: ITAM → Jira Assets and ITAM → ServiceNow OUTBOUND upsert handlers, each admitted only by an exact provider mapping plus `INFRANEXUM/PREFER_AUTHORITY/IGNORE/MANUAL` governance.

The runtime implements the roadmap requirements for cursor-based resume, idempotence, deduplication and checkpoints without claiming exactly-once execution.

## Execution model

A synchronization request contains a governed mutating direction (`INBOUND`, `OUTBOUND` or `BIDIRECTIONAL`), an explicit governed field set, deletion propagation choice and a bounded batch budget. Before a handler is invoked, `ConnectorGovernancePlanner` must admit the request. A missing handler, policy mismatch, unsupported direction or ambiguous field authority fails closed.

`ConnectorSyncEngine` then:

1. admits or deduplicates the run by connector and idempotency key;
2. fences concurrent runs for the same connector;
3. loads the current durable cursor and checkpoint revision;
4. calls the approved provider handler with a bounded batch context;
5. appends a checkpoint after every successful batch;
6. marks the run `SUCCEEDED`, `PAUSED` or `FAILED` truthfully;
7. performs governed compensation when a partial mutation requires it.

A retryable provider failure pauses the run instead of fabricating success. A permanent failure is persisted as failed. Exhausting the configured batch budget also pauses the run so a later resume continues from the durable cursor.

## Exactly-once boundary

InfraNexum does **not** assume exactly-once delivery or execution. Provider handlers must be idempotent when the same durable cursor is presented again after a crash or uncertain remote outcome. The durable state is designed for at-least-once retry with explicit deduplication and fencing.

## Durable state and checkpoints

Migration `0038-integrations-connector-sync-runtime` adds PostgreSQL and Oracle representations for:

- connector synchronization state;
- synchronization runs;
- append-only checkpoints.

The current connector state stores the internal raw cursor, its SHA-256 digest, the monotonic revision and the active run fence. The raw cursor is an internal persistence concern and is never exposed by the public HTTP or Web contracts.

Each checkpoint records:

- connector and run identifiers;
- monotonic revision;
- `PROGRESS` or `COMPENSATION` kind;
- cursor SHA-256;
- processed/changed/rejected counters;
- correlation and creation timestamp.

Checkpoint history is append-only. Existing revisions are never rewritten.

## Compensation

Compensation is real runtime behavior, not only a governance label. The engine invokes the approved handler compensation path for strategies that require executable recovery.

A successful compensation appends a new `COMPENSATION` checkpoint whose cursor restores the run's initial durable cursor. History remains intact. A compensation is rejected if a newer run has already advanced the connector beyond the revision owned by the run being compensated; an old rollback can therefore never overwrite later progress.

`MANUAL` remains an explicit operator recovery state. `NONE_REQUIRED` is valid only for genuinely non-mutating flows and cannot be used to claim rollback of a mutating run.

## Handler registry and provider safety

`ConnectorSyncHandlerRegistry` is the only execution bridge to provider-specific mutation code. Startup validation rejects a registered handler whose connector governance policy is not mutating.

`ConfiguredJiraAssetsSyncHandlerCatalog` and `ConfiguredServiceNowSyncHandlerCatalog` are the current provider-specific mutating catalogs. Each registers a handler only when its provider is enabled, an explicit mutation mapping exists, persistence is PostgreSQL/Oracle, execution is enabled and direction/authority/conflict/deletion/rollback/field mappings match exactly. ServiceNow additionally requires the immutable local `id` to map to a custom `u_*` CMDB column.

## API and RBAC

The runtime adds five operations:

```text
GET  /api/v1/integrations/sync/runs
GET  /api/v1/integrations/sync/{connectorKey}/checkpoints
POST /api/v1/integrations/sync/{connectorKey}/execute
POST /api/v1/integrations/sync/runs/{syncRunId}/resume
POST /api/v1/integrations/sync/runs/{syncRunId}/compensate
```

They require capability `integrations.connectors` and PLATFORM-scoped permissions introduced by migration `0039`:

- `integrations.sync.read`;
- `integrations.sync.execute`;
- `integrations.sync.compensate`.

The three mutations require `Idempotency-Key`. The two collections are offset-paginated and bounded. Unsupported paths and verbs remain unregistered and deny-by-default.

Public run responses exclude idempotency keys, request hashes and raw cursors. Public checkpoint responses expose only the cursor SHA-256.

## Audit and observability

Execute, resume and compensate are audited with actor and correlation context. Operator reasons are mandatory and bounded to 3–500 characters.

The synchronization engine now emits provider-neutral runtime telemetry through `ConnectorSyncRuntimeObserver`, with a Micrometer adapter in the Server process. Metrics cover admission/deduplication, resume activations, applied batches, processed/changed/rejected record counts, bounded pause causes, compensation starts, terminal states and terminal duration. The emitted series are:

- `infranexum.integrations.sync.operations`;
- `infranexum.integrations.sync.admissions`;
- `infranexum.integrations.sync.activations`;
- `infranexum.integrations.sync.batches`;
- `infranexum.integrations.sync.records`;
- `infranexum.integrations.sync.pauses`;
- `infranexum.integrations.sync.compensations`;
- `infranexum.integrations.sync.terminal`;
- `infranexum.integrations.sync.duration`.

Metric dimensions are deliberately restricted to configured connector keys and enums owned by InfraNexum (`direction`, terminal `status`, pause `cause`, rollback strategy and fixed outcomes). Raw cursors, provider failure messages/codes, payloads, request hashes, idempotency keys, actor IDs and correlation IDs are never metric labels. This prevents provider-controlled or per-request values from creating unbounded time-series cardinality.

## Web behavior

The Integrations workspace displays synchronization runs and checkpoint hashes. Mutating execution is offered only for policies whose direction is mutating and whose execution admission is enabled. A correctly configured Jira OUTBOUND policy can therefore execute through the generic Sync UI; provider-specific Jira and ServiceNow workspaces remain read-only.

Resume and compensation require the operator reason field. Browser requests are same-origin, CSRF-protected for mutations and use `Idempotency-Key`. Provider bearer tokens, secret references and raw cursors never reach the browser.

## MEMORY and durable modes

The synchronization repository, engine, operations service and HTTP controller are composed only for durable PostgreSQL or Oracle persistence. MEMORY mode intentionally omits the durable synchronization boundary instead of pretending to provide restart-safe checkpoints.

## Operational verification

A safe test plan is:

1. start with no Jira mutation mapping and verify Jira Assets/ServiceNow execution is unavailable;
2. verify run/checkpoint collections are authorization-gated and secret-free;
3. in a test-only approved handler, execute multiple batches and verify monotonic checkpoints;
4. interrupt after a checkpoint and verify resume continues from the persisted cursor;
5. repeat the same execute idempotency key and verify no duplicate run is created;
6. attempt a concurrent run for the same connector and verify fencing rejects it;
7. exercise a retryable failure and verify `PAUSED` rather than success;
8. exercise compensation and verify a new append-only compensation checkpoint restores the prior cursor;
9. advance the connector with a later run, then verify compensation of the older run is refused;
10. inspect browser/API output and confirm raw cursors and credentials are absent.

## Controlled tombstones

The provider-neutral request already carries `propagateDeletions`. Jira Assets and ServiceNow now admit that flag only when their execution policy is `TOMBSTONE` and an explicit provider tombstone marker is configured. The ITAM outbound source marks only `DISPOSED` assets as deleted. A matching remote object is updated with the single configured marker; no remote object is created solely to represent a deletion and no physical `DELETE` is issued. With the flag disabled, disposed records are rejected and the checkpoint counters make that visible.

## Remaining PGM-10-E06 work

The runtime plus governed Jira Assets and ServiceNow OUTBOUND handlers, including controlled tombstones, are implemented, but PGM-10-E06 remains **EN COURS**. Remaining work includes inbound/bidirectional contracts if required, end-to-end live provider certification, and OpenService once an authoritative provider/API contract exists.

PGM-10-E05 also remains formally open until its exact hosted JDK25/JaCoCo and PostgreSQL 17/18 gates are proven.
