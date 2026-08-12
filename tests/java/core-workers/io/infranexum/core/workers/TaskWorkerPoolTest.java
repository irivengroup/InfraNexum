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
import java.util.Optional;
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
    void operationalSnapshotCountsOutcomesAndDetectsADeadWorkerLoop() throws Exception {
        Clock clock = Clock.systemUTC();
        InMemoryTaskStore store = new InMemoryTaskStore();
        CountDownLatch completed = new CountDownLatch(1);
        TaskHandler handler = handler(RetrySafety.RETRY_SAFE, context -> completed.countDown());
        TaskHandlerRegistry registry = new TaskHandlerRegistry(List.of(handler));
        TaskScheduler scheduler = new TaskScheduler(store, registry, new UuidV7Generator(), clock);
        scheduler.schedule(new TaskSubmission(TYPE, "metrics", Map.of(), clock.instant()));
        WorkerPoolConfiguration configuration = new WorkerPoolConfiguration(
                1, Duration.ofMillis(5), Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(1));
        TaskWorkerPool pool = new TaskWorkerPool(store, registry, RETRY, clock, "metrics", configuration);

        assertFalse(pool.snapshot().ready());
        pool.start();
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertTrue(awaitReady(pool, 5, TimeUnit.SECONDS));
        WorkerPoolSnapshot completedSnapshot = pool.snapshot();
        assertEquals(1, completedSnapshot.claimed());
        assertEquals(1, completedSnapshot.succeeded());
        assertEquals(0, completedSnapshot.fatalLoopFailures());
        assertTrue(pool.shutdown().terminated());

        TaskWorkerPool failingPool = new TaskWorkerPool(
                new FailingClaimStore(),
                registry,
                RETRY,
                clock,
                "failing",
                configuration);
        failingPool.start();
        assertTrue(awaitFatalFailure(failingPool, 5, TimeUnit.SECONDS));
        WorkerPoolSnapshot failedSnapshot = failingPool.snapshot();
        assertFalse(failedSnapshot.ready());
        assertEquals(0, failedSnapshot.liveWorkers());
        assertEquals(1, failedSnapshot.fatalLoopFailures());
        assertTrue(failingPool.shutdown().terminated());
    }

    @Test
    void transientTaskStoreOutageDoesNotKillWorkersAndReadinessRecovers() throws Exception {
        TaskHandlerRegistry registry = new TaskHandlerRegistry(List.of());
        WorkerPoolConfiguration configuration = new WorkerPoolConfiguration(
                1, Duration.ofMillis(5), Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(1));
        TaskWorkerPool pool = new TaskWorkerPool(
                new RecoveringClaimStore(3),
                registry,
                RETRY,
                Clock.systemUTC(),
                "recovering",
                configuration);

        pool.start();
        assertTrue(awaitReady(pool, 5, TimeUnit.SECONDS));
        WorkerPoolSnapshot snapshot = pool.snapshot();
        assertEquals(1, snapshot.liveWorkers());
        assertEquals(1, snapshot.storeReadyWorkers());
        assertTrue(snapshot.storeUnavailableFailures() >= 3);
        assertEquals(0, snapshot.fatalLoopFailures());
        assertTrue(snapshot.ready());
        assertTrue(pool.shutdown().terminated());
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

    private static boolean awaitReady(TaskWorkerPool pool, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (pool.snapshot().ready()) {
                return true;
            }
            Thread.sleep(5);
        }
        return pool.snapshot().ready();
    }

    private static boolean awaitFatalFailure(TaskWorkerPool pool, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (pool.snapshot().fatalLoopFailures() > 0) {
                return true;
            }
            Thread.sleep(5);
        }
        return pool.snapshot().fatalLoopFailures() > 0;
    }

    private static final class RecoveringClaimStore implements TaskStore {
        private final AtomicInteger failuresRemaining;

        private RecoveringClaimStore(int failures) {
            failuresRemaining = new AtomicInteger(failures);
        }

        @Override
        public TaskSubmissionResult submit(TaskId proposedId, TaskSubmission submission, RetrySafety retrySafety, Instant submittedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TaskRecord> claimBatch(String workerId, int limit, Instant now, Duration leaseDuration, RetryPolicy retryPolicy) {
            if (failuresRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new TaskStoreUnavailableException(new java.sql.SQLException("writer failover", "08006"));
            }
            return List.of();
        }

        @Override public void renewLease(TaskId taskId, String workerId, long leaseVersion, Instant now, Duration leaseDuration) { throw new UnsupportedOperationException(); }
        @Override public TaskCheckpoint saveCheckpoint(TaskId taskId, String workerId, long leaseVersion, String token, Instant now, Duration leaseDuration) { throw new UnsupportedOperationException(); }
        @Override public void markSucceeded(TaskId taskId, String workerId, long leaseVersion, Instant completedAt) { throw new UnsupportedOperationException(); }
        @Override public TaskStatus markFailed(TaskId taskId, String workerId, long leaseVersion, Instant failedAt, RetryPolicy retryPolicy, Throwable failure) { throw new UnsupportedOperationException(); }
        @Override public void markTerminalFailure(TaskId taskId, String workerId, long leaseVersion, Instant failedAt, Throwable failure) { throw new UnsupportedOperationException(); }
        @Override public void markCancelled(TaskId taskId, String workerId, long leaseVersion, Instant cancelledAt) { throw new UnsupportedOperationException(); }
        @Override public CancellationOutcome requestCancellation(TaskId taskId, Instant requestedAt) { throw new UnsupportedOperationException(); }
        @Override public Optional<TaskRecord> find(TaskId taskId) { return Optional.empty(); }
    }

    private static final class FailingClaimStore implements TaskStore {
        @Override
        public TaskSubmissionResult submit(TaskId proposedId, TaskSubmission submission, RetrySafety retrySafety, Instant submittedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TaskRecord> claimBatch(String workerId, int limit, Instant now, Duration leaseDuration, RetryPolicy retryPolicy) {
            throw new IllegalStateException("simulated persistence failure");
        }

        @Override
        public void renewLease(TaskId taskId, String workerId, long leaseVersion, Instant now, Duration leaseDuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskCheckpoint saveCheckpoint(TaskId taskId, String workerId, long leaseVersion, String token, Instant now, Duration leaseDuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markSucceeded(TaskId taskId, String workerId, long leaseVersion, Instant completedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskStatus markFailed(TaskId taskId, String workerId, long leaseVersion, Instant failedAt, RetryPolicy retryPolicy, Throwable failure) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markTerminalFailure(TaskId taskId, String workerId, long leaseVersion, Instant failedAt, Throwable failure) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markCancelled(TaskId taskId, String workerId, long leaseVersion, Instant cancelledAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CancellationOutcome requestCancellation(TaskId taskId, Instant requestedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<TaskRecord> find(TaskId taskId) {
            return Optional.empty();
        }
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
