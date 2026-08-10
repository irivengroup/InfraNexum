package io.infranexum.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Non-regression tests for transaction, lease, interruption and inbox edge cases. */
class InMemoryEventStoreEdgeCasesTest {
    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void validatesNullAndUpperBoundaryInputs() {
        InMemoryEventStore store = new InMemoryEventStore();
        assertThrows(NullPointerException.class, () -> store.execute(null));
        assertThrows(NullPointerException.class, () -> store.claimBatch(null, 1, NOW, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> store.claimBatch("x".repeat(161), 1, NOW, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> store.claimBatch("worker", 1001, NOW, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> store.claimBatch("worker", 1, null, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> store.claimBatch("worker", 1, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> store.claimBatch("worker", 1, NOW, Duration.ofSeconds(-1)));

        var event = InMemoryEventStoreTest.event(1);
        assertThrows(NullPointerException.class, () -> store.markPublished(null, "worker", NOW));
        assertThrows(IllegalArgumentException.class, () -> store.markPublished(event.eventId(), "x".repeat(161), NOW));
        assertThrows(NullPointerException.class, () -> store.markPublished(event.eventId(), "worker", null));

        RetryPolicy retry = retryPolicy(2);
        assertThrows(NullPointerException.class, () -> store.markFailed(null, "worker", NOW, retry, new Exception()));
        assertThrows(NullPointerException.class, () -> store.markFailed(event.eventId(), "worker", null, retry, new Exception()));
        assertThrows(NullPointerException.class, () -> store.markFailed(event.eventId(), "worker", NOW, null, new Exception()));
        assertThrows(NullPointerException.class, () -> store.markFailed(event.eventId(), "worker", NOW, retry, null));
    }

    @Test
    void preservesInterruptedStatusForTransactionalAndPostCommitInterruptions() {
        InMemoryEventStore store = new InMemoryEventStore();
        assertThrows(TransactionExecutionException.class, () -> store.execute(transaction -> {
            throw new InterruptedException("cancel transaction");
        }));
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();

        TransactionOutcome<Void> outcome = store.execute(transaction -> {
            transaction.afterCommit(() -> {
                throw new InterruptedException("cancel signal");
            });
            return null;
        });
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(ListOf("InterruptedException"), outcome.postCommitFailures());
    }

    @Test
    void sanitizesAnonymousPostCommitFailureAndReportsSuccessfulSignals() {
        InMemoryEventStore store = new InMemoryEventStore();
        AtomicBoolean called = new AtomicBoolean();
        TransactionOutcome<Void> outcome = store.execute(transaction -> {
            transaction.afterCommit(() -> called.set(true));
            transaction.afterCommit(() -> {
                throw new Exception("anonymous") {};
            });
            return null;
        });
        assertTrue(called.get());
        assertEquals(ListOf("Failure"), outcome.postCommitFailures());
    }

    @Test
    void validatesTransactionLocalInboxStateMachine() {
        InMemoryEventStore store = new InMemoryEventStore();
        EventEnvelope event = InMemoryEventStoreTest.event(1);
        InboxKey key = new InboxKey("core.consumer", event.eventId());
        InboxReservation reservation = reservation(key, event);

        assertThrows(TransactionExecutionException.class, () -> store.execute(transaction -> {
            transaction.beginInbox(null);
            return null;
        }));
        assertThrows(TransactionExecutionException.class, () -> store.execute(transaction -> {
            transaction.completeInbox(key, NOW);
            return null;
        }));
        assertThrows(TransactionExecutionException.class, () -> store.execute(transaction -> {
            transaction.beginInbox(reservation);
            transaction.completeInbox(null, NOW);
            return null;
        }));
        assertThrows(TransactionExecutionException.class, () -> store.execute(transaction -> {
            transaction.beginInbox(reservation);
            transaction.completeInbox(key, null);
            return null;
        }));
        assertThrows(TransactionExecutionException.class, () -> store.execute(transaction -> {
            assertEquals(InboxDecision.ACCEPTED, transaction.beginInbox(reservation));
            transaction.beginInbox(reservation);
            return null;
        }));
        assertThrows(TransactionExecutionException.class, () -> store.execute(transaction -> {
            assertEquals(InboxDecision.ACCEPTED, transaction.beginInbox(reservation));
            transaction.completeInbox(key, event.occurredAt());
            transaction.completeInbox(key, event.occurredAt());
            return null;
        }));
        assertThrows(TransactionExecutionException.class, () -> store.execute(transaction -> {
            assertEquals(InboxDecision.ACCEPTED, transaction.beginInbox(reservation));
            transaction.completeInbox(key, event.occurredAt().minusNanos(1));
            return null;
        }));
        assertThrows(TransactionExecutionException.class, () -> store.execute(transaction -> {
            transaction.afterCommit(null);
            return null;
        }));

        store.execute(transaction -> {
            assertEquals(InboxDecision.ACCEPTED, transaction.beginInbox(reservation));
            transaction.completeInbox(key, event.occurredAt());
            return null;
        });
        assertEquals(1, store.inboxSnapshot().size());
        assertEquals(InboxDecision.DUPLICATE, store.execute(transaction -> transaction.beginInbox(reservation)).value());
    }

    @Test
    void enforcesLeaseOwnershipOnFailureAndPublication() {
        InMemoryEventStore store = new InMemoryEventStore();
        EventEnvelope event = InMemoryEventStoreTest.event(1);
        store.execute(transaction -> {
            transaction.append(event);
            return null;
        });
        store.claimBatch("owner", 1, NOW, Duration.ofSeconds(10));

        assertThrows(IllegalStateException.class,
                () -> store.markFailed(event.eventId(), "other", NOW, retryPolicy(2), new IllegalStateException()));
        store.markPublished(event.eventId(), "owner", NOW);
        assertThrows(IllegalStateException.class,
                () -> store.markPublished(event.eventId(), "owner", NOW.plusSeconds(1)));
    }

    @Test
    void rejectsTimeOverflowWhenCreatingLeaseOrRetryDelay() {
        InMemoryEventStore empty = new InMemoryEventStore();
        assertThrows(IllegalArgumentException.class,
                () -> empty.claimBatch("worker", 1, Instant.MAX, Duration.ofNanos(1)));

        InMemoryEventStore store = new InMemoryEventStore();
        EventEnvelope event = InMemoryEventStoreTest.event(1);
        store.execute(transaction -> {
            transaction.append(event);
            return null;
        });
        store.claimBatch("worker", 1, NOW, Duration.ofSeconds(10));
        RetryPolicy overflow = new RetryPolicy() {
            @Override
            public int maximumAttempts() {
                return 2;
            }

            @Override
            public Duration delayAfterFailure(int attempts) {
                return Duration.ofSeconds(2);
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> store.markFailed(event.eventId(), "worker", Instant.MAX, overflow, new IllegalStateException()));
    }

    @Test
    void doesNotRecoverActiveLeaseBeforeExpiry() {
        InMemoryEventStore store = new InMemoryEventStore();
        EventEnvelope event = InMemoryEventStoreTest.event(1);
        store.execute(transaction -> {
            transaction.append(event);
            return null;
        });
        assertEquals(1, store.claimBatch("worker-a", 1, NOW, Duration.ofSeconds(10)).size());
        assertTrue(store.claimBatch("worker-b", 1, NOW.plusSeconds(9), Duration.ofSeconds(10)).isEmpty());
        assertEquals(1, store.claimBatch("worker-b", 1, NOW.plusSeconds(10), Duration.ofSeconds(10)).size());
    }

    private static RetryPolicy retryPolicy(int maximumAttempts) {
        return new ExponentialBackoffPolicy(
                maximumAttempts, Duration.ofSeconds(1), Duration.ofMinutes(1), 0.0, () -> 0.0);
    }

    private static InboxReservation reservation(InboxKey key, EventEnvelope event) {
        return new InboxReservation(key, event.eventType(), "0".repeat(64), event.occurredAt());
    }

    private static java.util.List<String> ListOf(String value) {
        return java.util.List.of(value);
    }
}
