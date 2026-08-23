# Outbound signed-webhook notifications — PGM-10-E06 phase 3

## Scope and ownership

This phase adds durable **outbound** InfraNexum notifications delivered as signed HTTPS webhooks. InfraNexum is the authority for the emitted event and the durable delivery state. The destination remains an external consumer; the notification transport does not import provider data or write into Jira Assets/ServiceNow. Starting with `alpha.0.133`, the governed connector-sync runtime may also admit selected **operational lifecycle events** into this same durable notification outbox; that coupling is explicit and opt-in, not a provider synchronization contract.

Each configured endpoint declares only the information required by the Server runtime: a stable endpoint key, an HTTPS destination, an external secret reference, a bounded request timeout and an enabled flag. The authenticated Web UI deliberately exposes neither the destination URI nor the secret reference.

Delivery is **at least once**, not exactly once. InfraNexum therefore assigns one immutable delivery identifier to every endpoint/event admission and expects receivers to deduplicate by `X-InfraNexum-Delivery-ID`.

## Configuration and secret boundary

Notification secrets are never stored in migrations, browser state or API responses. An enabled endpoint must reference a secret through `env:` or absolute `file:` resolution. The resolved HMAC key must contain at least 32 bytes and is zeroized by the transport after use.

Example:

```yaml
infranexum:
  integrations:
    notifications:
      maximum-payload-bytes: 1048576
      endpoints:
        operations-webhook:
          destination: https://events.example.test/infranexum
          secret-reference: file:/run/secrets/infranexum-notification-hmac
          request-timeout: PT10S
          enabled: true
      sync-endpoint-keys:
        - operations-webhook
```

The example domain and secret path are non-production examples. Provision the secret outside the repository and mount/read it with the least privilege required by the Server process. Endpoint destinations are operator configuration: public APIs cannot submit or override a destination URL.

## Publication and durable idempotency

Publication requires:

- an event ID matching `[A-Za-z0-9][A-Za-z0-9._:-]{7,199}`;
- an event type matching `[a-z][a-z0-9]*(?:[._-][a-z0-9]+){1,15}`;
- a JSON object or array payload;
- a payload no larger than the configured maximum (hard product ceiling: 1 MiB);
- between 1 and 64 distinct configured endpoint keys.

The durable natural idempotency key is `(endpoint_key, event_id)`. Re-admitting the same event type and payload is recognized as a duplicate. Reusing the same event ID for different semantics is rejected as a conflict. The HTTP mutation boundary additionally requires an `Idempotency-Key` so network retries cannot accidentally amplify a caller operation.


## Automatic connector-sync operational events

`sync-endpoint-keys` is deliberately empty by default. Every configured key must identify an existing **enabled** notification endpoint; an unknown, disabled or duplicated subscription fails Server configuration closed. This prevents a pre-existing notification destination from silently receiving a new event class after upgrade.

The connector-sync operator boundary automatically admits only operationally significant states:

```text
integrations.sync.paused
integrations.sync.failed
integrations.sync.compensated
integrations.sync.compensation-failed
```

`RUNNING`, `SUCCEEDED` and `COMPENSATING` do not generate automatic notifications, avoiding routine success noise. The event ID is deterministically derived from the durable run ID, state, checkpoint revision and run update timestamp, so re-executing the same idempotent operator request re-admits the same notification event rather than amplifying deliveries.

The JSON payload is schema version `1.0` and is intentionally restricted to operational state: run ID, connector key, provider, direction, rollback strategy, status, checkpoint revision, bounded failure code, correlation ID and occurrence time. It excludes actor identity, provider credentials, cursor/checkpoint data, idempotency keys, request hashes and governed field lists.

Notification admission is **non-blocking relative to the already durable sync result**. A notification repository/configuration failure cannot rewrite a successful/paused/failed sync transition into an API mutation failure. Instead, admission failure increments `infranexum.integrations.sync.notifications{status,outcome=failed}` and emits a warning containing only safe identifiers and the exception class. The Server operations boundary also contains an unexpected notifier implementation failure and records `outcome=notifier-failure`. Operators should alert on either outcome because the operational event may not have entered the outbox.

## HTTP signature contract

For each delivery the transport computes the canonical byte sequence:

```text
<epoch-second>.<delivery-id>.<raw-body>
```

It signs those bytes with HMAC-SHA256 and sends:

```text
X-InfraNexum-Signature: sha256=<lowercase-hex-hmac>
X-InfraNexum-Timestamp: <epoch-second>
X-InfraNexum-Delivery-ID: <uuid-v7>
X-InfraNexum-Event: <event-type>
Content-Type: application/json
User-Agent: InfraNexum-Notification/2
```

The JDK transport requires HTTPS, refuses redirects, userinfo and non-443 explicit ports, and applies the endpoint request timeout. No InfraNexum session credential is forwarded to the destination.

### Receiver anti-replay procedure

A receiver should, in this order:

1. parse and validate the timestamp against its own bounded clock-skew tolerance;
2. reconstruct the canonical bytes from the received timestamp, delivery ID and **raw** request body;
3. verify `X-InfraNexum-Signature` with a constant-time comparison;
4. reject malformed or invalid signatures before processing the event;
5. deduplicate the delivery ID in durable receiver storage for at least the receiver's replay window;
6. process the event idempotently and return a 2xx status only after the receiver's acceptance boundary is satisfied.

Secret rotation is external to this phase. During a controlled rotation, the receiver may temporarily accept the old and new secret according to its own policy; InfraNexum must be switched to the new external secret source without storing either value in product configuration.

## Retry, DLQ and suspension

The dispatcher is bounded and coordinated across Server nodes through JDBC leasing/`SKIP LOCKED`. Local scheduling also prevents overlapping execution inside one Server process.

Transport classification is fail-closed:

- `2xx`: delivered;
- `408`, `425`, `429` and `5xx`: transient and eligible for bounded retry;
- any other non-2xx response: permanent rejection and immediate dead-letter transition;
- network I/O/interruption: transient; interruption restores the Java interrupt flag.

Transient failures use the shared bounded retry policy with backoff. When the attempt budget is exhausted, the delivery enters `DEAD_LETTER`. Repeated endpoint failures can suspend only that endpoint; one failing destination does not globally stop other destinations.

Replay is explicit and allowed only for a dead-letter delivery. **Replay does not resume a suspended endpoint.** Endpoint resume is a separate permissioned and audited operator action. This separation prevents a recovery action from silently changing runtime safety state.

## API and RBAC

All routes require capability `integrations.connectors`. They are registered in the Server authorization resolver and remain deny-by-default for unregistered verbs/paths.

```text
GET  /api/v1/integrations/notifications/endpoints
POST /api/v1/integrations/notifications/events
GET  /api/v1/integrations/notifications/dlq
POST /api/v1/integrations/notifications/dlq/{deliveryId}/replay
GET  /api/v1/integrations/notifications/endpoints/{endpointKey}/runtime
POST /api/v1/integrations/notifications/endpoints/{endpointKey}/resume
```

Permissions:

```text
integrations.notification.read
integrations.notification.publish
integrations.notification.replay
integrations.notification.resume
```

The four permissions are provisioned by migration `0037` and granted to the protected `system.platform_admin` bootstrap role. Publication, replay and resume are audited. Optional operator reasons are normalized and, when supplied, must contain 2–512 characters.

## Web behavior

The `Integrations` workspace exposes a notification section only when `integrations.connectors` is available. It provides:

- configured endpoint descriptors without destination or secret disclosure;
- event ID/type, endpoint selection and JSON payload publication;
- paginated DLQ inspection without payload bodies;
- explicit replay;
- runtime/backlog/dead-letter state;
- explicit resume;
- DE/EN/ES/FR/IT labels and states.

Mutations use the authenticated same-origin session, CSRF protection and an `Idempotency-Key`. The browser never receives a provider Bearer token, webhook HMAC key or raw secret reference.

## Observability

The runtime publishes low-cardinality Micrometer metrics per configured endpoint:

```text
infranexum.integrations.notifications.backlog
infranexum.integrations.notifications.dead_letters
infranexum.integrations.notifications.admissions{outcome=accepted|duplicate}
infranexum.integrations.notifications.deliveries{outcome=delivered|retry|dead_letter}
infranexum.integrations.notifications.replays{outcome=requested}
infranexum.integrations.sync.notifications{status=paused|failed|compensated|compensation-failed,outcome=admitted|failed|notifier-failure}
```

Audit records cover publication, replay and resume. Payload bodies and secret material are excluded from operator API representations, audit metadata and metric labels.

## Persistence, migrations and rollback

Migration `0036-integrations-outbound-notifications` creates the durable notification outbox and per-endpoint state for PostgreSQL and Oracle. Migration `0037-identity-access-notification-permissions` creates the four IAM permissions and protected platform-admin grants. Both have PostgreSQL/Oracle parity and verification contracts.

Rollback is destructive for notification state. Before rolling back:

1. stop notification publication and dispatcher activity;
2. inspect backlog and DLQ and retain any evidence required by the operator's retention policy;
3. back up the affected integration/IAM data if recovery may be required;
4. roll back `0037` before `0036`;
5. confirm that no Server version still references the removed permissions/tables.

The `0036` rollback drops the notification state/outbox tables; queued, delivered and dead-letter records therefore do not survive it.

## Operational verification

After configuring a non-production endpoint, verify:

1. Server startup fails explicitly for an enabled endpoint with a missing or weak secret source.
2. An unauthorized actor cannot list, publish, replay, read runtime state or resume endpoints.
3. Repeating an identical `(endpoint, event-id, event-type, payload)` admission does not create a second durable delivery.
4. Reusing the event ID with a different payload/type returns a conflict.
5. The receiver validates HMAC bytes exactly and deduplicates `X-InfraNexum-Delivery-ID`.
6. A 2xx receiver response marks the delivery delivered.
7. A transient status retries within the configured attempt budget; a permanent status dead-letters immediately.
8. A suspended endpoint requires explicit `resume`; replay alone does not clear suspension.
9. The Web UI/API never exposes destination URIs, secret references, secret values or payload bodies from DLQ records.
10. Backlog/dead-letter gauges and publication/replay/resume audits remain observable during the test.
11. With `sync-endpoint-keys` configured, a paused/failed/compensated run admits exactly one durable event per endpoint for the same durable run projection; success states remain silent.
12. Force notification admission failure and confirm the sync operation keeps its durable result while the `sync.notifications` failure metric becomes observable.

## OpenService boundary

PGM-10-E06 also names **OpenService**, but the `draft.21` architecture/CDC material available to this delivery does not identify an authoritative product/API, endpoint model, authentication mechanism or business schema for that connector. InfraNexum therefore does **not** fabricate an OpenService adapter in this phase. Implementing it requires a product decision or authoritative provider contract; until then, PGM-10-E06 remains open.
