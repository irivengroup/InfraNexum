package io.infranexum.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Exhaustive validation tests for eventing value objects and observable invariants. */
class EventValueObjectsTest {
    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");
    private static final DomainIdentifier EVENT_ID = id("018f22b2-7c00-7000-8000-000000000001");
    private static final DomainIdentifier OTHER_EVENT_ID = id("018f22b2-7c01-7000-8000-000000000002");
    private static final DomainIdentifier CORRELATION_ID = id("018f22b2-7c02-7000-8000-000000000003");

    @Test
    void dispatchReportRejectsEachNegativeCounterAndNonReconciledTotals() {
        assertEquals(new DispatchReport(0, 0, 0, 0), new DispatchReport(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new DispatchReport(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new DispatchReport(0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new DispatchReport(0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DispatchReport(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new DispatchReport(2, 1, 0, 0));
    }

    @Test
    void eventTypesSourcesAndInboxKeysNormalizeAndOrderDeterministically() {
        EventSource source = new EventSource("  core/server-1  ");
        assertEquals("core/server-1", source.value());
        assertEquals("core/server-1", source.toString());

        EventType created = new EventType("  core.asset.created.v1  ");
        EventType updated = new EventType("core.asset.updated.v1");
        assertEquals("core.asset.created.v1", created.toString());
        assertTrue(created.compareTo(updated) < 0);
        assertEquals(0, created.compareTo(new EventType("core.asset.created.v1")));
        assertThrows(NullPointerException.class, () -> created.compareTo(null));

        InboxKey first = new InboxKey(" core.consumer ", EVENT_ID);
        InboxKey otherConsumer = new InboxKey("core.projection", EVENT_ID);
        InboxKey otherEvent = new InboxKey("core.consumer", OTHER_EVENT_ID);
        assertEquals("core.consumer", first.consumerName());
        assertTrue(first.compareTo(otherConsumer) < 0);
        assertTrue(first.compareTo(otherEvent) < 0);
        assertEquals(0, first.compareTo(new InboxKey("core.consumer", EVENT_ID)));
        assertThrows(NullPointerException.class, () -> first.compareTo(null));
    }

    @Test
    void eventTypesSourcesAndInboxKeysRejectBoundaryViolations() {
        assertThrows(NullPointerException.class, () -> new EventSource(null));
        assertThrows(IllegalArgumentException.class, () -> new EventSource(" "));
        assertThrows(IllegalArgumentException.class, () -> new EventSource("x".repeat(256)));
        assertThrows(IllegalArgumentException.class, () -> new EventSource("core\u0000server"));

        assertThrows(NullPointerException.class, () -> new EventType(null));
        assertThrows(IllegalArgumentException.class, () -> new EventType("core.asset.v0"));
        assertThrows(IllegalArgumentException.class, () -> new EventType("core.asset.created.v01"));

        assertThrows(NullPointerException.class, () -> new InboxKey(null, EVENT_ID));
        assertThrows(IllegalArgumentException.class, () -> new InboxKey("ab", EVENT_ID));
        assertThrows(IllegalArgumentException.class, () -> new InboxKey("Core.consumer", EVENT_ID));
        assertThrows(NullPointerException.class, () -> new InboxKey("core.consumer", null));
    }

    @Test
    void inboxReservationAndReceiptValidateDigestAndTemporalOrdering() {
        EventType eventType = new EventType("core.asset.created.v1");
        InboxKey key = new InboxKey("core.consumer", EVENT_ID);
        String digest = "0".repeat(64);
        InboxReservation reservation = new InboxReservation(key, eventType, digest, NOW);
        assertEquals(digest, reservation.payloadSha256());

        InboxReceipt receipt = new InboxReceipt(key, eventType, digest, NOW, NOW.plusSeconds(1));
        assertEquals(NOW.plusSeconds(1), receipt.completedAt());

        assertThrows(IllegalArgumentException.class, () -> new InboxReservation(key, eventType, "BAD", NOW));
        assertThrows(IllegalArgumentException.class, () -> new InboxReceipt(key, eventType, "BAD", NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new InboxReceipt(key, eventType, digest, NOW, NOW.minusNanos(1)));
    }

    @Test
    void outboxRecordAcceptsEveryDurableStateAndRejectsIllegalCombinations() {
        EventEnvelope event = envelope();
        Instant leaseUntil = NOW.plusSeconds(30);
        Instant publishedAt = NOW.plusSeconds(1);

        OutboxRecord pending = new OutboxRecord(event, OutboxStatus.PENDING, 0, NOW, null, null, null, null);
        OutboxRecord inFlight = new OutboxRecord(event, OutboxStatus.IN_FLIGHT, 1, NOW, "worker", leaseUntil, null, null);
        OutboxRecord published = new OutboxRecord(event, OutboxStatus.PUBLISHED, 1, NOW, null, null, publishedAt, null);
        OutboxRecord deadLetter = new OutboxRecord(event, OutboxStatus.DEAD_LETTER, 3, NOW, null, null, null, "Failure");
        assertEquals(OutboxStatus.PENDING, pending.status());
        assertEquals("worker", inFlight.leaseOwner());
        assertEquals(publishedAt, published.publishedAt());
        assertEquals("Failure", deadLetter.lastFailure());

        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRecord(event, OutboxStatus.PENDING, -1, NOW, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRecord(event, OutboxStatus.IN_FLIGHT, 1, NOW, null, leaseUntil, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRecord(event, OutboxStatus.IN_FLIGHT, 1, NOW, " ", leaseUntil, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRecord(event, OutboxStatus.IN_FLIGHT, 1, NOW, "worker", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRecord(event, OutboxStatus.PENDING, 1, NOW, "worker", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRecord(event, OutboxStatus.PENDING, 1, NOW, null, leaseUntil, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRecord(event, OutboxStatus.PUBLISHED, 1, NOW, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRecord(event, OutboxStatus.PENDING, 1, NOW, null, null, publishedAt, null));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRecord(event, OutboxStatus.DEAD_LETTER, 1, NOW, null, null, null, "x".repeat(1025)));
    }

    @Test
    void transactionOutcomeDefensivelyCopiesFailuresAndReportsSignalState() {
        java.util.ArrayList<String> failures = new java.util.ArrayList<>(List.of("one"));
        TransactionOutcome<String> failed = new TransactionOutcome<>("value", failures);
        failures.add("two");
        assertEquals(List.of("one"), failed.postCommitFailures());
        assertFalse(failed.postCommitSignalsSucceeded());

        TransactionOutcome<String> successful = new TransactionOutcome<>("value", List.of());
        assertTrue(successful.postCommitSignalsSucceeded());
        assertThrows(NullPointerException.class, () -> new TransactionOutcome<>("value", null));
        assertThrows(UnsupportedOperationException.class, () -> successful.postCommitFailures().add("forbidden"));
    }

    private static EventEnvelope envelope() {
        return new EventEnvelope(
                EVENT_ID,
                new EventType("core.asset.created.v1"),
                ContractVersion.parse("1.0.0"),
                NOW,
                new EventSource("core/server-1"),
                CORRELATION_ID,
                null,
                "{}");
    }

    private static DomainIdentifier id(String value) {
        return new DomainIdentifier(UUID.fromString(value));
    }
}
