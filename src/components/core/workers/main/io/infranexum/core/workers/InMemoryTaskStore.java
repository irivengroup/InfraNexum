package io.infranexum.core.workers;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.RetryPolicy;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe bounded reference implementation of {@link TaskStore}.
 *
 * <p>This implementation is intentionally in-memory and is suitable for contract tests,
 * local development and non-persistent profiles only. Production persistence adapters
 * must provide the same atomic claim, lease fencing, retry and checkpoint semantics.
 */
public final class InMemoryTaskStore implements TaskStore {
    private static final int DEFAULT_MAXIMUM_TASKS = 100_000;
    private static final int MAX_BATCH_SIZE = 1_000;
    private static final int MAX_FAILURE_LENGTH = 1_024;

    private final ReentrantLock lock = new ReentrantLock(true);
    private final int maximumTasks;
    private final Map<TaskId, MutableTask> tasks = new LinkedHashMap<>();
    private final Map<IdempotencyScope, TaskId> idempotency = new LinkedHashMap<>();

    public InMemoryTaskStore() {
        this(DEFAULT_MAXIMUM_TASKS);
    }

    public InMemoryTaskStore(int maximumTasks) {
        if (maximumTasks < 1 || maximumTasks > 10_000_000) {
            throw new IllegalArgumentException("maximumTasks must be between 1 and 10000000");
        }
        this.maximumTasks = maximumTasks;
    }

    @Override
    public TaskSubmissionResult submit(
            TaskId proposedId,
            TaskSubmission submission,
            RetrySafety retrySafety,
            Instant submittedAt) {
        return submit(proposedId, submission, retrySafety, null, submittedAt);
    }

    @Override
    public TaskSubmissionResult submit(
            TaskId proposedId,
            TaskSubmission submission,
            RetrySafety retrySafety,
            DomainIdentifier correlationId,
            Instant submittedAt) {
        Objects.requireNonNull(proposedId, "proposedId");
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(retrySafety, "retrySafety");
        Objects.requireNonNull(submittedAt, "submittedAt");
        IdempotencyScope scope = new IdempotencyScope(submission.type(), submission.idempotencyKey());
        lock.lock();
        try {
            TaskId existingId = idempotency.get(scope);
            if (existingId != null) {
                MutableTask existing = tasks.get(existingId);
                if (!existing.matches(submission, retrySafety)) {
                    throw new IdempotencyConflictException(
                            "idempotency key was already used with different task semantics: "
                                    + submission.idempotencyKey());
                }
                return new TaskSubmissionResult(existingId, false);
            }
            if (tasks.size() >= maximumTasks) {
                throw new TaskCapacityExceededException("task store capacity reached: " + maximumTasks);
            }
            if (tasks.containsKey(proposedId)) {
                throw new IllegalArgumentException("task identifier already exists: " + proposedId);
            }
            MutableTask task = MutableTask.create(proposedId, submission, retrySafety, correlationId, submittedAt);
            tasks.put(proposedId, task);
            idempotency.put(scope, proposedId);
            return new TaskSubmissionResult(proposedId, true);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<TaskRecord> claimBatch(
            String workerId,
            int limit,
            Instant now,
            Duration leaseDuration,
            RetryPolicy retryPolicy) {
        String worker = requireText(workerId, "workerId", 160);
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH_SIZE);
        }
        Objects.requireNonNull(now, "now");
        Duration lease = requirePositive(leaseDuration, "leaseDuration");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        lock.lock();
        try {
            recoverExpiredLeases(now, retryPolicy);
            List<MutableTask> candidates = tasks.values().stream()
                    .filter(task -> task.status == TaskStatus.PENDING && !task.availableAt.isAfter(now))
                    .sorted(Comparator.comparing((MutableTask task) -> task.availableAt)
                            .thenComparing(task -> task.createdAt)
                            .thenComparing(task -> task.taskId))
                    .limit(limit)
                    .toList();
            Instant leaseUntil = safeAdd(now, lease);
            List<TaskRecord> claimed = new ArrayList<>(candidates.size());
            for (MutableTask task : candidates) {
                task.status = TaskStatus.RUNNING;
                task.attempts++;
                task.leaseOwner = worker;
                task.leaseVersion++;
                task.leaseUntil = leaseUntil;
                task.updatedAt = now;
                claimed.add(task.snapshot());
            }
            return List.copyOf(claimed);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void renewLease(
            TaskId taskId,
            String workerId,
            long leaseVersion,
            Instant now,
            Duration leaseDuration) {
        Objects.requireNonNull(now, "now");
        Duration lease = requirePositive(leaseDuration, "leaseDuration");
        lock.lock();
        try {
            MutableTask task = requireLease(taskId, workerId, leaseVersion);
            task.leaseUntil = safeAdd(now, lease);
            task.updatedAt = now;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public TaskCheckpoint saveCheckpoint(
            TaskId taskId,
            String workerId,
            long leaseVersion,
            String token,
            Instant now,
            Duration leaseDuration) {
        Objects.requireNonNull(now, "now");
        Duration lease = requirePositive(leaseDuration, "leaseDuration");
        lock.lock();
        try {
            MutableTask task = requireLease(taskId, workerId, leaseVersion);
            if (task.cancellationRequested) {
                throw new IllegalStateException("cannot checkpoint after cancellation was requested");
            }
            long sequence = task.checkpoint == null ? 1 : Math.addExact(task.checkpoint.sequence(), 1);
            TaskCheckpoint checkpoint = new TaskCheckpoint(sequence, token, now);
            task.checkpoint = checkpoint;
            task.leaseUntil = safeAdd(now, lease);
            task.updatedAt = now;
            return checkpoint;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markSucceeded(TaskId taskId, String workerId, long leaseVersion, Instant completedAt) {
        Objects.requireNonNull(completedAt, "completedAt");
        lock.lock();
        try {
            MutableTask task = requireLease(taskId, workerId, leaseVersion);
            task.status = TaskStatus.SUCCEEDED;
            task.lastFailure = null;
            task.clearLease();
            task.updatedAt = completedAt;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public TaskStatus markFailed(
            TaskId taskId,
            String workerId,
            long leaseVersion,
            Instant failedAt,
            RetryPolicy retryPolicy,
            Throwable failure) {
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        Objects.requireNonNull(failure, "failure");
        lock.lock();
        try {
            MutableTask task = requireLease(taskId, workerId, leaseVersion);
            task.lastFailure = sanitizeFailure(failure);
            task.clearLease();
            task.updatedAt = failedAt;
            if (task.cancellationRequested) {
                task.status = TaskStatus.CANCELLED;
                return task.status;
            }
            if (task.retrySafety == RetrySafety.RETRY_SAFE && task.attempts < retryPolicy.maximumAttempts()) {
                task.status = TaskStatus.PENDING;
                task.availableAt = safeAdd(failedAt, retryPolicy.delayAfterFailure(task.attempts));
            } else {
                task.status = TaskStatus.FAILED;
            }
            return task.status;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markTerminalFailure(
            TaskId taskId,
            String workerId,
            long leaseVersion,
            Instant failedAt,
            Throwable failure) {
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(failure, "failure");
        lock.lock();
        try {
            MutableTask task = requireLease(taskId, workerId, leaseVersion);
            task.status = task.cancellationRequested ? TaskStatus.CANCELLED : TaskStatus.FAILED;
            task.lastFailure = sanitizeFailure(failure);
            task.clearLease();
            task.updatedAt = failedAt;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markCancelled(TaskId taskId, String workerId, long leaseVersion, Instant cancelledAt) {
        Objects.requireNonNull(cancelledAt, "cancelledAt");
        lock.lock();
        try {
            MutableTask task = requireLease(taskId, workerId, leaseVersion);
            task.status = TaskStatus.CANCELLED;
            task.cancellationRequested = true;
            task.clearLease();
            task.updatedAt = cancelledAt;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CancellationOutcome requestCancellation(TaskId taskId, Instant requestedAt) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(requestedAt, "requestedAt");
        lock.lock();
        try {
            MutableTask task = tasks.get(taskId);
            if (task == null) {
                return CancellationOutcome.NOT_FOUND;
            }
            if (task.status.terminal()) {
                return CancellationOutcome.ALREADY_TERMINAL;
            }
            if (task.cancellationRequested) {
                return CancellationOutcome.ALREADY_REQUESTED;
            }
            task.cancellationRequested = true;
            task.updatedAt = requestedAt;
            if (task.status == TaskStatus.PENDING) {
                task.status = TaskStatus.CANCELLED;
            }
            return CancellationOutcome.REQUESTED;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<TaskRecord> find(TaskId taskId) {
        Objects.requireNonNull(taskId, "taskId");
        lock.lock();
        try {
            MutableTask task = tasks.get(taskId);
            return task == null ? Optional.empty() : Optional.of(task.snapshot());
        } finally {
            lock.unlock();
        }
    }

    private void recoverExpiredLeases(Instant now, RetryPolicy retryPolicy) {
        for (MutableTask task : tasks.values()) {
            if (task.status != TaskStatus.RUNNING || task.leaseUntil.isAfter(now)) {
                continue;
            }
            task.clearLease();
            task.updatedAt = now;
            if (task.cancellationRequested) {
                task.status = TaskStatus.CANCELLED;
                task.lastFailure = "execution lease expired after cancellation request";
            } else if (task.retrySafety == RetrySafety.RETRY_SAFE
                    && task.attempts < retryPolicy.maximumAttempts()) {
                task.status = TaskStatus.PENDING;
                task.availableAt = safeAdd(now, retryPolicy.delayAfterFailure(task.attempts));
                task.lastFailure = "execution lease expired; retry-safe task released for recovery";
            } else {
                task.status = TaskStatus.FAILED;
                task.lastFailure = task.retrySafety == RetrySafety.AT_MOST_ONCE
                        ? "execution lease expired; outcome unknown; automatic retry forbidden"
                        : "execution lease expired at maximum retry attempts";
            }
        }
    }

    private MutableTask requireLease(TaskId taskId, String workerId, long leaseVersion) {
        Objects.requireNonNull(taskId, "taskId");
        String worker = requireText(workerId, "workerId", 160);
        if (leaseVersion < 1) {
            throw new IllegalArgumentException("leaseVersion must be positive");
        }
        MutableTask task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("unknown task: " + taskId);
        }
        if (task.status != TaskStatus.RUNNING
                || !worker.equals(task.leaseOwner)
                || task.leaseVersion != leaseVersion) {
            throw new TaskLeaseLostException("task lease is no longer owned by " + worker);
        }
        return task;
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

    private static Instant safeAdd(Instant value, Duration duration) {
        try {
            return value.plus(duration);
        } catch (DateTimeException | ArithmeticException error) {
            throw new IllegalArgumentException("time calculation overflow", error);
        }
    }

    private static String sanitizeFailure(Throwable failure) {
        String type = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        String rendered = message == null || message.isBlank() ? type : type + ": " + message.strip();
        return rendered.length() <= MAX_FAILURE_LENGTH ? rendered : rendered.substring(0, MAX_FAILURE_LENGTH);
    }

    private record IdempotencyScope(TaskType type, String key) {}

    private static final class MutableTask {
        private final TaskId taskId;
        private final TaskType type;
        private final String idempotencyKey;
        private final Map<String, String> parameters;
        private final RetrySafety retrySafety;
        private final DomainIdentifier correlationId;
        private final Instant requestedNotBefore;
        private final Instant createdAt;
        private TaskStatus status;
        private int attempts;
        private Instant availableAt;
        private String leaseOwner;
        private long leaseVersion;
        private Instant leaseUntil;
        private TaskCheckpoint checkpoint;
        private boolean cancellationRequested;
        private String lastFailure;
        private Instant updatedAt;

        private MutableTask(
                TaskId taskId,
                TaskSubmission submission,
                RetrySafety retrySafety,
                DomainIdentifier correlationId,
                Instant submittedAt) {
            this.taskId = taskId;
            this.type = submission.type();
            this.idempotencyKey = submission.idempotencyKey();
            this.parameters = submission.parameters();
            this.retrySafety = retrySafety;
            this.correlationId = correlationId;
            this.requestedNotBefore = submission.notBefore();
            this.createdAt = submittedAt;
            this.status = TaskStatus.PENDING;
            this.availableAt = submission.notBefore();
            this.updatedAt = submittedAt;
        }

        static MutableTask create(
                TaskId taskId,
                TaskSubmission submission,
                RetrySafety retrySafety,
                DomainIdentifier correlationId,
                Instant submittedAt) {
            return new MutableTask(taskId, submission, retrySafety, correlationId, submittedAt);
        }

        boolean matches(TaskSubmission submission, RetrySafety safety) {
            // The idempotency map is keyed by type + idempotency key, therefore
            // those two values are already equal whenever this method is called.
            // Compare only the remaining semantics to avoid redundant,
            // structurally unreachable validation branches.
            return parameters.equals(submission.parameters())
                    && requestedNotBefore.equals(submission.notBefore())
                    && retrySafety == safety;
        }

        void clearLease() {
            leaseOwner = null;
            leaseUntil = null;
        }

        TaskRecord snapshot() {
            return new TaskRecord(
                    taskId,
                    type,
                    idempotencyKey,
                    correlationId,
                    parameters,
                    retrySafety,
                    status,
                    attempts,
                    availableAt,
                    leaseOwner,
                    leaseVersion,
                    leaseUntil,
                    checkpoint,
                    cancellationRequested,
                    lastFailure,
                    createdAt,
                    updatedAt);
        }
    }
}
