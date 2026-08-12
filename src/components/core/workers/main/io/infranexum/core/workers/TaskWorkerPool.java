package io.infranexum.core.workers;

import io.infranexum.core.events.RetryPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Fixed-concurrency worker runtime with lease heartbeats and bounded shutdown.
 *
 * <p>The pool submits exactly one long-lived loop per configured worker. Business task
 * executions are never placed into an unbounded executor queue. A dedicated heartbeat
 * thread fences long-running executions from lease expiry while graceful shutdown lets
 * in-flight handlers finish until the configured deadline.
 */
public final class TaskWorkerPool implements AutoCloseable {
    private final WorkerPoolConfiguration configuration;
    private final Clock clock;
    private final ExecutorService workerExecutor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final List<TaskWorker> workers;
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicReference<WorkerPoolState> state = new AtomicReference<>(WorkerPoolState.NEW);
    private final AtomicReference<ShutdownReport> shutdownReport = new AtomicReference<>();
    private final AtomicInteger liveWorkers = new AtomicInteger();
    private final LongAdder claimed = new LongAdder();
    private final LongAdder succeeded = new LongAdder();
    private final LongAdder retried = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder cancelled = new LongAdder();
    private final LongAdder abandoned = new LongAdder();
    private final LongAdder fatalLoopFailures = new LongAdder();

    public TaskWorkerPool(
            TaskStore store,
            TaskHandlerRegistry registry,
            RetryPolicy retryPolicy,
            Clock clock,
            String runtimeId,
            WorkerPoolConfiguration configuration) {
        this(store, registry, retryPolicy, clock, runtimeId, configuration, TaskExecutionScopeFactory.noop());
    }

    public TaskWorkerPool(
            TaskStore store,
            TaskHandlerRegistry registry,
            RetryPolicy retryPolicy,
            Clock clock,
            String runtimeId,
            WorkerPoolConfiguration configuration,
            TaskExecutionScopeFactory executionScopeFactory) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        Objects.requireNonNull(executionScopeFactory, "executionScopeFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        String normalizedRuntimeId = requireText(runtimeId, "runtimeId", 120);
        this.workerExecutor = Executors.newFixedThreadPool(
                configuration.concurrency(), namedFactory("infranexum-worker-" + normalizedRuntimeId + "-"));
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
                namedFactory("infranexum-worker-heartbeat-" + normalizedRuntimeId + "-"));
        List<TaskWorker> configured = new ArrayList<>(configuration.concurrency());
        for (int index = 1; index <= configuration.concurrency(); index++) {
            configured.add(new TaskWorker(
                    store,
                    registry,
                    retryPolicy,
                    clock,
                    normalizedRuntimeId + "-" + index,
                    configuration.leaseDuration(),
                    stopRequested::get,
                    executionScopeFactory));
        }
        this.workers = List.copyOf(configured);
    }

    /** Starts the fixed set of worker loops exactly once. */
    public synchronized void start() {
        if (state.get() != WorkerPoolState.NEW) {
            throw new IllegalStateException("worker pool can only be started from NEW state");
        }
        state.set(WorkerPoolState.RUNNING);
        for (TaskWorker worker : workers) {
            workerExecutor.submit(() -> runLoop(worker));
        }
        long heartbeatNanos = configuration.heartbeatInterval().toNanos();
        heartbeatExecutor.scheduleWithFixedDelay(
                this::heartbeatAll, heartbeatNanos, heartbeatNanos, TimeUnit.NANOSECONDS);
    }

    public WorkerPoolState state() {
        return state.get();
    }

    public int activeExecutions() {
        return (int) workers.stream().filter(TaskWorker::active).count();
    }

    /** Returns a stable, secret-free view used by readiness and metrics adapters. */
    public WorkerPoolSnapshot snapshot() {
        return new WorkerPoolSnapshot(
                state.get(),
                configuration.concurrency(),
                liveWorkers.get(),
                activeExecutions(),
                claimed.sum(),
                succeeded.sum(),
                retried.sum(),
                failed.sum(),
                cancelled.sum(),
                abandoned.sum(),
                fatalLoopFailures.sum());
    }

    /** Stops accepting new claims and waits a bounded interval for active handlers. */
    public synchronized ShutdownReport shutdown() {
        ShutdownReport previous = shutdownReport.get();
        if (previous != null && previous.terminated()) {
            return previous;
        }
        Instant started = clock.instant();
        WorkerPoolState current = state.get();
        if (current == WorkerPoolState.NEW) {
            // start() and shutdown() share the same monitor, therefore NEW cannot
            // change underneath this transition. Avoid a racy compare-and-set
            // branch and make lifecycle ownership explicit.
            state.set(WorkerPoolState.TERMINATED);
            stopRequested.set(true);
            workerExecutor.shutdownNow();
            heartbeatExecutor.shutdownNow();
            ShutdownReport report = new ShutdownReport(
                    true, false, true, configuration.concurrency(), Duration.ZERO);
            shutdownReport.set(report);
            return report;
        }
        state.set(WorkerPoolState.STOPPING);
        stopRequested.set(true);
        workerExecutor.shutdown();
        boolean forcePreviouslyRequested = previous != null && previous.forced();
        // Capture and clear a pre-existing interruption before any blocking cleanup.
        // Otherwise ExecutorService implementations may either return termination or
        // throw immediately, making the shutdown report depend on scheduler timing.
        boolean interrupted = Thread.interrupted();
        AwaitResult gracefulAwait = interrupted
                ? new AwaitResult(workerExecutor.isTerminated(), false)
                : await(workerExecutor, configuration.shutdownTimeout());
        interrupted |= gracefulAwait.interrupted();
        boolean terminatedWithoutNewForce = gracefulAwait.terminated();
        boolean forced = forcePreviouslyRequested || !terminatedWithoutNewForce || interrupted;
        boolean graceful = terminatedWithoutNewForce && !forced;
        boolean workersTerminated = terminatedWithoutNewForce;
        if (!terminatedWithoutNewForce || interrupted) {
            workerExecutor.shutdownNow();
            AwaitResult forcedAwait = await(workerExecutor, configuration.shutdownTimeout());
            interrupted |= forcedAwait.interrupted();
            workersTerminated = forcedAwait.terminated();
        }
        heartbeatExecutor.shutdownNow();
        AwaitResult heartbeatAwait = await(heartbeatExecutor, configuration.shutdownTimeout());
        interrupted |= heartbeatAwait.interrupted();
        boolean terminated = workersTerminated && heartbeatAwait.terminated();
        state.set(terminated ? WorkerPoolState.TERMINATED : WorkerPoolState.STOPPING);
        ShutdownReport report = new ShutdownReport(
                graceful,
                forced,
                terminated,
                configuration.concurrency(),
                nonNegativeDuration(started, clock.instant()));
        shutdownReport.set(report);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return report;
    }

    @Override
    public void close() {
        shutdown();
    }

    private void runLoop(TaskWorker worker) {
        liveWorkers.incrementAndGet();
        try {
            while (!stopRequested.get()) {
                WorkerIterationReport report = worker.runOnce();
                record(report);
                if (stopRequested.get()) {
                    break;
                }
                if (report.claimed() == 0) {
                    try {
                        TimeUnit.NANOSECONDS.sleep(configuration.pollInterval().toNanos());
                    } catch (InterruptedException interrupted) {
                        if (stopRequested.get()) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (Thread.currentThread().isInterrupted() && !stopRequested.get()) {
                    Thread.interrupted();
                }
            }
        } catch (RuntimeException failure) {
            // ExecutorService otherwise hides loop termination inside its Future. Record the
            // failure so readiness becomes fail-closed instead of reporting a partially dead pool.
            fatalLoopFailures.increment();
            throw failure;
        } finally {
            liveWorkers.decrementAndGet();
        }
    }

    private void record(WorkerIterationReport report) {
        claimed.add(report.claimed());
        succeeded.add(report.succeeded());
        retried.add(report.retried());
        failed.add(report.failed());
        cancelled.add(report.cancelled());
        abandoned.add(report.abandoned());
    }

    private void heartbeatAll() {
        if (state.get() != WorkerPoolState.RUNNING && state.get() != WorkerPoolState.STOPPING) {
            return;
        }
        for (TaskWorker worker : workers) {
            worker.heartbeat();
        }
    }

    private static AwaitResult await(ExecutorService executor, Duration timeout) {
        try {
            return new AwaitResult(
                    executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS), false);
        } catch (InterruptedException interrupted) {
            // Preserve cleanup determinism: the caller restores interruption only after
            // every executor has received its bounded shutdown opportunity.
            return new AwaitResult(executor.isTerminated(), true);
        }
    }

    private record AwaitResult(boolean terminated, boolean interrupted) {}

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }

    private static String requireText(String value, String field, int maximumLength) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must contain 1-" + maximumLength + " characters");
        }
        return normalized;
    }

    private static Duration nonNegativeDuration(Instant start, Instant end) {
        if (end.isBefore(start)) {
            return Duration.ZERO;
        }
        return Duration.between(start, end);
    }
}
