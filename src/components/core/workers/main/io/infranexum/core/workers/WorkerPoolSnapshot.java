package io.infranexum.core.workers;

import java.util.Objects;

/**
 * Secret-free operational snapshot of the bounded worker runtime.
 *
 * <p>The counters are cumulative for the lifetime of one {@link TaskWorkerPool}. Readiness is
 * deliberately strict: an enabled pool is ready only while every configured worker loop is alive
 * and no fatal loop failure has been observed.
 */
public record WorkerPoolSnapshot(
        WorkerPoolState state,
        int configuredConcurrency,
        int liveWorkers,
        int activeExecutions,
        long claimed,
        long succeeded,
        long retried,
        long failed,
        long cancelled,
        long abandoned,
        long fatalLoopFailures) {

    public WorkerPoolSnapshot {
        Objects.requireNonNull(state, "state");
        if (configuredConcurrency < 1) {
            throw new IllegalArgumentException("configuredConcurrency must be positive");
        }
        if (liveWorkers < 0 || liveWorkers > configuredConcurrency) {
            throw new IllegalArgumentException("liveWorkers must be between zero and configuredConcurrency");
        }
        if (activeExecutions < 0 || activeExecutions > configuredConcurrency) {
            throw new IllegalArgumentException("activeExecutions must be between zero and configuredConcurrency");
        }
        if (claimed < 0 || succeeded < 0 || retried < 0 || failed < 0 || cancelled < 0 || abandoned < 0
                || fatalLoopFailures < 0) {
            throw new IllegalArgumentException("worker operational counters must not be negative");
        }
        if (succeeded + retried + failed + cancelled + abandoned > claimed) {
            throw new IllegalArgumentException("worker task outcomes cannot exceed claimed tasks");
        }
    }

    /** Returns true only when the complete configured worker capacity is alive and healthy. */
    public boolean ready() {
        return state == WorkerPoolState.RUNNING
                && liveWorkers == configuredConcurrency
                && fatalLoopFailures == 0;
    }
}
