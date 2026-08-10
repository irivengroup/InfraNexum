package io.infranexum.core.workers;

import io.infranexum.core.contracts.UuidV7Generator;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** Idempotent scheduling and cancellation facade for background tasks. */
public final class TaskScheduler {
    private final TaskStore store;
    private final TaskHandlerRegistry registry;
    private final UuidV7Generator identifiers;
    private final Clock clock;

    public TaskScheduler(
            TaskStore store,
            TaskHandlerRegistry registry,
            UuidV7Generator identifiers,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TaskSubmissionResult schedule(TaskSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        TaskHandler handler = registry.require(submission.type());
        return store.submit(
                new TaskId(identifiers.next()),
                submission,
                handler.retrySafety(),
                clock.instant());
    }

    public CancellationOutcome cancel(TaskId taskId) {
        return store.requestCancellation(Objects.requireNonNull(taskId, "taskId"), clock.instant());
    }

    public Optional<TaskRecord> find(TaskId taskId) {
        return store.find(Objects.requireNonNull(taskId, "taskId"));
    }
}
