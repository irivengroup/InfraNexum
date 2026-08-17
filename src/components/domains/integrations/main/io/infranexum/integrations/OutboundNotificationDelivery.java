package io.infranexum.integrations;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/** Durable outbound notification delivery with lease and replay metadata. */
public record OutboundNotificationDelivery(
        DomainIdentifier deliveryId,
        ConnectorKey endpointKey,
        String eventId,
        String eventType,
        byte[] payload,
        String payloadSha256,
        OutboundNotificationStatus status,
        int attempts,
        Instant createdAt,
        Instant availableAt,
        String leaseOwner,
        Instant leaseUntil,
        Instant deliveredAt,
        String lastFailure,
        int replayCount,
        Instant lastReplayedAt) {
    public OutboundNotificationDelivery {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(endpointKey, "endpointKey");
        eventId = require(eventId, "eventId", 200);
        eventType = require(eventType, "eventType", 128);
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
        if (payload.length == 0) throw new IllegalArgumentException("notification payload must not be empty");
        payloadSha256 = require(payloadSha256, "payloadSha256", 64);
        Objects.requireNonNull(status, "status");
        if (attempts < 0 || replayCount < 0) throw new IllegalArgumentException("notification counters must be non-negative");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(availableAt, "availableAt");
        if ((status == OutboundNotificationStatus.IN_FLIGHT) != (leaseOwner != null && leaseUntil != null)) {
            throw new IllegalArgumentException("notification lease fields are inconsistent with status");
        }
        if ((status == OutboundNotificationStatus.DELIVERED) != (deliveredAt != null)) {
            throw new IllegalArgumentException("notification deliveredAt is inconsistent with status");
        }
        if (replayCount == 0 && lastReplayedAt != null) {
            throw new IllegalArgumentException("notification replay timestamp requires replayCount > 0");
        }
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    private static String require(String value, String field, int maximum) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximum) throw new IllegalArgumentException("invalid " + field);
        return normalized;
    }
}
