package io.infranexum.core.workers;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.RetryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable task state port. Implementations must make claims and state transitions atomic. */
public interface TaskStore {
    TaskSubmissionResult submit(
            TaskId proposedId,
            TaskSubmission submission,
            RetrySafety retrySafety,
            Instant submittedAt);

    /**
     * Submits a task with durable correlation metadata. Legacy stores may ignore correlation until
     * they implement the extended contract; InfraNexum stores override this method.
     */
    default TaskSubmissionResult submit(
            TaskId proposedId,
            TaskSubmission submission,
            RetrySafety retrySafety,
            DomainIdentifier correlationId,
            Instant submittedAt) {
        return submit(proposedId, submission, retrySafety, submittedAt);
    }

    List<TaskRecord> claimBatch(
            String workerId,
            int limit,
            Instant now,
            Duration leaseDuration,
            RetryPolicy retryPolicy);

    void renewLease(
            TaskId taskId,
            String workerId,
            long leaseVersion,
            Instant now,
            Duration leaseDuration);

    TaskCheckpoint saveCheckpoint(
            TaskId taskId,
            String workerId,
            long leaseVersion,
            String token,
            Instant now,
            Duration leaseDuration);

    void markSucceeded(TaskId taskId, String workerId, long leaseVersion, Instant completedAt);

    TaskStatus markFailed(
            TaskId taskId,
            String workerId,
            long leaseVersion,
            Instant failedAt,
            RetryPolicy retryPolicy,
            Throwable failure);

    void markTerminalFailure(
            TaskId taskId,
            String workerId,
            long leaseVersion,
            Instant failedAt,
            Throwable failure);

    void markCancelled(TaskId taskId, String workerId, long leaseVersion, Instant cancelledAt);

    CancellationOutcome requestCancellation(TaskId taskId, Instant requestedAt);

    Optional<TaskRecord> find(TaskId taskId);
}
