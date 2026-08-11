package io.infranexum.core.events;

import java.time.Instant;
import java.util.Objects;

/** Immutable diagnostic snapshot of one outbox record. */
public record OutboxRecord(
        EventEnvelope event,
        OutboxStatus status,
        int attempts,
        Instant availableAt,
        String leaseOwner,
        Instant leaseUntil,
        Instant publishedAt,
        String lastFailure) {
    public OutboxRecord {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(availableAt, "availableAt");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be non-negative");
        }
        validateState(status, leaseOwner, leaseUntil, publishedAt);
        if (lastFailure != null && lastFailure.length() > 1024) {
            throw new IllegalArgumentException("lastFailure exceeds 1024 characters");
        }
    }

    private static void validateState(
            OutboxStatus status, String leaseOwner, Instant leaseUntil, Instant publishedAt) {
        if (status == OutboxStatus.IN_FLIGHT) {
            if (leaseOwner == null || leaseOwner.isBlank() || leaseUntil == null) {
                throw new IllegalArgumentException("IN_FLIGHT records require lease owner and expiry");
            }
        } else if (leaseOwner != null || leaseUntil != null) {
            throw new IllegalArgumentException("only IN_FLIGHT records may hold a lease");
        }
        if (status == OutboxStatus.PUBLISHED && publishedAt == null) {
            throw new IllegalArgumentException("PUBLISHED records require publishedAt");
        }
        if (status != OutboxStatus.PUBLISHED && publishedAt != null) {
            throw new IllegalArgumentException("only PUBLISHED records may define publishedAt");
        }
    }
}
