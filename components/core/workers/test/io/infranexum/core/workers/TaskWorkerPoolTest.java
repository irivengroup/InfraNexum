package io.infranexum.core.workers;

import static io.infranexum.core.workers.WorkerTestFixtures.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.RetryPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** End-to-end pool tests for bounded concurrency, heartbeat and truthful shutdown. */
final class TaskWorkerPoolTest {
    private static final RetryPolicy RETRY = new WorkerTestFixtures.FixedRetryPolicy(3, Duration.ofMillis(20));

    @Test
    void poolNeverExceedsConfiguredConcurrencyAndShutsDownGracefully() throws Exception {
        Clock clock = Clock.systemUTC();
        InMemoryTaskStore store = new InMemoryTaskStore();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(6);
        TaskHandler handler = handler(RetrySafety.RETRY_SAFE, context -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(60);
            } finally {
                active.decrementAndGet();
                completed.countDown();
            }
        });
        TaskHandlerRegistry registry = new TaskHandlerRegistry(List.of(handler));
        TaskScheduler scheduler = new TaskScheduler(store, registry, new UuidV7Generator(), clock);
        for (int index = 0; index < 6; index++) {
            scheduler.schedule(new TaskSubmission(TYPE, "bounded-" + index, Map.of(), clock.instant()));
        }
        WorkerPoolConfiguration configuration = new WorkerPoolConfiguration(
                2, Duration.ofMillis(5), Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(2));

        TaskWorkerPool pool = new TaskWorkerPool(store, registry, RETRY, clock, "bounded", configuration);
        pool.start();
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        ShutdownReport report = pool.shutdown();

        assertTrue(report.graceful());
        assertTrue(report.terminated());
        assertEquals(WorkerPoolState.TERMINATED, pool.state());
        assertTrue(maximum.get() >= 1);
        assertTrue(maximum.get() <= 2);
        assertEquals(report, pool.shutdown());
        assertThrows(IllegalStateException.class, pool::start);
    }

    @Test
    void heartbeatPreventsASecondWorkerFromReclaimingLongExecution() throws Exception {
        Clock clock = Clock.systemUTC();
        InMemoryTaskStore store = new InMemoryTaskStore();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        TaskHandler handler = handler(RetrySafety.RETRY_SAFE, context -> {
            entered.countDown();
            Thread.sleep(350);
            completed.countDown();
        });
        TaskHandlerRegistry registry = new TaskHandlerRegistry(List.of(handler));
        TaskScheduler scheduler = new TaskScheduler(store, registry, new UuidV7Generator(), clock);
        TaskSubmissionResult task = scheduler.schedule(
                new TaskSubmission(TYPE, "heartbeat", Map.of(), clock.instant()));
        WorkerPoolConfiguration configuration = new WorkerPoolConfiguration(
                1, Duration.ofMillis(5), Duration.ofMillis(120), Duration.ofMillis(25), Duration.ofSeconds(2));

        TaskWorkerPool pool = new TaskWorkerPool(store, registry, RETRY, clock, "heartbeat", configuration);
        pool.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        Thread.sleep(220);

        assertTrue(store.claimBatch("intruder", 1, clock.instant(), Duration.ofMillis(120), RETRY).isEmpty());
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertTrue(pool.shutdown().terminated());
        assertEquals(TaskStatus.SUCCEEDED, store.find(task.taskId()).orElseThrow().status());
    }

    @Test
    void shutdownBeforeStartIsGracefulAndIdempotent() {
        TaskHandler handler = handler(RetrySafety.RETRY_SAFE, context -> {});
        TaskWorkerPool pool = new TaskWorkerPool(
                new InMemoryTaskStore(),
                new TaskHandlerRegistry(List.of(handler)),
                RETRY,
                Clock.systemUTC(),
                "never-started",
                new WorkerPoolConfiguration(
                        1, Duration.ofMillis(5), Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(1)));

        ShutdownReport first = pool.shutdown();
        assertTrue(first.graceful());
        assertTrue(first.terminated());
        assertEquals(WorkerPoolState.TERMINATED, pool.state());
        assertEquals(first, pool.shutdown());
    }

    @Test
    void forcedShutdownReportsNonTerminationUntilInterruptIgnoringHandlerActuallyStops() throws Exception {
        Clock clock = Clock.systemUTC();
        InMemoryTaskStore store = new InMemoryTaskStore();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TaskHandler handler = handler(RetrySafety.RETRY_SAFE, context -> {
            entered.countDown();
            boolean done = false;
            while (!done) {
                try {
                    done = release.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException signal) {
                    interrupted.countDown();
                    // Deliberately emulate a non-cooperative integration. The runtime must
                    // report that the Java thread is still alive instead of claiming success.
                }
            }
        });
        TaskHandlerRegistry registry = new TaskHandlerRegistry(List.of(handler));
        TaskScheduler scheduler = new TaskScheduler(store, registry, new UuidV7Generator(), clock);
        scheduler.schedule(new TaskSubmission(TYPE, "forced", Map.of(), Instant.now(clock)));
        WorkerPoolConfiguration configuration = new WorkerPoolConfiguration(
                1, Duration.ofMillis(5), Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofMillis(50));
        TaskWorkerPool pool = new TaskWorkerPool(store, registry, RETRY, clock, "forced", configuration);
        pool.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        ShutdownReport first = pool.shutdown();

        assertTrue(first.forced());
        assertFalse(first.terminated());
        assertEquals(WorkerPoolState.STOPPING, pool.state());
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        release.countDown();
        ShutdownReport second = pool.shutdown();
        assertTrue(second.forced());
        assertTrue(second.terminated());
        assertEquals(WorkerPoolState.TERMINATED, pool.state());
    }

    private static TaskHandler handler(RetrySafety safety, HandlerBody body) {
        return new TaskHandler() {
            @Override
            public TaskType taskType() {
                return TYPE;
            }

            @Override
            public RetrySafety retrySafety() {
                return safety;
            }

            @Override
            public void execute(TaskExecutionContext context) throws Exception {
                body.execute(context);
            }
        };
    }

    @FunctionalInterface
    private interface HandlerBody {
        void execute(TaskExecutionContext context) throws Exception;
    }
}
