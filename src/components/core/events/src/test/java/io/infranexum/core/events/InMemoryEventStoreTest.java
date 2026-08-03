package io.infranexum.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InMemoryEventStoreTest {
    @Test
    void commitsBeforeRunningPostCommitSignalsAndCapturesSignalFailures() {
        InMemoryEventStore store = new InMemoryEventStore();
        EventEnvelope event = event(1);
        AtomicBoolean visibleAfterCommit = new AtomicBoolean();
        TransactionOutcome<String> outcome = store.execute(transaction -> {
            transaction.append(event);
            transaction.afterCommit(() -> visibleAfterCommit.set(store.outboxSnapshot().size() == 1));
            transaction.afterCommit(() -> { throw new IllegalStateException("signal unavailable"); });
            return "committed";
        });
        assertEquals("committed", outcome.value());
        assertTrue(visibleAfterCommit.get());
        assertFalse(outcome.postCommitSignalsSucceeded());
        assertEquals("IllegalStateException", outcome.postCommitFailures().getFirst());
    }

    @Test
    void rollsBackEveryStagedChangeAndNeverRunsPostCommitOnFailure() {
        InMemoryEventStore store = new InMemoryEventStore();
        AtomicBoolean signal = new AtomicBoolean();
        assertThrows(TransactionExecutionException.class, () -> store.execute(transaction -> {
            transaction.append(event(1));
            transaction.afterCommit(() -> signal.set(true));
            throw new IllegalStateException("domain write failed");
        }));
        assertTrue(store.outboxSnapshot().isEmpty());
        assertFalse(signal.get());
    }

    @Test
    void requiresAcceptedInboxReservationsToComplete() {
        InMemoryEventStore store = new InMemoryEventStore();
        InboxKey key = new InboxKey("core.consumer", event(1).eventId());
        assertThrows(TransactionExecutionException.class, () -> store.execute(transaction -> {
            assertEquals(InboxDecision.ACCEPTED, transaction.beginInbox(key));
            return null;
        }));
        assertTrue(store.inboxSnapshot().isEmpty());
    }

    @Test
    void claimsInOrderRecoversExpiredLeaseAndEnforcesOwnership() {
        InMemoryEventStore store = new InMemoryEventStore();
        store.execute(transaction -> {
            transaction.append(event(2));
            transaction.append(event(1));
            return null;
        });
        Instant now = Instant.parse("2026-08-03T12:00:10Z");
        var first = store.claimBatch("worker-a", 1, now, Duration.ofSeconds(5));
        assertEquals(event(1).eventId(), first.getFirst().event().eventId());
        assertThrows(IllegalStateException.class, () -> store.markPublished(event(1).eventId(), "worker-b", now));
        assertThrows(IllegalArgumentException.class, () -> store.markPublished(event(99).eventId(), "worker-a", now));

        var reclaimed = store.claimBatch("worker-b", 2, now.plusSeconds(5), Duration.ofSeconds(5));
        assertEquals(2, reclaimed.size());
        assertEquals(2, reclaimed.stream().filter(record -> record.status() == OutboxStatus.IN_FLIGHT).count());
    }

    @Test
    void retriesThenDeadLettersAccordingToPolicy() {
        InMemoryEventStore store = new InMemoryEventStore();
        EventEnvelope event = event(1);
        store.execute(transaction -> { transaction.append(event); return null; });
        RetryPolicy retry = new ExponentialBackoffPolicy(
                2, Duration.ofSeconds(2), Duration.ofSeconds(10), 0.0, () -> 0.0);
        Instant now = Instant.parse("2026-08-03T12:00:10Z");
        store.claimBatch("worker", 1, now, Duration.ofSeconds(5));
        assertEquals(OutboxStatus.PENDING,
                store.markFailed(event.eventId(), "worker", now, retry, new IllegalStateException("offline")));
        OutboxRecord pending = store.outboxSnapshot().getFirst();
        assertEquals(now.plusSeconds(2), pending.availableAt());
        assertEquals("IllegalStateException", pending.lastFailure());
        assertTrue(store.claimBatch("worker", 1, now.plusSeconds(1), Duration.ofSeconds(5)).isEmpty());
        store.claimBatch("worker", 1, now.plusSeconds(2), Duration.ofSeconds(5));
        assertEquals(OutboxStatus.DEAD_LETTER,
                store.markFailed(event.eventId(), "worker", now.plusSeconds(2), retry, new IllegalStateException()));
    }

    @Test
    void validatesBatchLeaseAndDuplicateEventInputs() {
        InMemoryEventStore store = new InMemoryEventStore();
        assertThrows(IllegalArgumentException.class,
                () -> store.claimBatch("worker", 0, Instant.EPOCH, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> store.claimBatch(" ", 1, Instant.EPOCH, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> store.claimBatch("worker", 1, Instant.EPOCH, Duration.ZERO));
        assertThrows(TransactionExecutionException.class, () -> store.execute(transaction -> {
            transaction.append(event(1));
            transaction.append(event(1));
            return null;
        }));
    }

    static EventEnvelope event(int sequence) {
        String suffix = "%012d".formatted(sequence);
        DomainIdentifier eventId = id("018bcfe5-6800-7000-8000-" + suffix);
        DomainIdentifier correlationId = id("018bcfe5-6800-7002-8000-" + suffix);
        return new EventEnvelope(
                eventId,
                new EventType("core.asset.created.v1"),
                ContractVersion.parse("1.0.0"),
                Instant.parse("2026-08-03T12:00:00Z").plusMillis(sequence),
                new EventSource("core/server-1"),
                correlationId,
                null,
                "{\"sequence\":" + sequence + "}");
    }

    private static DomainIdentifier id(String value) {
        return new DomainIdentifier(UUID.fromString(value));
    }
}
