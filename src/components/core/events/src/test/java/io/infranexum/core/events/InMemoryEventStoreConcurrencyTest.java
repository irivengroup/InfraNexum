package io.infranexum.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InMemoryEventStoreConcurrencyTest {
    @Test
    void concurrentCommitsAndClaimsDoNotLoseOrDuplicateEvents() throws Exception {
        InMemoryEventStore store = new InMemoryEventStore();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> writes = java.util.stream.IntStream.range(0, 200)
                    .mapToObj(index -> (Callable<Void>) () -> {
                        store.execute(transaction -> {
                            transaction.append(InMemoryEventStoreTest.event(1_000 + index));
                            return null;
                        });
                        return null;
                    })
                    .toList();
            for (Future<Void> future : executor.invokeAll(writes)) future.get();
            assertEquals(200, store.outboxSnapshot().size());

            Instant now = Instant.parse("2026-08-03T13:00:00Z");
            List<Callable<List<OutboxRecord>>> claims = List.of(
                    () -> store.claimBatch("worker-a", 100, now, Duration.ofMinutes(1)),
                    () -> store.claimBatch("worker-b", 100, now, Duration.ofMinutes(1)));
            Set<String> eventIds = new HashSet<>();
            for (Future<List<OutboxRecord>> future : executor.invokeAll(claims)) {
                for (OutboxRecord record : future.get()) eventIds.add(record.event().eventId().toString());
            }
            assertEquals(200, eventIds.size());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
