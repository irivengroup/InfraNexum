# Core Workers — scheduler, leases, retry, checkpoints and shutdown

## Scope

`src/components/core/workers` is the first executable foundation for roadmap epic **PGM-02-E07**. It defines the domain/application contracts for one-shot background tasks and a bounded in-process worker runtime. The module deliberately does not introduce a message broker: durable scheduling is expressed through the `TaskStore` port so PostgreSQL/Oracle adapters can provide the same semantics without coupling the core to a persistence technology.

The in-memory reference runtime and the durable JDBC adapter are implemented. **PGM-02-E07 remains NON TERMINÉ** until the Server composition root owns the worker-pool lifecycle/readiness/metrics and target database execution, including Oracle, is proven.

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

If the thread calling `shutdown()` is already interrupted, or becomes interrupted while waiting, the pool records that signal without allowing it to short-circuit the remaining bounded cleanup waits. Forced cleanup still runs, the report reflects the actual executor state, and the caller interruption flag is restored immediately before `shutdown()` returns. This keeps shutdown semantics deterministic across JDK implementations while preserving Java interruption semantics.

## Configuration constraints

`WorkerPoolConfiguration` validates at construction time:

- concurrency: 1–256;
- all durations positive and no greater than 30 days;
- heartbeat strictly less than half the lease duration;
- bounded polling, lease and shutdown intervals.

Invalid configuration fails explicitly; there is no silent degraded mode.

## Durable persistence — alpha.0.19

`src/components/adapters/jdbc` (`JdbcTaskStore`) implements the same `TaskStore` contract for PostgreSQL and Oracle. Each mutation owns a short transaction. Expired leases are reconciled first through a bounded optimistic compare-and-set keyed by `task_id`, `lease_version`, expiration and cancellation state; a concurrent zero-row update is benign, while any multi-row update fails closed. Due rows are then claimed with `FOR UPDATE SKIP LOCKED`, transitioned to `RUNNING`, assigned a new owner, and advanced through the monotonically increasing `leaseVersion`. The adapter reconstructs the immutable `TaskRecord` including parameters after the claim transaction.

Migration `0006-core-workers` stores task parameters in a child table instead of opaque database-specific JSON. This keeps the logical schema equivalent across PostgreSQL and Oracle and preserves exact semantic comparison for idempotent replay. PostgreSQL uses bounded character columns for the 4096-character token/value contract. Oracle uses `CLOB` for those two fields and dedicated triggers for LOB-dependent length/tuple invariants. Relational constraints still enforce retry/status values, lease state, checkpoint sequence/time coherence and cancellation markers.

All execution mutations are fenced by `(task_id, lease_owner, lease_version)`. A zero-row mutation performs a diagnostic state read so an unknown task remains distinguishable from a stale lease. Checkpoints are rejected after cancellation has been requested. Expired `AT_MOST_ONCE` tasks become terminal `FAILED` with an explicit unknown-outcome diagnostic and are never automatically reclaimed.

The JDBC recovery pass is capped at 1,000 expired leases per claim transaction; task claims themselves remain capped at 1,000. This prevents an unbounded maintenance transaction from monopolizing the scheduler under a large backlog.

## Verification

The module contains JUnit contract tests with JaCoCo gates fixed at **98% line and 98% branch coverage**. Dependency-free `java-workers-smoke` and `java-jdbc-workers-smoke` additionally compiles all required Core sources with `javac -Xlint:all -Werror` and executes representative idempotency, checkpoint, retry, at-most-once, cancellation, lease-fencing, heartbeat, bounded-concurrency and forced-shutdown scenarios.

The Foundation CI architecture job is required by the toolchain validator to execute this smoke under the exact project Java toolchain.
