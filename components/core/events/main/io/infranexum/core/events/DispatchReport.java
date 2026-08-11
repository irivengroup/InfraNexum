package io.infranexum.core.events;

/** Bounded result of one outbox dispatcher iteration. */
public record DispatchReport(int claimed, int published, int retried, int deadLettered) {
    public DispatchReport {
        if (claimed < 0 || published < 0 || retried < 0 || deadLettered < 0) {
            throw new IllegalArgumentException("dispatch counters must be non-negative");
        }
        if (published + retried + deadLettered != claimed) {
            throw new IllegalArgumentException("dispatch counters must reconcile with claimed");
        }
    }
}
