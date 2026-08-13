package io.infranexum.rsot.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;

/** Canonical lifecycle metadata required by the composable RSOT data foundation. */
public record CanonicalLifecycle(
        CanonicalObjectStatus status,
        String statusReason,
        Instant effectiveFrom,
        Instant effectiveUntil,
        Instant archivedAt,
        DomainIdentifier archivedBy) {

    public CanonicalLifecycle {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        statusReason = optionalText(statusReason, 500);
        if (effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveUntil must be after effectiveFrom");
        }
        if ((archivedAt == null) != (archivedBy == null)) {
            throw new IllegalArgumentException("archivedAt and archivedBy must be defined together");
        }
        if (status == CanonicalObjectStatus.ARCHIVED && archivedAt == null) {
            throw new IllegalArgumentException("archived lifecycle requires archive metadata");
        }
        if (archivedAt != null && archivedAt.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("archivedAt precedes effectiveFrom");
        }
    }

    private static String optionalText(String value, int max) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid statusReason");
        }
        return normalized;
    }
}
