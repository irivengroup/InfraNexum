package io.infranexum.core.workers;

import java.util.Objects;

/** Result of idempotent task submission. */
public record TaskSubmissionResult(TaskId taskId, boolean created) {
    public TaskSubmissionResult {
        Objects.requireNonNull(taskId, "taskId");
    }
}
