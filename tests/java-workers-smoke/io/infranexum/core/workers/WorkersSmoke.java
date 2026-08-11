package io.infranexum.core.workers;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.RetryPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free executable smoke for PGM-02-E07 worker invariants. */
public final class WorkersSmoke {
    private static final Instant START = Instant.parse("2026-08-10T12:00:00Z");
    private static final TaskType TYPE = new TaskType("inventory.refresh");
    private static final RetryPolicy RETRY = new FixedRetryPolicy(3, Duration.ofMillis(25));

    private WorkersSmoke() {}

    public static void main(String[] args) throws Exception {
        verifyIdempotencyCheckpointRetryAndAtMostOnce();
        verifyCooperativeCancellation();
        verifyLeaseExpiryFencing();
        verifyBoundedPoolAndHeartbeat();
        verifyTruthfulForcedShutdown();
    }

    private static void verifyIdempotencyCheckpointRetryAndAtMostOnce() throws Exception {
        MutableClock clock = new MutableClock(START);
        InMemoryTaskStore store = new InMemoryTaskStore();
        TaskSubmission safeSubmission = submission("safe");
        TaskSubmissionResult created = store.submit(id(1), safeSubmission, RetrySafety.RETRY_SAFE, START);
        TaskSubmissionResult replay = store.submit(id(2), safeSubmission, RetrySafety.RETRY_SAFE, START.plusSeconds(1));
        assert created.created();
        assert !replay.created();
        assert created.taskId().equals(replay.taskId());

        AtomicInteger attempts = new AtomicInteger();
        TaskHandler safeHandler = handler(RetrySafety.RETRY_SAFE, context -> {
            context.saveCheckpoint("attempt=" + attempts.incrementAndGet());
            if (attempts.get() == 1) {
                throw new IllegalStateException("retry me");
            }
        });
        TaskWorker safeWorker = worker(store, safeHandler, clock);
        WorkerIterationReport first = safeWorker.runOnce();
        assert first.retried() == 1;
        clock.advance(Duration.ofMillis(25));
        WorkerIterationReport second = safeWorker.runOnce();
        assert second.succeeded() == 1;
        TaskRecord safe = store.find(created.taskId()).orElseThrow();
        assert safe.status() == TaskStatus.SUCCEEDED;
        assert safe.attempts() == 2;
        assert safe.optionalCheckpoint().orElseThrow().sequence() == 2;

        store.submit(id(3), submission("unsafe"), RetrySafety.AT_MOST_ONCE, clock.instant());
        TaskHandler unsafeHandler = handler(
                RetrySafety.AT_MOST_ONCE,
                context -> { throw new IllegalStateException("unknown external outcome"); });
        TaskWorker unsafeWorker = worker(store, unsafeHandler, clock);
        WorkerIterationReport unsafe = unsafeWorker.runOnce();
        assert unsafe.failed() == 1;
        assert store.find(id(3)).orElseThrow().status() == TaskStatus.FAILED;
    }

    private static void verifyCooperativeCancellation() throws Exception {
        MutableClock clock = new MutableClock(START);
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(10), submission("cancel"), RetrySafety.RETRY_SAFE, START);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TaskWorker worker = worker(store, handler(RetrySafety.RETRY_SAFE, context -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("cancellation smoke timeout");
            }
            context.throwIfCancellationRequested();
        }), clock);
        AtomicReference<WorkerIterationReport> report = new AtomicReference<>();
        Thread thread = new Thread(() -> report.set(worker.runOnce()), "workers-smoke-cancel");
        thread.start();
        assert entered.await(5, TimeUnit.SECONDS);
        assert store.requestCancellation(id(10), START.plusSeconds(1)) == CancellationOutcome.REQUESTED;
        release.countDown();
        thread.join(5_000);
        assert !thread.isAlive();
        assert report.get().cancelled() == 1;
        assert store.find(id(10)).orElseThrow().status() == TaskStatus.CANCELLED;
    }

    private static void verifyLeaseExpiryFencing() {
        MutableClock clock = new MutableClock(START);
        InMemoryTaskStore store = new InMemoryTaskStore();
        Duration lease = Duration.ofSeconds(2);
        store.submit(id(20), submission("lease-safe"), RetrySafety.RETRY_SAFE, START);
        store.submit(id(21), submission("lease-unsafe"), RetrySafety.AT_MOST_ONCE, START);
        List<TaskRecord> leased = store.claimBatch("worker-a", 2, START, lease, RETRY);
        clock.advance(lease);

        assert store.claimBatch("recovery", 2, clock.instant(), lease, RETRY).isEmpty();
        assert store.find(id(20)).orElseThrow().status() == TaskStatus.PENDING;
        assert store.find(id(21)).orElseThrow().status() == TaskStatus.FAILED;
        TaskRecord oldSafeLease = leased.stream().filter(task -> task.taskId().equals(id(20))).findFirst().orElseThrow();
        boolean fenced = false;
        try {
            store.markSucceeded(id(20), "worker-a", oldSafeLease.leaseVersion(), clock.instant());
        } catch (TaskLeaseLostException expected) {
            fenced = true;
        }
        assert fenced;
        clock.advance(Duration.ofMillis(25));
        TaskRecord recovered = store.claimBatch("worker-b", 1, clock.instant(), lease, RETRY).getFirst();
        assert recovered.taskId().equals(id(20));
        assert recovered.leaseVersion() > oldSafeLease.leaseVersion();
    }

    private static void verifyBoundedPoolAndHeartbeat() throws Exception {
        Clock clock = Clock.systemUTC();
        InMemoryTaskStore store = new InMemoryTaskStore();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch enteredLong = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(4);
        TaskHandler handler = handler(RetrySafety.RETRY_SAFE, context -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            try {
                if ("long".equals(context.parameters().get("kind"))) {
                    enteredLong.countDown();
                    Thread.sleep(300);
                } else {
                    Thread.sleep(60);
                }
            } finally {
                active.decrementAndGet();
                allDone.countDown();
            }
        });
        TaskHandlerRegistry registry = new TaskHandlerRegistry(List.of(handler));
        TaskScheduler scheduler = new TaskScheduler(store, registry, new UuidV7Generator(), clock);
        TaskSubmissionResult longTask = scheduler.schedule(new TaskSubmission(
                TYPE, "pool-long", Map.of("kind", "long"), clock.instant()));
        for (int index = 0; index < 3; index++) {
            scheduler.schedule(new TaskSubmission(TYPE, "pool-" + index, Map.of("kind", "short"), clock.instant()));
        }
        WorkerPoolConfiguration configuration = new WorkerPoolConfiguration(
                2, Duration.ofMillis(5), Duration.ofMillis(100), Duration.ofMillis(20), Duration.ofSeconds(2));
        TaskWorkerPool pool = new TaskWorkerPool(store, registry, RETRY, clock, "smoke", configuration);
        pool.start();
        assert enteredLong.await(5, TimeUnit.SECONDS);
        Thread.sleep(180);
        assert store.claimBatch("intruder", 1, clock.instant(), Duration.ofMillis(100), RETRY).isEmpty();
        assert allDone.await(5, TimeUnit.SECONDS);
        ShutdownReport report = pool.shutdown();
        assert report.graceful();
        assert report.terminated();
        assert maximum.get() <= 2;
        assert store.find(longTask.taskId()).orElseThrow().status() == TaskStatus.SUCCEEDED;
    }

    private static void verifyTruthfulForcedShutdown() throws Exception {
        Clock clock = Clock.systemUTC();
        InMemoryTaskStore store = new InMemoryTaskStore();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TaskHandler stubborn = handler(RetrySafety.RETRY_SAFE, context -> {
            entered.countDown();
            boolean done = false;
            while (!done) {
                try {
                    done = release.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                }
            }
        });
        TaskHandlerRegistry registry = new TaskHandlerRegistry(List.of(stubborn));
        TaskScheduler scheduler = new TaskScheduler(store, registry, new UuidV7Generator(), clock);
        scheduler.schedule(new TaskSubmission(TYPE, "stubborn", Map.of(), clock.instant()));
        TaskWorkerPool pool = new TaskWorkerPool(
                store,
                registry,
                RETRY,
                clock,
                "forced-smoke",
                new WorkerPoolConfiguration(
                        1, Duration.ofMillis(5), Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofMillis(40)));
        pool.start();
        assert entered.await(5, TimeUnit.SECONDS);
        ShutdownReport first = pool.shutdown();
        assert first.forced();
        assert !first.terminated();
        assert pool.state() == WorkerPoolState.STOPPING;
        assert interrupted.await(1, TimeUnit.SECONDS);
        release.countDown();
        ShutdownReport second = pool.shutdown();
        assert second.forced();
        assert second.terminated();
        assert pool.state() == WorkerPoolState.TERMINATED;
    }

    private static TaskSubmission submission(String key) {
        return new TaskSubmission(TYPE, key, Map.of(), START);
    }

    private static TaskWorker worker(InMemoryTaskStore store, TaskHandler handler, Clock clock) {
        return new TaskWorker(
                store,
                new TaskHandlerRegistry(List.of(handler)),
                RETRY,
                clock,
                "smoke-worker",
                Duration.ofSeconds(2),
                () -> false);
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

    private static TaskId id(long sequence) {
        long millis = START.toEpochMilli() + sequence;
        long most = (millis << 16) | 0x7000L | (sequence & 0x0fffL);
        long least = 0x8000_0000_0000_0000L | (sequence & 0x3fff_ffff_ffff_ffffL);
        return new TaskId(new DomainIdentifier(new UUID(most, least)));
    }

    @FunctionalInterface
    private interface HandlerBody {
        void execute(TaskExecutionContext context) throws Exception;
    }

    private static final class FixedRetryPolicy implements RetryPolicy {
        private final int maximumAttempts;
        private final Duration delay;

        private FixedRetryPolicy(int maximumAttempts, Duration delay) {
            this.maximumAttempts = maximumAttempts;
            this.delay = delay;
        }

        @Override
        public int maximumAttempts() {
            return maximumAttempts;
        }

        @Override
        public Duration delayAfterFailure(int attempts) {
            return delay;
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            instant = new AtomicReference<>(initial);
        }

        private void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
