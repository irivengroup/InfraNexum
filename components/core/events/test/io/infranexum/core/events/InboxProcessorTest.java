package io.infranexum.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InboxProcessorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-03T12:02:00Z"), ZoneOffset.UTC);

    @Test
    void processesOnceAndDeduplicatesCommittedRedelivery() {
        InMemoryEventStore store = new InMemoryEventStore();
        InboxProcessor processor = new InboxProcessor(store, CLOCK);
        AtomicInteger calls = new AtomicInteger();
        EventEnvelope inbound = InMemoryEventStoreTest.event(1);
        var first = processor.process("core.search-projection", inbound, (event, transaction) -> {
            calls.incrementAndGet();
            transaction.append(InMemoryEventStoreTest.event(2));
        });
        var duplicate = processor.process("core.search-projection", inbound, (event, transaction) -> calls.incrementAndGet());
        assertEquals(InboxProcessingResult.PROCESSED, first.value());
        assertEquals(InboxProcessingResult.DUPLICATE, duplicate.value());
        assertEquals(1, calls.get());
        assertEquals(1, store.inboxSnapshot().size());
        assertEquals(1, store.outboxSnapshot().size());
    }

    @Test
    void rollsBackReceiptAndProducedEventsWhenHandlerFailsThenAllowsRetry() {
        InMemoryEventStore store = new InMemoryEventStore();
        InboxProcessor processor = new InboxProcessor(store, CLOCK);
        EventEnvelope inbound = InMemoryEventStoreTest.event(1);
        assertThrows(TransactionExecutionException.class, () -> processor.process(
                "core.search-projection", inbound, (event, transaction) -> {
                    transaction.append(InMemoryEventStoreTest.event(2));
                    throw new IllegalStateException("projection failed");
                }));
        assertTrue(store.inboxSnapshot().isEmpty());
        assertTrue(store.outboxSnapshot().isEmpty());
        assertEquals(InboxProcessingResult.PROCESSED,
                processor.process("core.search-projection", inbound, (event, transaction) -> {}).value());
    }
}
