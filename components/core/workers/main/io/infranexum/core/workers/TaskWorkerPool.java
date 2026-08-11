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

    public TaskWorkerPool(
            TaskStore store,
            TaskHandlerRegistry registry,
            RetryPolicy retryPolicy,
            Clock clock,
            String runtimeId,
            WorkerPoolConfiguration configuration) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
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
                    stopRequested::get));
        }
        this.workers = List.copyOf(configured);
    }

    /** Starts the fixed set of worker loops exactly once. */
    public void start() {
        if (!state.compareAndSet(WorkerPoolState.NEW, WorkerPoolState.RUNNING)) {
            throw new IllegalStateException("worker pool can only be started from NEW state");
        }
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

    /** Stops accepting new claims and waits a bounded interval for active handlers. */
    public synchronized ShutdownReport shutdown() {
        ShutdownReport previous = shutdownReport.get();
        if (previous != null && previous.terminated()) {
            return previous;
        }
        Instant started = clock.instant();
        WorkerPoolState current = state.get();
        if (current == WorkerPoolState.NEW) {
            if (state.compareAndSet(WorkerPoolState.NEW, WorkerPoolState.TERMINATED)) {
                stopRequested.set(true);
                workerExecutor.shutdownNow();
                heartbeatExecutor.shutdownNow();
                ShutdownReport report = new ShutdownReport(
                        true, false, true, configuration.concurrency(), Duration.ZERO);
                shutdownReport.set(report);
                return report;
            }
        }
        state.set(WorkerPoolState.STOPPING);
        stopRequested.set(true);
        workerExecutor.shutdown();
        boolean forcePreviouslyRequested = previous != null && previous.forced();
        boolean terminatedWithoutNewForce = await(workerExecutor, configuration.shutdownTimeout());
        boolean forced = forcePreviouslyRequested || !terminatedWithoutNewForce;
        boolean graceful = terminatedWithoutNewForce && !forced;
        boolean workersTerminated = terminatedWithoutNewForce;
        if (!terminatedWithoutNewForce) {
            workerExecutor.shutdownNow();
            workersTerminated = await(workerExecutor, configuration.shutdownTimeout());
        }
        heartbeatExecutor.shutdownNow();
        boolean heartbeatTerminated = await(heartbeatExecutor, configuration.shutdownTimeout());
        boolean terminated = workersTerminated && heartbeatTerminated;
        state.set(terminated ? WorkerPoolState.TERMINATED : WorkerPoolState.STOPPING);
        ShutdownReport report = new ShutdownReport(
                graceful,
                forced,
                terminated,
                configuration.concurrency(),
                nonNegativeDuration(started, clock.instant()));
        shutdownReport.set(report);
        return report;
    }

    @Override
    public void close() {
        shutdown();
    }

    private void runLoop(TaskWorker worker) {
        while (!stopRequested.get()) {
            WorkerIterationReport report = worker.runOnce();
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
    }

    private void heartbeatAll() {
        if (state.get() != WorkerPoolState.RUNNING && state.get() != WorkerPoolState.STOPPING) {
            return;
        }
        for (TaskWorker worker : workers) {
            worker.heartbeat();
        }
    }

    private static boolean await(ExecutorService executor, Duration timeout) {
        try {
            return executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

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
