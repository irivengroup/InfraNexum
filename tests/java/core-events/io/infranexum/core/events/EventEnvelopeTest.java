package io.infranexum.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {
    private static final DomainIdentifier EVENT_ID = id("018bcfe5-6800-7000-8000-000000000001");
    private static final DomainIdentifier CORRELATION_ID = id("018bcfe5-6800-7000-8000-000000000003");

    @Test
    void normalizesEnvelopeValues() {
        EventEnvelope envelope = envelope(" {\"value\":1} ");
        assertEquals("{\"value\":1}", envelope.payload());
        assertEquals("core/server-1", envelope.source().value());
        assertEquals("core.asset.created.v1", envelope.eventType().value());
        assertEquals("1.0.0", envelope.schemaVersion().toString());
    }

    @Test
    void rejectsInvalidTypesSourcesAndPayloads() {
        assertThrows(IllegalArgumentException.class, () -> new EventType("Core.Asset.Created.v1"));
        assertThrows(IllegalArgumentException.class, () -> new EventType("core.asset.v0"));
        assertThrows(IllegalArgumentException.class, () -> new EventSource(" "));
        assertThrows(IllegalArgumentException.class, () -> new EventSource("core\nserver"));
        assertThrows(IllegalArgumentException.class, () -> new EventSource("x".repeat(256)));
        assertThrows(IllegalArgumentException.class, () -> envelope(" "));
        assertThrows(IllegalArgumentException.class, () -> envelope("true"));
        assertThrows(IllegalArgumentException.class, () -> envelope("{"));
        assertThrows(IllegalArgumentException.class, () -> envelope("["));
        assertThrows(IllegalArgumentException.class, () -> envelope(
                "{\"value\":\"" + "x".repeat(EventEnvelope.MAX_PAYLOAD_BYTES) + "\"}"));
    }

    @Test
    void validatesOutboxAndInboxSnapshots() {
        EventEnvelope envelope = envelope("[]");
        Instant now = Instant.parse("2026-08-03T12:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> new OutboxRecord(
                envelope, OutboxStatus.IN_FLIGHT, 1, now, null, now, null, null));
        assertThrows(IllegalArgumentException.class, () -> new OutboxRecord(
                envelope, OutboxStatus.PENDING, 0, now, "worker", now, null, null));
        assertThrows(IllegalArgumentException.class, () -> new OutboxRecord(
                envelope, OutboxStatus.PUBLISHED, 1, now, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new OutboxRecord(
                envelope, OutboxStatus.PENDING, -1, now, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new InboxKey("X", EVENT_ID));
        InboxKey key = new InboxKey("core.projection", EVENT_ID);
        assertThrows(IllegalArgumentException.class, () -> new InboxReceipt(
                key, envelope.eventType(), "BAD", now, now));
        assertThrows(IllegalArgumentException.class, () -> new InboxReceipt(
                key, envelope.eventType(), "0".repeat(64), now, now.minusSeconds(1)));
    }

    private static EventEnvelope envelope(String payload) {
        return new EventEnvelope(
                EVENT_ID,
                new EventType("core.asset.created.v1"),
                ContractVersion.parse("1.0.0"),
                Instant.parse("2026-08-03T12:00:00Z"),
                new EventSource("core/server-1"),
                CORRELATION_ID,
                null,
                payload);
    }

    private static DomainIdentifier id(String value) {
        return new DomainIdentifier(UUID.fromString(value));
    }
}
