package io.infranexum.core.workers;

import io.infranexum.core.events.RetryPolicy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Executes at most one leased task per iteration and exposes lease heartbeat support. */
public final class TaskWorker {
    private final TaskStore store;
    private final TaskHandlerRegistry registry;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final String workerId;
    private final Duration leaseDuration;
    private final BooleanSupplier shutdownRequested;
    private final AtomicReference<ActiveExecution> active = new AtomicReference<>();

    public TaskWorker(
            TaskStore store,
            TaskHandlerRegistry registry,
            RetryPolicy retryPolicy,
            Clock clock,
            String workerId,
            Duration leaseDuration,
            BooleanSupplier shutdownRequested) {
        this.store = Objects.requireNonNull(store, "store");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.workerId = requireText(workerId, "workerId", 160);
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        this.shutdownRequested = Objects.requireNonNull(shutdownRequested, "shutdownRequested");
    }

    public synchronized WorkerIterationReport runOnce() {
        if (shutdownRequested.getAsBoolean()) {
            return WorkerIterationReport.idle();
        }
        List<TaskRecord> claimed = store.claimBatch(
                workerId, 1, clock.instant(), leaseDuration, retryPolicy);
        if (claimed.isEmpty()) {
            return WorkerIterationReport.idle();
        }
        TaskRecord task = claimed.getFirst();
        TaskExecutionContext context = new TaskExecutionContext(store, clock, leaseDuration, task);
        ActiveExecution execution = new ActiveExecution(context, Thread.currentThread());
        // runOnce() is synchronized so a worker can never claim a second task
        // while its first execution is active. This prevents a concurrent caller
        // from acquiring a lease and then abandoning it before execution.
        active.set(execution);
        try {
            TaskHandler handler = registry.find(task.type()).orElse(null);
            if (handler == null) {
                store.markTerminalFailure(
                        task.taskId(), workerId, task.leaseVersion(), clock.instant(),
                        new IllegalStateException("task handler is no longer registered: " + task.type()));
                return new WorkerIterationReport(1, 0, 0, 1, 0, 0);
            }
            handler.execute(context);
            if (context.leaseLost()) {
                return abandonedReport();
            }
            store.markSucceeded(task.taskId(), workerId, task.leaseVersion(), clock.instant());
            return new WorkerIterationReport(1, 1, 0, 0, 0, 0);
        } catch (TaskLeaseLostException lost) {
            context.markLeaseLost();
            return abandonedReport();
        } catch (TaskCancelledException cancelled) {
            if (context.leaseLost()) {
                return abandonedReport();
            }
            try {
                store.markCancelled(task.taskId(), workerId, task.leaseVersion(), clock.instant());
                return new WorkerIterationReport(1, 0, 0, 0, 1, 0);
            } catch (TaskLeaseLostException lost) {
                context.markLeaseLost();
                return abandonedReport();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (shutdownRequested.getAsBoolean() || context.leaseLost()) {
                return abandonedReport();
            }
            return failed(task, interrupted);
        } catch (Exception failure) {
            if (context.leaseLost()) {
                return abandonedReport();
            }
            return failed(task, failure);
        } finally {
            active.compareAndSet(execution, null);
        }
    }

    /** Renews the active lease; lease loss interrupts the handler to fail closed. */
    void heartbeat() {
        ActiveExecution execution = active.get();
        if (execution == null) {
            return;
        }
        try {
            execution.context().renewLease();
        } catch (RuntimeException failure) {
            // A terminal transition can race with the heartbeat after the handler has
            // returned. In that case the lease is intentionally gone and no execution
            // must be interrupted. Every other renewal failure is treated as lease loss.
            boolean terminal = false;
            try {
                terminal = store.find(execution.context().taskId())
                        .map(record -> record.status().terminal())
                        .orElse(false);
            } catch (RuntimeException lookupFailure) {
                failure.addSuppressed(lookupFailure);
            }
            if (!terminal) {
                execution.context().markLeaseLost();
                execution.thread().interrupt();
            }
        }
    }

    boolean active() {
        return active.get() != null;
    }

    private WorkerIterationReport failed(TaskRecord task, Throwable failure) {
        try {
            TaskStatus status = store.markFailed(
                    task.taskId(), workerId, task.leaseVersion(), clock.instant(), retryPolicy, failure);
            return switch (status) {
                case PENDING -> new WorkerIterationReport(1, 0, 1, 0, 0, 0);
                case CANCELLED -> new WorkerIterationReport(1, 0, 0, 0, 1, 0);
                case FAILED -> new WorkerIterationReport(1, 0, 0, 1, 0, 0);
                default -> throw new IllegalStateException("unexpected failure transition: " + status);
            };
        } catch (TaskLeaseLostException lost) {
            return abandonedReport();
        }
    }

    private static WorkerIterationReport abandonedReport() {
        return new WorkerIterationReport(1, 0, 0, 0, 0, 1);
    }

    private static String requireText(String value, String field, int maximumLength) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must contain 1-" + maximumLength + " characters");
        }
        return normalized;
    }

    private static Duration requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private record ActiveExecution(TaskExecutionContext context, Thread thread) {}
}
