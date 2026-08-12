package io.infranexum.core.workers;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable task state returned by a {@link TaskStore}. */
public record TaskRecord(
        TaskId taskId,
        TaskType type,
        String idempotencyKey,
        DomainIdentifier correlationId,
        Map<String, String> parameters,
        RetrySafety retrySafety,
        TaskStatus status,
        int attempts,
        Instant availableAt,
        String leaseOwner,
        long leaseVersion,
        Instant leaseUntil,
        TaskCheckpoint checkpoint,
        boolean cancellationRequested,
        String lastFailure,
        Instant createdAt,
        Instant updatedAt) {
    public TaskRecord {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(type, "type");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(parameters, "parameters")));
        Objects.requireNonNull(retrySafety, "retrySafety");
        Objects.requireNonNull(status, "status");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        Objects.requireNonNull(availableAt, "availableAt");
        if (leaseVersion < 0) {
            throw new IllegalArgumentException("leaseVersion must not be negative");
        }
        if (status == TaskStatus.RUNNING) {
            leaseOwner = requireLeaseOwner(leaseOwner);
            Objects.requireNonNull(leaseUntil, "leaseUntil");
            if (leaseVersion < 1) {
                throw new IllegalArgumentException("running task must have a positive leaseVersion");
            }
        } else if (leaseOwner != null || leaseUntil != null) {
            throw new IllegalArgumentException("only a running task may hold a lease");
        }
        if (lastFailure != null && lastFailure.length() > 1_024) {
            throw new IllegalArgumentException("lastFailure exceeds 1024 characters");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }


    /** Backward-compatible constructor for task records created without correlation metadata. */
    public TaskRecord(
            TaskId taskId,
            TaskType type,
            String idempotencyKey,
            Map<String, String> parameters,
            RetrySafety retrySafety,
            TaskStatus status,
            int attempts,
            Instant availableAt,
            String leaseOwner,
            long leaseVersion,
            Instant leaseUntil,
            TaskCheckpoint checkpoint,
            boolean cancellationRequested,
            String lastFailure,
            Instant createdAt,
            Instant updatedAt) {
        this(taskId, type, idempotencyKey, null, parameters, retrySafety, status, attempts, availableAt,
                leaseOwner, leaseVersion, leaseUntil, checkpoint, cancellationRequested, lastFailure, createdAt, updatedAt);
    }

    public Optional<TaskCheckpoint> optionalCheckpoint() {
        return Optional.ofNullable(checkpoint);
    }

    public Optional<String> optionalFailure() {
        return Optional.ofNullable(lastFailure);
    }

    private static String requireLeaseOwner(String value) {
        String normalized = Objects.requireNonNull(value, "leaseOwner").strip();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw new IllegalArgumentException("leaseOwner must contain 1-160 characters");
        }
        return normalized;
    }
}
