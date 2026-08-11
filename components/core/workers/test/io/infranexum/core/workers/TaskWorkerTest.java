package io.infranexum.core.workers;

import static io.infranexum.core.workers.WorkerTestFixtures.LEASE;
import static io.infranexum.core.workers.WorkerTestFixtures.RETRY;
import static io.infranexum.core.workers.WorkerTestFixtures.START;
import static io.infranexum.core.workers.WorkerTestFixtures.TYPE;
import static io.infranexum.core.workers.WorkerTestFixtures.id;
import static io.infranexum.core.workers.WorkerTestFixtures.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Worker execution tests for success, retry, cancellation, fencing and interruption. */
final class TaskWorkerTest {
    @Test
    void successfulHandlerCanCheckpointAndCompletesTask() {
        MutableClock clock = new MutableClock(START);
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission("success"), RetrySafety.RETRY_SAFE, START);
        TaskHandler handler = handler(RetrySafety.RETRY_SAFE, context -> {
            TaskCheckpoint checkpoint = context.saveCheckpoint("cursor=42");
            assertEquals(1, checkpoint.sequence());
            assertFalse(context.cancellationRequested());
        });
        TaskWorker worker = worker(store, handler, clock, () -> false);

        WorkerIterationReport report = worker.runOnce();

        assertEquals(new WorkerIterationReport(1, 1, 0, 0, 0, 0), report);
        TaskRecord task = store.find(id(1)).orElseThrow();
        assertEquals(TaskStatus.SUCCEEDED, task.status());
        assertEquals("cursor=42", task.optionalCheckpoint().orElseThrow().token());
        assertFalse(worker.active());
    }

    @Test
    void retrySafeHandlerRetriesThenSucceeds() {
        MutableClock clock = new MutableClock(START);
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission("retry"), RetrySafety.RETRY_SAFE, START);
        AtomicInteger attempts = new AtomicInteger();
        TaskHandler handler = handler(RetrySafety.RETRY_SAFE, context -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("transient");
            }
        });
        TaskWorker worker = worker(store, handler, clock, () -> false);

        assertEquals(new WorkerIterationReport(1, 0, 1, 0, 0, 0), worker.runOnce());
        assertEquals(TaskStatus.PENDING, store.find(id(1)).orElseThrow().status());
        assertEquals(WorkerIterationReport.idle(), worker.runOnce());
        clock.advance(java.time.Duration.ofSeconds(5));
        assertEquals(new WorkerIterationReport(1, 1, 0, 0, 0, 0), worker.runOnce());
        assertEquals(2, attempts.get());
    }

    @Test
    void atMostOnceFailureIsTerminal() {
        MutableClock clock = new MutableClock(START);
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission("unsafe"), RetrySafety.AT_MOST_ONCE, START);
        TaskWorker worker = worker(
                store,
                handler(RetrySafety.AT_MOST_ONCE, context -> { throw new IllegalStateException("unsafe failure"); }),
                clock,
                () -> false);

        assertEquals(new WorkerIterationReport(1, 0, 0, 1, 0, 0), worker.runOnce());
        assertEquals(TaskStatus.FAILED, store.find(id(1)).orElseThrow().status());
    }

    @Test
    void runningCancellationIsObservedCooperatively() throws Exception {
        MutableClock clock = new MutableClock(START);
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission("cancel"), RetrySafety.RETRY_SAFE, START);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch continueHandler = new CountDownLatch(1);
        TaskHandler handler = handler(RetrySafety.RETRY_SAFE, context -> {
            entered.countDown();
            if (!continueHandler.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release handler");
            }
            context.throwIfCancellationRequested();
        });
        TaskWorker worker = worker(store, handler, clock, () -> false);
        AtomicReference<WorkerIterationReport> report = new AtomicReference<>();
        Thread thread = new Thread(() -> report.set(worker.runOnce()), "worker-cancellation-test");
        thread.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        assertEquals(CancellationOutcome.REQUESTED, store.requestCancellation(id(1), START.plusSeconds(1)));
        continueHandler.countDown();
        thread.join(5_000);

        assertFalse(thread.isAlive());
        assertEquals(new WorkerIterationReport(1, 0, 0, 0, 1, 0), report.get());
        assertEquals(TaskStatus.CANCELLED, store.find(id(1)).orElseThrow().status());
    }

    @Test
    void heartbeatLeaseLossInterruptsExecutionAndAbandonsIt() throws Exception {
        MutableClock clock = new MutableClock(START);
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission("lease-loss"), RetrySafety.RETRY_SAFE, START);
        CountDownLatch entered = new CountDownLatch(1);
        TaskHandler handler = handler(RetrySafety.RETRY_SAFE, context -> {
            entered.countDown();
            new CountDownLatch(1).await();
        });
        TaskWorker worker = worker(store, handler, clock, () -> false);
        AtomicReference<WorkerIterationReport> report = new AtomicReference<>();
        Thread thread = new Thread(() -> report.set(worker.runOnce()), "worker-lease-loss-test");
        thread.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        clock.advance(LEASE);
        assertTrue(store.claimBatch("recovery-trigger", 1, clock.instant(), LEASE, RETRY).isEmpty());
        assertEquals(TaskStatus.PENDING, store.find(id(1)).orElseThrow().status());
        worker.heartbeat();
        thread.join(5_000);

        assertFalse(thread.isAlive());
        assertEquals(new WorkerIterationReport(1, 0, 0, 0, 0, 1), report.get());
        assertEquals(TaskStatus.PENDING, store.find(id(1)).orElseThrow().status());
    }

    @Test
    void staleLeaseDiscoveredByCheckpointIsAbandonedWithoutSecondMutation() {
        MutableClock clock = new MutableClock(START);
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission("stale-checkpoint"), RetrySafety.RETRY_SAFE, START);
        TaskHandler handler = handler(RetrySafety.RETRY_SAFE, context -> {
            clock.advance(LEASE);
            assertTrue(store.claimBatch("recovery", 1, clock.instant(), LEASE, RETRY).isEmpty());
            context.saveCheckpoint("must-not-persist");
        });
        TaskWorker worker = worker(store, handler, clock, () -> false);

        assertEquals(new WorkerIterationReport(1, 0, 0, 0, 0, 1), worker.runOnce());
        TaskRecord task = store.find(id(1)).orElseThrow();
        assertEquals(TaskStatus.PENDING, task.status());
        assertTrue(task.optionalCheckpoint().isEmpty());
    }

    @Test
    void staleLeaseAtCompletionIsAbandonedInsteadOfFailingARecoveredTask() {
        MutableClock clock = new MutableClock(START);
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission("stale-complete"), RetrySafety.RETRY_SAFE, START);
        TaskHandler handler = handler(RetrySafety.RETRY_SAFE, context -> {
            clock.advance(LEASE);
            assertTrue(store.claimBatch("recovery", 1, clock.instant(), LEASE, RETRY).isEmpty());
        });
        TaskWorker worker = worker(store, handler, clock, () -> false);

        assertEquals(new WorkerIterationReport(1, 0, 0, 0, 0, 1), worker.runOnce());
        assertEquals(TaskStatus.PENDING, store.find(id(1)).orElseThrow().status());
    }

    @Test
    void missingHandlerFailsClaimedTaskInsteadOfDroppingIt() {
        MutableClock clock = new MutableClock(START);
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission("orphan"), RetrySafety.RETRY_SAFE, START);
        TaskWorker worker = new TaskWorker(
                store, new TaskHandlerRegistry(List.of()), RETRY, clock, "worker", LEASE, () -> false);

        assertEquals(new WorkerIterationReport(1, 0, 0, 1, 0, 0), worker.runOnce());
        TaskRecord failed = store.find(id(1)).orElseThrow();
        assertEquals(TaskStatus.FAILED, failed.status());
        assertTrue(failed.optionalFailure().orElseThrow().contains("no longer registered"));
    }

    @Test
    void shutdownPreventsNewClaims() {
        MutableClock clock = new MutableClock(START);
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission("shutdown"), RetrySafety.RETRY_SAFE, START);
        TaskWorker worker = worker(store, handler(RetrySafety.RETRY_SAFE, context -> {}), clock, () -> true);

        assertEquals(WorkerIterationReport.idle(), worker.runOnce());
        assertEquals(TaskStatus.PENDING, store.find(id(1)).orElseThrow().status());
    }

    @Test
    void unexpectedInterruptIsRecordedAsFailureWhenShutdownWasNotRequested() {
        MutableClock clock = new MutableClock(START);
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission("interrupt"), RetrySafety.RETRY_SAFE, START);
        TaskWorker worker = worker(
                store,
                handler(RetrySafety.RETRY_SAFE, context -> { throw new InterruptedException("unexpected"); }),
                clock,
                () -> false);
        try {
            assertEquals(new WorkerIterationReport(1, 0, 1, 0, 0, 0), worker.runOnce());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static TaskWorker worker(
            InMemoryTaskStore store,
            TaskHandler handler,
            MutableClock clock,
            java.util.function.BooleanSupplier shutdown) {
        return new TaskWorker(
                store, new TaskHandlerRegistry(List.of(handler)), RETRY, clock, "worker", LEASE, shutdown);
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
