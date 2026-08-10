package io.infranexum.core.workers;

/** Outcome counters for one finite worker iteration. */
public record WorkerIterationReport(
        int claimed,
        int succeeded,
        int retried,
        int failed,
        int cancelled,
        int abandoned) {
    public WorkerIterationReport {
        if (claimed < 0 || succeeded < 0 || retried < 0 || failed < 0 || cancelled < 0 || abandoned < 0) {
            throw new IllegalArgumentException("worker counters must not be negative");
        }
        if (succeeded + retried + failed + cancelled + abandoned > claimed) {
            throw new IllegalArgumentException("worker outcomes cannot exceed claimed tasks");
        }
    }

    public static WorkerIterationReport idle() {
        return new WorkerIterationReport(0, 0, 0, 0, 0, 0);
    }
}
