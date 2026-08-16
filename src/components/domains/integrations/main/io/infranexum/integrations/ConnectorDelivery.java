package io.infranexum.integrations;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable durable connector inbox/DLQ record. */
public record ConnectorDelivery(
        DomainIdentifier deliveryId,
        ConnectorKey connectorKey,
        String externalDeliveryId,
        String payload,
        String payloadSha256,
        ConnectorDeliveryStatus status,
        int attempts,
        Instant receivedAt,
        Instant availableAt,
        String leaseOwner,
        Instant leaseUntil,
        Instant processedAt,
        String lastFailure,
        int replayCount,
        Instant lastReplayedAt) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DELIVERY_ID = Pattern.compile("[A-Za-z0-9._:-]{1,200}");

    public ConnectorDelivery {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(connectorKey, "connectorKey");
        externalDeliveryId = text(externalDeliveryId, "externalDeliveryId", 200);
        if (!DELIVERY_ID.matcher(externalDeliveryId).matches()) throw new IllegalArgumentException("invalid externalDeliveryId");
        payload = payload(payload);
        if (!SHA256.matcher(Objects.requireNonNull(payloadSha256, "payloadSha256")).matches()) throw new IllegalArgumentException("invalid payloadSha256");
        Objects.requireNonNull(status, "status");
        if (attempts < 0 || replayCount < 0) throw new IllegalArgumentException("delivery counters must be non-negative");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(availableAt, "availableAt");
        boolean leased = status == ConnectorDeliveryStatus.IN_FLIGHT;
        if (leased != (leaseOwner != null && leaseUntil != null)) throw new IllegalArgumentException("lease fields must match IN_FLIGHT status");
        if (status == ConnectorDeliveryStatus.PROCESSED && processedAt == null) throw new IllegalArgumentException("processed delivery requires processedAt");
        if (status != ConnectorDeliveryStatus.PROCESSED && processedAt != null) throw new IllegalArgumentException("only processed delivery may define processedAt");
        if (lastFailure != null && lastFailure.length() > 1024) throw new IllegalArgumentException("lastFailure exceeds 1024 characters");
        if (lastReplayedAt != null && replayCount == 0) throw new IllegalArgumentException("lastReplayedAt requires replayCount > 0");
    }

    private static String payload(String value) {
        Objects.requireNonNull(value, "payload");
        if (value.isBlank() || value.length() > 1_048_576) throw new IllegalArgumentException("invalid payload");
        return value;
    }

    private static String text(String value, String field, int max) {
        Objects.requireNonNull(value, field);
        if (value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid " + field);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid " + field);
        return normalized;
    }
}
