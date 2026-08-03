package io.infranexum.core.events;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable public event envelope persisted atomically in the transactional outbox.
 *
 * <p>The field names follow the InfraNexum standard envelope. The payload is
 * bounded and remains owned by the producing bounded context and its schema.
 */
public record EventEnvelope(
        DomainIdentifier eventId,
        EventType eventType,
        ContractVersion schemaVersion,
        Instant occurredAt,
        EventSource source,
        DomainIdentifier correlationId,
        DomainIdentifier causationId,
        String payload) {
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(correlationId, "correlationId");
        payload = validatePayload(payload);
    }

    private static String validatePayload(String value) {
        Objects.requireNonNull(value, "payload");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("payload must not be blank");
        }
        boolean object = normalized.startsWith("{") && normalized.endsWith("}");
        boolean array = normalized.startsWith("[") && normalized.endsWith("]");
        if (!object && !array) {
            throw new IllegalArgumentException("payload must be a JSON object or array");
        }
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }
        return normalized;
    }
}
