package io.infranexum.itam.asset.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;

/** Immutable event used to reconstruct who held an asset and why at every lifecycle mutation. */
public record AssetCustodyEvent(
        DomainIdentifier eventId,
        DomainIdentifier assetId,
        long sequence,
        AssetCustodyEventType eventType,
        AssetLifecycleStatus fromStatus,
        AssetLifecycleStatus toStatus,
        AssetCustodian custodian,
        Instant occurredAt,
        DomainIdentifier actorId,
        DomainIdentifier correlationId,
        String reason,
        String evidenceReference) {
    public AssetCustodyEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(assetId, "assetId");
        if (sequence < 1) throw new IllegalArgumentException("custody sequence must be positive");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(custodian, "custodian");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(correlationId, "correlationId");
        reason = text(reason, "reason", 2, 1024, true);
        evidenceReference = text(evidenceReference, "evidenceReference", 1, 240, false);
        if (eventType == AssetCustodyEventType.DISPOSED && evidenceReference == null) {
            throw new IllegalArgumentException("disposal requires an evidence reference");
        }
    }

    private static String text(String value, String field, int min, int max, boolean required) {
        if (value == null) {
            if (required) throw new NullPointerException(field);
            return null;
        }
        String normalized = value.strip();
        if ((!required && normalized.isEmpty())) return null;
        if (normalized.length() < min || normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
    }
}
