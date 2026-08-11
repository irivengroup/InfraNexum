package io.infranexum.core.workers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Lease-fenced execution context exposed to one task handler invocation. */
public final class TaskExecutionContext {
    private final TaskStore store;
    private final Clock clock;
    private final Duration leaseDuration;
    private final TaskId taskId;
    private final TaskType taskType;
    private final Map<String, String> parameters;
    private final String workerId;
    private final long leaseVersion;
    private final AtomicReference<TaskCheckpoint> checkpoint;
    private final AtomicReference<Instant> leaseUntil;
    private final AtomicBoolean leaseLost = new AtomicBoolean();

    TaskExecutionContext(TaskStore store, Clock clock, Duration leaseDuration, TaskRecord claimed) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        Objects.requireNonNull(claimed, "claimed");
        if (claimed.status() != TaskStatus.RUNNING) {
            throw new IllegalArgumentException("execution context requires a running task");
        }
        this.taskId = claimed.taskId();
        this.taskType = claimed.type();
        this.parameters = claimed.parameters();
        this.workerId = claimed.leaseOwner();
        this.leaseVersion = claimed.leaseVersion();
        this.checkpoint = new AtomicReference<>(claimed.checkpoint());
        this.leaseUntil = new AtomicReference<>(claimed.leaseUntil());
    }

    public TaskId taskId() {
        return taskId;
    }

    public TaskType taskType() {
        return taskType;
    }

    public Map<String, String> parameters() {
        return parameters;
    }

    public Optional<TaskCheckpoint> checkpoint() {
        return Optional.ofNullable(checkpoint.get());
    }

    public boolean cancellationRequested() {
        if (leaseLost.get()) {
            return true;
        }
        Optional<TaskRecord> current = store.find(taskId);
        if (current.isEmpty()) {
            leaseLost.set(true);
            return true;
        }
        TaskRecord record = current.orElseThrow();
        if (record.status() != TaskStatus.RUNNING
                || record.leaseVersion() != leaseVersion
                || !workerId.equals(record.leaseOwner())) {
            leaseLost.set(true);
            return true;
        }
        return record.cancellationRequested();
    }

    public void throwIfCancellationRequested() throws TaskCancelledException {
        boolean cancellation = cancellationRequested();
        if (leaseLost.get()) {
            throw new TaskLeaseLostException("task lease was lost while executing " + taskId);
        }
        if (cancellation) {
            throw new TaskCancelledException("task cancellation was requested: " + taskId);
        }
    }

    /** Persists a resume token and renews the execution lease in the same store operation. */
    public TaskCheckpoint saveCheckpoint(String token) throws TaskCancelledException {
        throwIfCancellationRequested();
        Instant now = clock.instant();
        try {
            TaskCheckpoint saved = store.saveCheckpoint(
                    taskId, workerId, leaseVersion, token, now, leaseDuration);
            checkpoint.set(saved);
            leaseUntil.set(safeLeaseUntil(now));
            return saved;
        } catch (TaskLeaseLostException lost) {
            leaseLost.set(true);
            throw lost;
        }
    }

    void renewLease() {
        if (leaseLost.get()) {
            throw new TaskLeaseLostException("task lease was already lost: " + taskId);
        }
        Instant now = clock.instant();
        store.renewLease(taskId, workerId, leaseVersion, now, leaseDuration);
        leaseUntil.set(safeLeaseUntil(now));
    }

    Instant leaseUntil() {
        return leaseUntil.get();
    }

    void markLeaseLost() {
        leaseLost.set(true);
    }

    boolean leaseLost() {
        return leaseLost.get();
    }

    private Instant safeLeaseUntil(Instant now) {
        try {
            return now.plus(leaseDuration);
        } catch (RuntimeException error) {
            throw new IllegalStateException("lease time calculation failed", error);
        }
    }
}
