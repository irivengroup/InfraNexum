package io.infranexum.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OutboxDispatcherTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-03T12:01:00Z"), ZoneOffset.UTC);

    @Test
    void publishesBoundedBatchAndPersistsSuccess() {
        InMemoryEventStore store = storeWithEvents(3);
        List<EventEnvelope> published = new ArrayList<>();
        OutboxDispatcher dispatcher = dispatcher(store, published::add, 2, 3);
        assertEquals(new DispatchReport(2, 2, 0, 0), dispatcher.dispatchOnce());
        assertEquals(2, published.size());
        assertEquals(2, store.outboxSnapshot().stream()
                .filter(record -> record.status() == OutboxStatus.PUBLISHED).count());
        assertEquals(new DispatchReport(1, 1, 0, 0), dispatcher.dispatchOnce());
    }

    @Test
    void retriesAndDeadLettersTransportFailures() {
        InMemoryEventStore store = storeWithEvents(1);
        AtomicInteger calls = new AtomicInteger();
        OutboxDispatcher dispatcher = dispatcher(store, event -> {
            calls.incrementAndGet();
            throw new IllegalStateException("broker offline");
        }, 10, 1);
        assertEquals(new DispatchReport(1, 0, 0, 1), dispatcher.dispatchOnce());
        assertEquals(1, calls.get());
        assertEquals(OutboxStatus.DEAD_LETTER, store.outboxSnapshot().getFirst().status());
    }

    @Test
    void validatesDispatcherConfigurationAndReportReconciliation() {
        InMemoryEventStore store = new InMemoryEventStore();
        RetryPolicy retry = new ExponentialBackoffPolicy(
                3, Duration.ofSeconds(1), Duration.ofMinutes(1), 0.0, () -> 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxDispatcher(store, event -> {}, retry, CLOCK, " ", 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxDispatcher(store, event -> {}, retry, CLOCK, "worker", 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxDispatcher(store, event -> {}, retry, CLOCK, "worker", 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new DispatchReport(1, 1, 1, 0));
    }

    private static InMemoryEventStore storeWithEvents(int count) {
        InMemoryEventStore store = new InMemoryEventStore();
        store.execute(transaction -> {
            for (int index = 1; index <= count; index++) transaction.append(InMemoryEventStoreTest.event(index));
            return null;
        });
        return store;
    }

    private static OutboxDispatcher dispatcher(
            InMemoryEventStore store, EventTransport transport, int batchSize, int maxAttempts) {
        RetryPolicy retry = new ExponentialBackoffPolicy(
                maxAttempts, Duration.ofSeconds(1), Duration.ofMinutes(1), 0.0, () -> 0.0);
        return new OutboxDispatcher(store, transport, retry, CLOCK, "worker-1", batchSize, Duration.ofSeconds(30));
    }
}
