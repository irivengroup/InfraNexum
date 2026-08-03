package io.infranexum.core.events;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free end-to-end proof for outbox, post-commit publication and inbox deduplication. */
public final class EventingSmoke {
    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC);

    private EventingSmoke() {}

    public static void main(String[] args) throws Exception {
        provesPostCommitVisibilityAndRollback();
        provesBoundedDispatchRetryDeadLetterAndLeaseRecovery();
        provesInboxDeduplicationAndTransactionalRetry();
        provesConcurrentTransactionsAndClaims();
        provesInputGuards();
        System.out.println("java-eventing-smoke: PASS");
    }

    private static void provesPostCommitVisibilityAndRollback() {
        InMemoryEventStore store = new InMemoryEventStore();
        AtomicBoolean visible = new AtomicBoolean();
        EventEnvelope event = event(1);
        TransactionOutcome<String> outcome = store.execute(transaction -> {
            transaction.append(event);
            require(store.outboxSnapshot().isEmpty(), "outbox became visible before commit");
            transaction.afterCommit(() -> visible.set(store.outboxSnapshot().size() == 1));
            transaction.afterCommit(() -> { throw new IllegalStateException("notification offline"); });
            return "committed";
        });
        require("committed".equals(outcome.value()), "transaction result lost");
        require(visible.get(), "post-commit hook ran before committed state was visible");
        require(outcome.postCommitFailures().size() == 1, "post-commit failure not captured");
        require(store.outboxSnapshot().getFirst().status() == OutboxStatus.PENDING, "outbox state is not pending");

        AtomicBoolean rolledBackSignal = new AtomicBoolean();
        boolean failed = false;
        try {
            store.execute(transaction -> {
                transaction.append(event(2));
                transaction.afterCommit(() -> rolledBackSignal.set(true));
                throw new IllegalArgumentException("business failure");
            });
        } catch (TransactionExecutionException expected) {
            failed = expected.getCause() instanceof IllegalArgumentException;
        }
        require(failed, "transaction failure did not preserve cause");
        require(store.outboxSnapshot().size() == 1, "rolled-back outbox record persisted");
        require(!rolledBackSignal.get(), "post-commit hook ran after rollback");
    }

    private static void provesBoundedDispatchRetryDeadLetterAndLeaseRecovery() {
        InMemoryEventStore store = new InMemoryEventStore();
        store.execute(transaction -> {
            transaction.append(event(2));
            transaction.append(event(1));
            transaction.append(event(3));
            return null;
        });
        List<OutboxRecord> firstClaim = store.claimBatch("worker-a", 1, NOW.plusSeconds(10), Duration.ofSeconds(5));
        require(firstClaim.size() == 1, "bounded claim returned wrong size");
        require(firstClaim.getFirst().event().eventId().equals(event(1).eventId()), "claim ordering is not deterministic");
        List<OutboxRecord> recovered = store.claimBatch("worker-b", 3, NOW.plusSeconds(15), Duration.ofSeconds(5));
        require(recovered.size() == 3, "expired lease was not recovered");
        for (OutboxRecord record : recovered) {
            store.markFailed(
                    record.event().eventId(),
                    "worker-b",
                    NOW.plusSeconds(15),
                    new ExponentialBackoffPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(4), 0.0, () -> 0.0),
                    new IllegalStateException("broker offline"));
        }
        require(store.outboxSnapshot().stream().allMatch(record -> record.status() == OutboxStatus.PENDING),
                "failed records were not returned to pending");

        AtomicInteger calls = new AtomicInteger();
        List<EventEnvelope> published = new ArrayList<>();
        EventTransport transport = event -> {
            if (calls.getAndIncrement() == 0) throw new IllegalStateException("transient outage");
            published.add(event);
        };
        OutboxDispatcher dispatcher = new OutboxDispatcher(
                store,
                transport,
                new ExponentialBackoffPolicy(2, Duration.ofSeconds(1), Duration.ofSeconds(2), 0.0, () -> 0.0),
                Clock.fixed(NOW.plusSeconds(20), ZoneOffset.UTC),
                "worker-c",
                3,
                Duration.ofSeconds(10));
        DispatchReport report = dispatcher.dispatchOnce();
        require(report.claimed() == 3, "dispatcher did not claim expected batch");
        require(report.published() == 2 && report.retried() == 0 && report.deadLettered() == 1,
                "dispatcher counters are incorrect");
        require(published.size() == 2, "transport success count is incorrect");
        require(store.outboxSnapshot().stream().filter(record -> record.status() == OutboxStatus.PUBLISHED).count() == 2,
                "published state not persisted");
        require(store.outboxSnapshot().stream().filter(record -> record.status() == OutboxStatus.DEAD_LETTER).count() == 1,
                "dead-letter state not persisted");
    }

    private static void provesInboxDeduplicationAndTransactionalRetry() {
        InMemoryEventStore store = new InMemoryEventStore();
        InboxProcessor processor = new InboxProcessor(store, CLOCK);
        EventEnvelope inbound = event(10);
        AtomicInteger handlerCalls = new AtomicInteger();
        TransactionOutcome<InboxProcessingResult> first = processor.process(
                "core.search-projection", inbound, (event, transaction) -> {
                    handlerCalls.incrementAndGet();
                    transaction.append(event(11));
                });
        require(first.value() == InboxProcessingResult.PROCESSED, "first delivery was not processed");
        TransactionOutcome<InboxProcessingResult> duplicate = processor.process(
                "core.search-projection", inbound, (event, transaction) -> handlerCalls.incrementAndGet());
        require(duplicate.value() == InboxProcessingResult.DUPLICATE, "redelivery was not deduplicated");
        require(handlerCalls.get() == 1, "duplicate invoked handler");
        require(store.inboxSnapshot().size() == 1, "inbox receipt not committed");
        require(store.outboxSnapshot().size() == 1, "handler-produced outbox event not committed");

        InMemoryEventStore retryStore = new InMemoryEventStore();
        InboxProcessor retryProcessor = new InboxProcessor(retryStore, CLOCK);
        boolean rolledBack = false;
        try {
            retryProcessor.process("core.search-projection", inbound, (event, transaction) -> {
                transaction.append(event(12));
                throw new IllegalStateException("projection unavailable");
            });
        } catch (TransactionExecutionException expected) {
            rolledBack = true;
        }
        require(rolledBack, "failed handler did not roll back");
        require(retryStore.inboxSnapshot().isEmpty(), "failed inbox receipt persisted");
        require(retryStore.outboxSnapshot().isEmpty(), "failed handler outbox event persisted");
        require(retryProcessor.process("core.search-projection", inbound, (event, transaction) -> {}).value()
                        == InboxProcessingResult.PROCESSED,
                "redelivery after rollback was not accepted");
    }

    private static void provesConcurrentTransactionsAndClaims() throws Exception {
        InMemoryEventStore store = new InMemoryEventStore();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Void>> writes = java.util.stream.IntStream.range(0, 200)
                    .mapToObj(index -> (Callable<Void>) () -> {
                        store.execute(transaction -> {
                            transaction.append(event(1_000 + index));
                            return null;
                        });
                        return null;
                    })
                    .toList();
            for (Future<Void> future : executor.invokeAll(writes)) future.get();
            require(store.outboxSnapshot().size() == 200, "concurrent commits lost events");

            List<Callable<List<OutboxRecord>>> claims = List.of(
                    () -> store.claimBatch("worker-a", 100, NOW.plusSeconds(120), Duration.ofMinutes(1)),
                    () -> store.claimBatch("worker-b", 100, NOW.plusSeconds(120), Duration.ofMinutes(1)));
            Set<String> claimedIds = new HashSet<>();
            for (Future<List<OutboxRecord>> future : executor.invokeAll(claims)) {
                for (OutboxRecord record : future.get()) claimedIds.add(record.event().eventId().toString());
            }
            require(claimedIds.size() == 200, "concurrent claims duplicated or lost events");
        }
    }

    private static void provesInputGuards() {
        expect(IllegalArgumentException.class, () -> new EventType("invalid"));
        expect(IllegalArgumentException.class, () -> eventWithPayload("true"));
        expect(IllegalArgumentException.class, () -> new InboxKey("X", event(1).eventId()));
        expect(IllegalArgumentException.class, () -> new ExponentialBackoffPolicy(
                0, Duration.ofSeconds(1), Duration.ofSeconds(2), 0.0, () -> 0.0));
        expect(IllegalStateException.class, () -> new ExponentialBackoffPolicy(
                1, Duration.ofSeconds(1), Duration.ofSeconds(2), 0.1, () -> 2.0).delayAfterFailure(1));
        expect(IllegalArgumentException.class, () -> new DispatchReport(1, 1, 1, 0));

        InMemoryEventStore store = new InMemoryEventStore();
        expect(TransactionExecutionException.class, () -> store.execute(transaction -> {
            transaction.beginInbox(new InboxReservation(
                    new InboxKey("core.consumer", event(1).eventId()),
                    event(1).eventType(),
                    "0".repeat(64),
                    NOW));
            return null;
        }));
        expect(IllegalArgumentException.class,
                () -> store.claimBatch("worker", 0, NOW, Duration.ofSeconds(1)));
    }

    private static EventEnvelope event(int sequence) {
        String suffix = "%012d".formatted(sequence);
        return new EventEnvelope(
                id("018bcfe5-6800-7000-8000-" + suffix),
                new EventType("core.asset.created.v1"),
                ContractVersion.parse("1.0.0"),
                NOW.plusMillis(sequence),
                new EventSource("core/server-1"),
                id("018bcfe5-6800-7002-8000-" + suffix),
                null,
                "{\"sequence\":" + sequence + "}");
    }

    private static EventEnvelope eventWithPayload(String payload) {
        return new EventEnvelope(
                id("018bcfe5-6800-7000-8000-000000000001"),
                new EventType("core.asset.created.v1"),
                ContractVersion.parse("1.0.0"),
                NOW,
                new EventSource("core/server-1"),
                id("018bcfe5-6800-7002-8000-000000000001"),
                null,
                payload);
    }

    private static DomainIdentifier id(String value) {
        return new DomainIdentifier(UUID.fromString(value));
    }

    private static void expect(Class<? extends Throwable> type, ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable error) {
            require(type.isInstance(error), "unexpected exception type: " + error);
            return;
        }
        throw new AssertionError("expected exception " + type.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
