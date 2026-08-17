package io.infranexum.integrations;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/** Canonical durable-admission command for one endpoint delivery. */
public record OutboundNotificationAdmission(
        DomainIdentifier deliveryId,
        ConnectorKey endpointKey,
        String eventId,
        String eventType,
        byte[] payload,
        String payloadSha256,
        Instant createdAt) {
    public OutboundNotificationAdmission {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(endpointKey, "endpointKey");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
        Objects.requireNonNull(payloadSha256, "payloadSha256");
        Objects.requireNonNull(createdAt, "createdAt");
    }
    @Override public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
}
