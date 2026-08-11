# Core Transactional Events

This module implements the framework-independent transactional-event contracts used by InfraNexum bounded contexts.

## Guaranteed semantics

- Event envelopes use the canonical eight-field contract defined by `event-envelope.schema.json`.
- A local outbox record becomes visible only when its owning unit of work commits.
- Post-commit actions execute after committed state is visible and cannot roll back that commit.
- Delivery is explicitly **at least once**; consumers must be idempotent.
- Inbox deduplication uses `(consumerName, eventId)` as its durable key.
- Handler effects, the inbox receipt and any newly produced outbox events commit atomically.
- Claims are bounded and leased. Expired leases may be recovered.
- Retry delays are bounded and failures move to dead-letter state after the configured maximum attempts.
- Failure persistence records exception types, not exception messages, to reduce accidental secret disclosure.

The module does **not** claim exactly-once delivery or global ordering.

## Architecture boundary

`TransactionalEventStore` is the persistence port. `InMemoryEventStore` remains a deterministic, thread-safe reference adapter for contract tests and local smoke validation. Production JDBC persistence is implemented by `components.adapters.persistence-jdbc`, which uses one deployment-provided `DataSource` and one physical connection per unit of work.

Each bounded context owns its own unit of work, outbox and inbox tables. The migration under `distribution/migrations/0002-core-transactional-events` establishes the Core-owned reference schema only; it is not a cross-context shared business-event table.

## Remaining production work

The following capabilities are intentionally outside this increment:

- observed execution of the PostgreSQL 17/18 CI matrix and Oracle 19c/26ai laboratory suite;
- deployment packaging for maintained JDBC drivers, connection pooling and external secrets;
- Kafka 4.3.x KRaft transport adapter;
- durable broker-side dead-letter topics and replay workflow;
- audited replay authorization and operational tooling;
- scheduler/worker lifecycle integration and backpressure metrics.

## Validation

```bash
make eventing-test eventing-check java-eventing-smoke
./mvnw --batch-mode --no-transfer-progress verify
```

The Maven command requires the exact Java 25 toolchain declared in `toolchains.lock.json`.
