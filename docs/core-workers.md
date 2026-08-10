# Core Workers — scheduler, leases, retry, checkpoints and shutdown

## Scope

`components/core/workers` is the first executable foundation for roadmap epic **PGM-02-E07**. It defines the domain/application contracts for one-shot background tasks and a bounded in-process worker runtime. The module deliberately does not introduce a message broker: durable scheduling is expressed through the `TaskStore` port so PostgreSQL/Oracle adapters can provide the same semantics without coupling the core to a persistence technology.

This increment is complete for the in-memory reference runtime, but **PGM-02-E07 remains NON TERMINÉ** until a durable JDBC `TaskStore` is implemented for PostgreSQL and Oracle and the Server composition root owns the worker-pool lifecycle.

## Invariants

### Bounded concurrency

`TaskWorkerPool` creates exactly `WorkerPoolConfiguration.concurrency` long-lived worker loops. A worker claims at most one task per iteration. Business executions are therefore bounded by configuration and are not submitted to an unbounded executor queue. The accepted concurrency range is 1–256.

### Idempotent scheduling

A submission is identified by `(TaskType, idempotencyKey)`. Replaying the same semantic request returns the original `TaskId`; reusing the key with different parameters, `notBefore` or retry-safety semantics fails with `IdempotencyConflictException`. The key is not treated as a generic deduplication hint: semantic drift is rejected.

### Retry safety

Every `TaskHandler` declares one of two contracts:

- `RETRY_SAFE`: the runtime may automatically retry after a handler failure or an expired lease, subject to `RetryPolicy.maximumAttempts()` and its bounded delay;
- `AT_MOST_ONCE`: automatic retry is forbidden. A handler failure is terminal, and an expired lease becomes `FAILED` with an explicit "outcome unknown" diagnostic because the external side effect may have completed before worker loss.

Exactly-once execution is not claimed.

### Claim leases and fencing

Every successful claim increments `leaseVersion` and records `leaseOwner` plus `leaseUntil`. All mutating execution operations require the exact task, owner and lease version. A stale worker therefore cannot checkpoint, complete or fail a task after lease recovery/reclaim; it receives `TaskLeaseLostException`.

Long-running executions are protected by heartbeat renewal. A heartbeat renewal failure is fail-closed: unless the task is already terminal, the execution context is marked lease-lost and its handler thread is interrupted.

### Checkpoints

`TaskExecutionContext.saveCheckpoint()` writes an opaque resume token with a monotonically increasing sequence and renews the lease in the same `TaskStore` operation. A checkpoint is rejected once cancellation has been requested. Persistence adapters must preserve this atomicity.

### Cancellation

Cancellation is cooperative and best-effort:

- a `PENDING` task transitions immediately to `CANCELLED`;
- a `RUNNING` task records `cancellationRequested=true`;
- handlers must call `throwIfCancellationRequested()` at safe interruption points, typically between bounded units of work or immediately before irreversible side effects;
- if a handler completes without observing a concurrent cancellation request, success may win that race. This is intentional and must not be represented as hard preemption.

### Lease-expiry recovery

Before every claim, expired running leases are reconciled atomically:

- cancelled execution → `CANCELLED`;
- retry-safe execution below its attempt ceiling → `PENDING` after retry backoff;
- retry-safe execution at its ceiling → `FAILED`;
- at-most-once execution → `FAILED`, outcome unknown and automatic retry forbidden.

Old lease holders are fenced by status plus `leaseVersion`.

### Shutdown

Shutdown has two phases:

1. stop new claims and wait `shutdownTimeout` for active handlers while heartbeats continue;
2. if the deadline expires, interrupt worker threads and wait one more bounded interval.

A non-cooperative handler cannot be forcibly killed safely by Java. The runtime therefore never reports false termination: `ShutdownReport.terminated=false` and pool state remains `STOPPING` while any worker thread is still alive. A subsequent `shutdown()` can observe eventual termination. `TERMINATED` means the worker and heartbeat executors have actually stopped.

## Configuration constraints

`WorkerPoolConfiguration` validates at construction time:

- concurrency: 1–256;
- all durations positive and no greater than 30 days;
- heartbeat strictly less than half the lease duration;
- bounded polling, lease and shutdown intervals.

Invalid configuration fails explicitly; there is no silent degraded mode.

## Persistence contract for the next increment

A production `TaskStore` must provide atomic, transactionally durable implementations of:

- idempotent submission;
- ordered due-task claim with concurrency-safe row locking;
- lease renewal and lease-version fencing;
- checkpoint + lease renewal;
- terminal transitions;
- retry/backoff transition;
- cancellation request;
- lookup and deterministic lease-expiry recovery.

The PostgreSQL and Oracle implementations must expose equivalent behavior, paired migrations and concurrency tests. Database-specific locking syntax belongs in the persistence adapter, not this core module.

## Verification

The module contains JUnit contract tests with JaCoCo gates fixed at **98% line and 98% branch coverage**. A dependency-free `java-workers-smoke` additionally compiles all required Core sources with `javac -Xlint:all -Werror` and executes representative idempotency, checkpoint, retry, at-most-once, cancellation, lease-fencing, heartbeat, bounded-concurrency and forced-shutdown scenarios.

The Foundation CI architecture job is required by the toolchain validator to execute this smoke under the exact project Java toolchain.
