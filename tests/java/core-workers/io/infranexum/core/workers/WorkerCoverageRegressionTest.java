package io.infranexum.core.workers;

import static io.infranexum.core.workers.WorkerTestFixtures.LEASE;
import static io.infranexum.core.workers.WorkerTestFixtures.RETRY;
import static io.infranexum.core.workers.WorkerTestFixtures.START;
import static io.infranexum.core.workers.WorkerTestFixtures.TYPE;
import static io.infranexum.core.workers.WorkerTestFixtures.id;
import static io.infranexum.core.workers.WorkerTestFixtures.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.events.RetryPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Regression coverage for fail-closed worker validation and recovery branches. */
final class WorkerCoverageRegressionTest {
    @Test
    void inMemoryStoreRejectsAllInvalidLeaseAndCapacityBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryTaskStore(10_000_001));
        InMemoryTaskStore store = new InMemoryTaskStore(3);
        store.submit(id(1), submission("one"), RetrySafety.RETRY_SAFE, START);
        assertThrows(
                IdempotencyConflictException.class,
                () -> store.submit(
                        id(2),
                        new TaskSubmission(TYPE, "one", Map.of("site", "paris"), START.plusSeconds(1)),
                        RetrySafety.RETRY_SAFE,
                        START));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.submit(id(1), submission("other-key"), RetrySafety.RETRY_SAFE, START));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.claimBatch("w".repeat(161), 1, START, LEASE, RETRY));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.claimBatch("worker", 1, START, Duration.ofSeconds(-1), RETRY));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.claimBatch("worker", 1, Instant.MAX, Duration.ofSeconds(1), RETRY));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.renewLease(id(99), "worker", 1, START, LEASE));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.renewLease(id(1), "worker", 0, START, LEASE));
        assertTrue(store.find(id(99)).isEmpty());

        TaskRecord claimed = store.claimBatch("worker", 1, START, LEASE, RETRY).getFirst();
        store.markSucceeded(claimed.taskId(), "worker", claimed.leaseVersion(), START);
        assertThrows(
                TaskLeaseLostException.class,
                () -> store.renewLease(claimed.taskId(), "worker", claimed.leaseVersion(), START, LEASE));
    }

    @Test
    void expiredRetrySafeLeaseAtMaximumAttemptsFailsClosed() {
        RetryPolicy oneAttempt = new WorkerTestFixtures.FixedRetryPolicy(1, Duration.ofSeconds(1));
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission("max-attempt"), RetrySafety.RETRY_SAFE, START);
        store.claimBatch("worker", 1, START, LEASE, oneAttempt);

        assertTrue(store.claimBatch("recovery", 1, START.plus(LEASE), LEASE, oneAttempt).isEmpty());
        TaskRecord failed = store.find(id(1)).orElseThrow();
        assertEquals(TaskStatus.FAILED, failed.status());
        assertTrue(failed.optionalFailure().orElseThrow().contains("maximum retry attempts"));
    }

    @Test
    void failureTransitionsHonorCancellationAndSanitizeEmptyMessages() {
        InMemoryTaskStore retryStore = new InMemoryTaskStore();
        retryStore.submit(id(1), submission("cancel-failure"), RetrySafety.RETRY_SAFE, START);
        TaskRecord retryTask = retryStore.claimBatch("worker", 1, START, LEASE, RETRY).getFirst();
        retryStore.requestCancellation(retryTask.taskId(), START);
        assertEquals(
                TaskStatus.CANCELLED,
                retryStore.markFailed(
                        retryTask.taskId(), "worker", retryTask.leaseVersion(), START, RETRY, new RuntimeException()));
        assertEquals("RuntimeException", retryStore.find(retryTask.taskId()).orElseThrow().lastFailure());

        InMemoryTaskStore terminalStore = new InMemoryTaskStore();
        terminalStore.submit(id(2), submission("cancel-terminal"), RetrySafety.RETRY_SAFE, START);
        TaskRecord terminalTask = terminalStore.claimBatch("worker", 1, START, LEASE, RETRY).getFirst();
        terminalStore.requestCancellation(terminalTask.taskId(), START);
        terminalStore.markTerminalFailure(
                terminalTask.taskId(), "worker", terminalTask.leaseVersion(), START, new RuntimeException("   "));
        TaskRecord cancelled = terminalStore.find(terminalTask.taskId()).orElseThrow();
        assertEquals(TaskStatus.CANCELLED, cancelled.status());
        assertEquals("RuntimeException", cancelled.lastFailure());
    }

    @Test
    void executionContextDetectsMissingChangedAndStoreRejectedLeases() throws Exception {
        TaskRecord pending = record(TaskStatus.PENDING, null, 0, null, null, false);
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskExecutionContext(new ProbeStore(), Clock.fixed(START, ZoneOffset.UTC), LEASE, pending));

        ProbeStore missingStore = claimedStore("worker");
        TaskRecord claimed = missingStore.delegate.find(id(1)).orElseThrow();
        TaskExecutionContext missing = context(missingStore, claimed, START, LEASE);
        missingStore.hideFind = true;
        assertTrue(missing.cancellationRequested());
        assertTrue(missing.leaseLost());
        assertTrue(missing.cancellationRequested());
        assertThrows(TaskLeaseLostException.class, missing::throwIfCancellationRequested);
        assertThrows(TaskLeaseLostException.class, missing::renewLease);

        ProbeStore changedStore = claimedStore("worker");
        TaskRecord changedClaim = changedStore.delegate.find(id(1)).orElseThrow();
        TaskExecutionContext changed = context(changedStore, changedClaim, START, LEASE);
        changedStore.findOverride = record(
                TaskStatus.RUNNING,
                "other-worker",
                changedClaim.leaseVersion(),
                changedClaim.leaseUntil(),
                null,
                false);
        assertTrue(changed.cancellationRequested());

        ProbeStore versionStore = claimedStore("worker");
        TaskRecord versionClaim = versionStore.delegate.find(id(1)).orElseThrow();
        TaskExecutionContext versionChanged = context(versionStore, versionClaim, START, LEASE);
        versionStore.findOverride = record(
                TaskStatus.RUNNING,
                "worker",
                versionClaim.leaseVersion() + 1,
                versionClaim.leaseUntil(),
                null,
                false);
        assertTrue(versionChanged.cancellationRequested());

        ProbeStore saveStore = claimedStore("worker");
        TaskRecord saveClaim = saveStore.delegate.find(id(1)).orElseThrow();
        TaskExecutionContext save = context(saveStore, saveClaim, START, LEASE);
        saveStore.saveFailure = new TaskLeaseLostException("stale checkpoint");
        assertThrows(TaskLeaseLostException.class, () -> save.saveCheckpoint("cursor"));
        assertTrue(save.leaseLost());
    }

    @Test
    void executionContextAccessorsAndLeaseOverflowAreObservable() throws Exception {
        ProbeStore store = claimedStore("worker");
        TaskRecord claimed = store.delegate.find(id(1)).orElseThrow();
        TaskExecutionContext context = context(store, claimed, START, LEASE);
        assertEquals(claimed.taskId(), context.taskId());
        assertEquals(TYPE, context.taskType());
        assertEquals(Map.of("site", "paris"), context.parameters());
        assertTrue(context.checkpoint().isEmpty());
        assertEquals(claimed.leaseUntil(), context.leaseUntil());
        context.renewLease();
        assertFalse(context.leaseLost());

        ProbeStore overflowStore = new ProbeStore();
        TaskRecord overflowClaim = record(
                TaskStatus.RUNNING, "worker", 1, Instant.MAX, null, false);
        overflowStore.findOverride = overflowClaim;
        overflowStore.saveOverride = new TaskCheckpoint(1, "cursor", Instant.MAX);
        TaskExecutionContext overflow = context(overflowStore, overflowClaim, Instant.MAX, Duration.ofSeconds(1));
        assertThrows(IllegalStateException.class, () -> overflow.saveCheckpoint("cursor"));
    }

    @Test
    void registryRejectsNullHandlersAndInvalidContracts() {
        assertThrows(NullPointerException.class, () -> new TaskHandlerRegistry(null));
        List<TaskHandler> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new TaskHandlerRegistry(withNull));
        assertThrows(
                NullPointerException.class,
                () -> new TaskHandlerRegistry(List.of(handler(null, RetrySafety.RETRY_SAFE, context -> {}))));
        assertThrows(
                NullPointerException.class,
                () -> new TaskHandlerRegistry(List.of(handler(TYPE, null, context -> {}))));
        TaskHandlerRegistry registry = new TaskHandlerRegistry(List.of(handler(TYPE, RetrySafety.RETRY_SAFE, context -> {})));
        assertThrows(NullPointerException.class, () -> registry.require(null));
        assertThrows(NullPointerException.class, () -> registry.find(null));
    }

    @Test
    void immutableWorkerContractsCoverAllValidationBoundaries() {
        assertThrows(NullPointerException.class, () -> new TaskId(null));
        assertThrows(NullPointerException.class, () -> id(1).compareTo(null));
        assertThrows(NullPointerException.class, () -> new TaskSubmissionResult(null, true));
        assertThrows(IllegalArgumentException.class, () -> new TaskType("   "));
        assertThrows(NullPointerException.class, () -> new TaskType(null));
        assertThrows(NullPointerException.class, () -> TYPE.compareTo(null));

        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskSubmission(TYPE, "x".repeat(257), Map.of(), START));
        LinkedHashMap<String, String> nullKey = new LinkedHashMap<>();
        nullKey.put(null, "value");
        assertThrows(NullPointerException.class, () -> new TaskSubmission(TYPE, "key", nullKey, START));
        LinkedHashMap<String, String> nullValue = new LinkedHashMap<>();
        nullValue.put("key", null);
        assertThrows(NullPointerException.class, () -> new TaskSubmission(TYPE, "key", nullValue, START));
        LinkedHashMap<String, String> duplicateNormalized = new LinkedHashMap<>();
        duplicateNormalized.put("dup", "1");
        duplicateNormalized.put(" dup ", "2");
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskSubmission(TYPE, "key", duplicateNormalized, START));
        LinkedHashMap<String, String> aggregate = new LinkedHashMap<>();
        for (int index = 0; index < 9; index++) {
            aggregate.put("p" + index, "x".repeat(4_096));
        }
        assertThrows(IllegalArgumentException.class, () -> new TaskSubmission(TYPE, "key", aggregate, START));

        assertThrows(NullPointerException.class, () -> new TaskCheckpoint(1, null, START));
        assertThrows(IllegalArgumentException.class, () -> new TaskCheckpoint(1, "x".repeat(4_097), START));
        assertThrows(NullPointerException.class, () -> new TaskCheckpoint(1, "ok", null));

        assertThrows(IllegalArgumentException.class, () -> record(TaskStatus.PENDING, null, 0, null, null, false, -1, null));
        assertThrows(IllegalArgumentException.class, () -> record(TaskStatus.PENDING, null, -1, null, null, false));
        assertThrows(NullPointerException.class, () -> record(TaskStatus.RUNNING, null, 1, START.plusSeconds(1), null, false));
        assertThrows(IllegalArgumentException.class, () -> record(TaskStatus.RUNNING, " ", 1, START.plusSeconds(1), null, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> record(TaskStatus.RUNNING, "w".repeat(161), 1, START.plusSeconds(1), null, false));
        assertThrows(NullPointerException.class, () -> record(TaskStatus.RUNNING, "worker", 1, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> record(TaskStatus.PENDING, null, 0, START, null, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> record(TaskStatus.FAILED, null, 0, null, "x".repeat(1_025), false));

        assertFalse(TaskStatus.PENDING.terminal());
        assertFalse(TaskStatus.RUNNING.terminal());
        assertTrue(TaskStatus.SUCCEEDED.terminal());
        assertTrue(TaskStatus.FAILED.terminal());
        assertTrue(TaskStatus.CANCELLED.terminal());

        assertThrows(IllegalArgumentException.class, () -> configuration(257, Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class, () -> configuration(1, Duration.ofMillis(-1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkerPoolConfiguration(
                        1, Duration.ofMillis(1), Duration.ofDays(31), Duration.ofMillis(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ShutdownReport(false, false, true, 1, Duration.ZERO));

        assertThrows(IllegalArgumentException.class, () -> new WorkerIterationReport(1, -1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerIterationReport(1, 0, -1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerIterationReport(1, 0, 0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerIterationReport(1, 0, 0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerIterationReport(1, 0, 0, 0, 0, -1));
    }

    @Test
    void taskWorkerCoversCancellationInterruptionAndFailureFencingBranches() {
        MutableClock clock = new MutableClock(START);
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskWorker(
                        new InMemoryTaskStore(),
                        new TaskHandlerRegistry(List.of()),
                        RETRY,
                        clock,
                        " ",
                        LEASE,
                        () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskWorker(
                        new InMemoryTaskStore(),
                        new TaskHandlerRegistry(List.of()),
                        RETRY,
                        clock,
                        "w".repeat(161),
                        LEASE,
                        () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskWorker(
                        new InMemoryTaskStore(),
                        new TaskHandlerRegistry(List.of()),
                        RETRY,
                        clock,
                        "worker",
                        Duration.ZERO,
                        () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskWorker(
                        new InMemoryTaskStore(),
                        new TaskHandlerRegistry(List.of()),
                        RETRY,
                        clock,
                        "worker",
                        Duration.ofSeconds(-1),
                        () -> false));

        InMemoryTaskStore lostStore = taskStore("lease-lost-cancel");
        TaskWorker lostWorker = worker(lostStore, clock, context -> {
            context.markLeaseLost();
            throw new TaskCancelledException("lost");
        }, () -> false);
        lostWorker.heartbeat();
        assertEquals(new WorkerIterationReport(1, 0, 0, 0, 0, 1), lostWorker.runOnce());

        AtomicBoolean shutdown = new AtomicBoolean();
        InMemoryTaskStore interruptStore = taskStore("shutdown-interrupt");
        TaskWorker interruptWorker = worker(interruptStore, clock, context -> {
            shutdown.set(true);
            throw new InterruptedException("shutdown");
        }, shutdown::get);
        try {
            assertEquals(new WorkerIterationReport(1, 0, 0, 0, 0, 1), interruptWorker.runOnce());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        InMemoryTaskStore lostInterruptStore = taskStore("lost-interrupt");
        TaskWorker lostInterruptWorker = worker(lostInterruptStore, clock, context -> {
            context.markLeaseLost();
            throw new InterruptedException("lost");
        }, () -> false);
        try {
            assertEquals(new WorkerIterationReport(1, 0, 0, 0, 0, 1), lostInterruptWorker.runOnce());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        InMemoryTaskStore exceptionStore = taskStore("lost-exception");
        TaskWorker exceptionWorker = worker(exceptionStore, clock, context -> {
            context.markLeaseLost();
            throw new IllegalStateException("stale");
        }, () -> false);
        assertEquals(new WorkerIterationReport(1, 0, 0, 0, 0, 1), exceptionWorker.runOnce());

        InMemoryTaskStore cancelledStore = taskStore("cancelled-failure");
        TaskWorker cancelledWorker = worker(cancelledStore, clock, context -> {
            cancelledStore.requestCancellation(context.taskId(), clock.instant());
            throw new IllegalStateException("after cancellation");
        }, () -> false);
        assertEquals(new WorkerIterationReport(1, 0, 0, 0, 1, 0), cancelledWorker.runOnce());
    }

    @Test
    void taskWorkerTreatsPersistenceRacesAndInvalidTransitionsFailClosed() {
        MutableClock clock = new MutableClock(START);
        ProbeStore leaseLostStore = claimedStorePending("mark-failed-lost");
        leaseLostStore.markFailedFailure = new TaskLeaseLostException("lost");
        TaskWorker leaseLostWorker = worker(leaseLostStore, clock, context -> {
            throw new IllegalStateException("handler failure");
        }, () -> false);
        assertEquals(new WorkerIterationReport(1, 0, 0, 0, 0, 1), leaseLostWorker.runOnce());

        ProbeStore invalidStore = claimedStorePending("invalid-transition");
        invalidStore.markFailedOverride = TaskStatus.SUCCEEDED;
        TaskWorker invalidWorker = worker(invalidStore, clock, context -> {
            throw new IllegalStateException("handler failure");
        }, () -> false);
        assertThrows(IllegalStateException.class, invalidWorker::runOnce);

        ProbeStore cancelRaceStore = claimedStorePending("cancel-race");
        cancelRaceStore.markCancelledFailure = new TaskLeaseLostException("lost before cancellation transition");
        TaskWorker cancelRaceWorker = worker(cancelRaceStore, clock, context -> {
            throw new TaskCancelledException("cancel");
        }, () -> false);
        assertEquals(new WorkerIterationReport(1, 0, 0, 0, 0, 1), cancelRaceWorker.runOnce());
    }

    @Test
    void heartbeatIgnoresTerminalRaceButFencesUnknownInfrastructureFailure() throws Exception {
        MutableClock clock = new MutableClock(START);
        ProbeStore terminalStore = claimedStorePending("heartbeat-terminal");
        CountDownLatch terminalEntered = new CountDownLatch(1);
        CountDownLatch terminalRelease = new CountDownLatch(1);
        TaskWorker terminalWorker = worker(terminalStore, clock, context -> {
            terminalEntered.countDown();
            terminalRelease.await(5, TimeUnit.SECONDS);
        }, () -> false);
        AtomicReference<WorkerIterationReport> terminalReport = new AtomicReference<>();
        Thread terminalThread = new Thread(() -> terminalReport.set(terminalWorker.runOnce()), "terminal-heartbeat-test");
        terminalThread.start();
        assertTrue(terminalEntered.await(5, TimeUnit.SECONDS));
        TaskRecord real = terminalStore.delegate.find(id(1)).orElseThrow();
        terminalStore.renewFailure = new TaskLeaseLostException("terminal race");
        terminalStore.findOverride = record(TaskStatus.SUCCEEDED, null, real.leaseVersion(), null, null, false);
        terminalWorker.heartbeat();
        terminalStore.renewFailure = null;
        terminalStore.findOverride = null;
        terminalRelease.countDown();
        terminalThread.join(5_000);
        assertFalse(terminalThread.isAlive());
        assertEquals(new WorkerIterationReport(1, 1, 0, 0, 0, 0), terminalReport.get());

        ProbeStore brokenStore = claimedStorePending("heartbeat-lookup");
        CountDownLatch brokenEntered = new CountDownLatch(1);
        TaskWorker brokenWorker = worker(brokenStore, clock, context -> {
            brokenEntered.countDown();
            new CountDownLatch(1).await();
        }, () -> false);
        AtomicReference<WorkerIterationReport> brokenReport = new AtomicReference<>();
        Thread brokenThread = new Thread(() -> brokenReport.set(brokenWorker.runOnce()), "broken-heartbeat-test");
        brokenThread.start();
        assertTrue(brokenEntered.await(5, TimeUnit.SECONDS));
        brokenStore.renewFailure = new IllegalStateException("renew unavailable");
        brokenStore.findFailure = new IllegalStateException("lookup unavailable");
        brokenWorker.heartbeat();
        brokenThread.join(5_000);
        assertFalse(brokenThread.isAlive());
        assertEquals(new WorkerIterationReport(1, 0, 0, 0, 0, 1), brokenReport.get());
    }

    @Test
    void workerPoolCoversForcedIdleShutdownAndInterruptedAwait() throws Exception {
        TaskHandler handler = handler(TYPE, RetrySafety.RETRY_SAFE, context -> {});
        WorkerPoolConfiguration slowPoll = new WorkerPoolConfiguration(
                1, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofMillis(20));
        TaskWorkerPool forcedIdle = new TaskWorkerPool(
                new InMemoryTaskStore(), new TaskHandlerRegistry(List.of(handler)), RETRY, Clock.systemUTC(), "idle", slowPoll);
        forcedIdle.start();
        Thread.sleep(40);
        ShutdownReport forced = forcedIdle.shutdown();
        assertTrue(forced.forced());
        assertTrue(forced.terminated());

        TaskWorkerPool interrupted = new TaskWorkerPool(
                new InMemoryTaskStore(),
                new TaskHandlerRegistry(List.of(handler)),
                RETRY,
                Clock.systemUTC(),
                "interrupted",
                new WorkerPoolConfiguration(
                        1, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofMillis(20)));
        interrupted.start();
        try {
            Thread.currentThread().interrupt();
            ShutdownReport report = interrupted.shutdown();
            assertTrue(report.forced());
            assertFalse(report.terminated());
        } finally {
            Thread.interrupted();
            interrupted.shutdown();
        }
    }

    @Test
    void workerPoolReportsActiveExecutionsAndClearsUnexpectedInterrupts() throws Exception {
        InMemoryTaskStore store = new InMemoryTaskStore();
        AtomicBoolean first = new AtomicBoolean(true);
        CountDownLatch completed = new CountDownLatch(1);
        TaskHandler handler = handler(TYPE, RetrySafety.RETRY_SAFE, context -> {
            if (first.getAndSet(false)) {
                throw new InterruptedException("transient interrupt");
            }
            completed.countDown();
        });
        TaskHandlerRegistry registry = new TaskHandlerRegistry(List.of(handler));
        store.submit(id(1), submission("pool-interrupt"), RetrySafety.RETRY_SAFE, Instant.now());
        TaskWorkerPool pool = new TaskWorkerPool(
                store,
                registry,
                new WorkerTestFixtures.FixedRetryPolicy(3, Duration.ofMillis(5)),
                Clock.systemUTC(),
                "interrupt-recovery",
                new WorkerPoolConfiguration(
                        1, Duration.ofMillis(1), Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(1)));
        pool.start();
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertTrue(pool.activeExecutions() <= 1);
        assertTrue(pool.shutdown().terminated());
    }

    @Test
    void workerPoolInternalGuardsRemainDeterministic() throws Exception {
        TaskHandlerRegistry registry = new TaskHandlerRegistry(List.of(handler(TYPE, RetrySafety.RETRY_SAFE, context -> {})));
        TaskWorkerPool pool = new TaskWorkerPool(
                new InMemoryTaskStore(),
                registry,
                RETRY,
                Clock.systemUTC(),
                "internal-guards",
                new WorkerPoolConfiguration(
                        1, Duration.ofMillis(5), Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(1)));

        java.lang.reflect.Method heartbeatAll = TaskWorkerPool.class.getDeclaredMethod("heartbeatAll");
        heartbeatAll.setAccessible(true);
        heartbeatAll.invoke(pool);
        assertEquals(WorkerPoolState.NEW, pool.state());

        java.lang.reflect.Method nonNegative = TaskWorkerPool.class.getDeclaredMethod(
                "nonNegativeDuration", Instant.class, Instant.class);
        nonNegative.setAccessible(true);
        assertEquals(Duration.ZERO, nonNegative.invoke(null, START, START.minusSeconds(1)));
        assertEquals(Duration.ofSeconds(1), nonNegative.invoke(null, START, START.plusSeconds(1)));
        assertTrue(pool.shutdown().terminated());

        TaskWorkerPool closePool = new TaskWorkerPool(
                new InMemoryTaskStore(),
                registry,
                RETRY,
                Clock.systemUTC(),
                "close-guard",
                new WorkerPoolConfiguration(
                        1, Duration.ofMillis(5), Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(1)));
        closePool.close();
        assertEquals(WorkerPoolState.TERMINATED, closePool.state());
    }

    @Test
    void taskWorkerActiveStateAndPostHandlerLeaseLossAreObservable() throws Exception {
        ProbeStore store = claimedStorePending("post-handler-lease-loss");
        MutableClock clock = new MutableClock(START);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TaskWorker worker = worker(store, clock, context -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            context.markLeaseLost();
        }, () -> false);
        assertFalse(worker.active());

        AtomicReference<WorkerIterationReport> report = new AtomicReference<>();
        Thread thread = new Thread(() -> report.set(worker.runOnce()), "active-state-test");
        thread.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(worker.active());
        release.countDown();
        thread.join(5_000);
        assertFalse(thread.isAlive());
        assertFalse(worker.active());
        assertEquals(new WorkerIterationReport(1, 0, 0, 0, 0, 1), report.get());
    }

    @Test
    void taskWorkerSerializesConcurrentRunOnceCallsWithoutLeakingASecondLease() throws Exception {
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission("serialized-one"), RetrySafety.RETRY_SAFE, START);
        store.submit(id(2), submission("serialized-two"), RetrySafety.RETRY_SAFE, START);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        TaskWorker worker = worker(store, new MutableClock(START), context -> {
            if (first.getAndSet(false)) {
                firstEntered.countDown();
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
            }
        }, () -> false);

        AtomicReference<WorkerIterationReport> firstReport = new AtomicReference<>();
        AtomicReference<WorkerIterationReport> secondReport = new AtomicReference<>();
        Thread firstThread = new Thread(() -> firstReport.set(worker.runOnce()), "serialized-worker-first");
        Thread secondThread = new Thread(() -> secondReport.set(worker.runOnce()), "serialized-worker-second");
        firstThread.start();
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
        secondThread.start();
        Thread.sleep(30);
        assertTrue(secondThread.isAlive());
        assertEquals(TaskStatus.PENDING, store.find(id(2)).orElseThrow().status());

        releaseFirst.countDown();
        firstThread.join(5_000);
        secondThread.join(5_000);
        assertFalse(firstThread.isAlive());
        assertFalse(secondThread.isAlive());
        assertEquals(new WorkerIterationReport(1, 1, 0, 0, 0, 0), firstReport.get());
        assertEquals(new WorkerIterationReport(1, 1, 0, 0, 0, 0), secondReport.get());
        assertEquals(TaskStatus.SUCCEEDED, store.find(id(1)).orElseThrow().status());
        assertEquals(TaskStatus.SUCCEEDED, store.find(id(2)).orElseThrow().status());
    }

    @Test
    void workerPoolRunLoopRecoversFromUnexpectedSleepInterrupt() throws Exception {
        TaskHandlerRegistry registry = new TaskHandlerRegistry(List.of(handler(TYPE, RetrySafety.RETRY_SAFE, context -> {})));
        TaskWorkerPool pool = new TaskWorkerPool(
                new InMemoryTaskStore(),
                registry,
                RETRY,
                Clock.systemUTC(),
                "manual-loop",
                new WorkerPoolConfiguration(
                        1, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofSeconds(1)));
        TaskWorker worker = new TaskWorker(
                new InMemoryTaskStore(),
                registry,
                RETRY,
                Clock.systemUTC(),
                "manual-worker",
                Duration.ofSeconds(2),
                () -> false);
        java.lang.reflect.Method runLoop = TaskWorkerPool.class.getDeclaredMethod("runLoop", TaskWorker.class);
        runLoop.setAccessible(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread loop = new Thread(() -> {
            try {
                runLoop.invoke(pool, worker);
            } catch (java.lang.reflect.InvocationTargetException error) {
                failure.set(error.getCause());
            } catch (ReflectiveOperationException error) {
                failure.set(error);
            }
        }, "manual-worker-loop");
        loop.start();
        Thread.sleep(40);
        loop.interrupt();
        Thread.sleep(40);
        assertTrue(loop.isAlive());
        assertFalse(loop.isInterrupted());
        assertTrue(pool.shutdown().terminated());
        loop.join(5_000);
        assertFalse(loop.isAlive());
        assertEquals(null, failure.get());
    }

    @Test
    void workerPoolRejectsInvalidRuntimeIdentifiers() {
        TaskHandlerRegistry registry = new TaskHandlerRegistry(List.of(handler(TYPE, RetrySafety.RETRY_SAFE, context -> {})));
        WorkerPoolConfiguration configuration = new WorkerPoolConfiguration(
                1, Duration.ofMillis(1), Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskWorkerPool(new InMemoryTaskStore(), registry, RETRY, Clock.systemUTC(), " ", configuration));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskWorkerPool(
                        new InMemoryTaskStore(), registry, RETRY, Clock.systemUTC(), "r".repeat(121), configuration));
    }

    private static WorkerPoolConfiguration configuration(int concurrency, Duration poll) {
        return new WorkerPoolConfiguration(
                concurrency, poll, Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofSeconds(1));
    }

    private static TaskExecutionContext context(ProbeStore store, TaskRecord claimed, Instant now, Duration lease) {
        return new TaskExecutionContext(store, Clock.fixed(now, ZoneOffset.UTC), lease, claimed);
    }

    private static TaskRecord record(
            TaskStatus status,
            String leaseOwner,
            long leaseVersion,
            Instant leaseUntil,
            String failure,
            boolean cancellationRequested) {
        return record(status, leaseOwner, leaseVersion, leaseUntil, failure, cancellationRequested, 0, null);
    }

    private static TaskRecord record(
            TaskStatus status,
            String leaseOwner,
            long leaseVersion,
            Instant leaseUntil,
            String failure,
            boolean cancellationRequested,
            int attempts,
            TaskCheckpoint checkpoint) {
        return new TaskRecord(
                id(1),
                TYPE,
                "key",
                Map.of("site", "paris"),
                RetrySafety.RETRY_SAFE,
                status,
                attempts,
                START,
                leaseOwner,
                leaseVersion,
                leaseUntil,
                checkpoint,
                cancellationRequested,
                failure,
                START,
                START);
    }

    private static InMemoryTaskStore taskStore(String key) {
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission(key), RetrySafety.RETRY_SAFE, START);
        return store;
    }

    private static ProbeStore claimedStore(String workerId) {
        ProbeStore store = new ProbeStore();
        store.delegate.submit(id(1), submission("context"), RetrySafety.RETRY_SAFE, START);
        store.delegate.claimBatch(workerId, 1, START, LEASE, RETRY);
        return store;
    }

    private static ProbeStore claimedStorePending(String key) {
        ProbeStore store = new ProbeStore();
        store.delegate.submit(id(1), submission(key), RetrySafety.RETRY_SAFE, START);
        return store;
    }

    private static TaskWorker worker(
            TaskStore store,
            MutableClock clock,
            HandlerBody body,
            java.util.function.BooleanSupplier shutdownRequested) {
        return new TaskWorker(
                store,
                new TaskHandlerRegistry(List.of(handler(TYPE, RetrySafety.RETRY_SAFE, body))),
                RETRY,
                clock,
                "worker",
                LEASE,
                shutdownRequested);
    }

    private static TaskHandler handler(TaskType type, RetrySafety safety, HandlerBody body) {
        return new TaskHandler() {
            @Override
            public TaskType taskType() {
                return type;
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

    /** Controllable persistence probe used to reproduce adapter races deterministically. */
    private static final class ProbeStore implements TaskStore {
        private final InMemoryTaskStore delegate = new InMemoryTaskStore();
        private boolean hideFind;
        private TaskRecord findOverride;
        private RuntimeException findFailure;
        private RuntimeException renewFailure;
        private RuntimeException saveFailure;
        private TaskCheckpoint saveOverride;
        private RuntimeException markFailedFailure;
        private TaskStatus markFailedOverride;
        private RuntimeException markCancelledFailure;

        @Override
        public TaskSubmissionResult submit(
                TaskId proposedId, TaskSubmission submission, RetrySafety retrySafety, Instant submittedAt) {
            return delegate.submit(proposedId, submission, retrySafety, submittedAt);
        }

        @Override
        public List<TaskRecord> claimBatch(
                String workerId, int limit, Instant now, Duration leaseDuration, RetryPolicy retryPolicy) {
            return delegate.claimBatch(workerId, limit, now, leaseDuration, retryPolicy);
        }

        @Override
        public void renewLease(TaskId taskId, String workerId, long leaseVersion, Instant now, Duration leaseDuration) {
            if (renewFailure != null) {
                throw renewFailure;
            }
            delegate.renewLease(taskId, workerId, leaseVersion, now, leaseDuration);
        }

        @Override
        public TaskCheckpoint saveCheckpoint(
                TaskId taskId,
                String workerId,
                long leaseVersion,
                String token,
                Instant now,
                Duration leaseDuration) {
            if (saveFailure != null) {
                throw saveFailure;
            }
            if (saveOverride != null) {
                return saveOverride;
            }
            return delegate.saveCheckpoint(taskId, workerId, leaseVersion, token, now, leaseDuration);
        }

        @Override
        public void markSucceeded(TaskId taskId, String workerId, long leaseVersion, Instant completedAt) {
            delegate.markSucceeded(taskId, workerId, leaseVersion, completedAt);
        }

        @Override
        public TaskStatus markFailed(
                TaskId taskId,
                String workerId,
                long leaseVersion,
                Instant failedAt,
                RetryPolicy retryPolicy,
                Throwable failure) {
            if (markFailedFailure != null) {
                throw markFailedFailure;
            }
            if (markFailedOverride != null) {
                return markFailedOverride;
            }
            return delegate.markFailed(taskId, workerId, leaseVersion, failedAt, retryPolicy, failure);
        }

        @Override
        public void markTerminalFailure(
                TaskId taskId, String workerId, long leaseVersion, Instant failedAt, Throwable failure) {
            delegate.markTerminalFailure(taskId, workerId, leaseVersion, failedAt, failure);
        }

        @Override
        public void markCancelled(TaskId taskId, String workerId, long leaseVersion, Instant cancelledAt) {
            if (markCancelledFailure != null) {
                throw markCancelledFailure;
            }
            delegate.markCancelled(taskId, workerId, leaseVersion, cancelledAt);
        }

        @Override
        public CancellationOutcome requestCancellation(TaskId taskId, Instant requestedAt) {
            return delegate.requestCancellation(taskId, requestedAt);
        }

        @Override
        public Optional<TaskRecord> find(TaskId taskId) {
            if (findFailure != null) {
                throw findFailure;
            }
            if (hideFind) {
                return Optional.empty();
            }
            if (findOverride != null) {
                return Optional.of(findOverride);
            }
            return delegate.find(taskId);
        }
    }
}
