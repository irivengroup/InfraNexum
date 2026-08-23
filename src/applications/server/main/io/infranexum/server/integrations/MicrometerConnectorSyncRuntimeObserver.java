package io.infranexum.server.integrations;

import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorRollbackStrategy;
import io.infranexum.integrations.ConnectorSyncDirection;
import io.infranexum.integrations.ConnectorSyncPauseCause;
import io.infranexum.integrations.ConnectorSyncRunStatus;
import io.infranexum.integrations.ConnectorSyncRuntimeObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Micrometer adapter for durable synchronization metrics with bounded, provider-independent dimensions. */
final class MicrometerConnectorSyncRuntimeObserver implements ConnectorSyncRuntimeObserver {
    private static final String PREFIX = "infranexum.integrations.sync.";
    private final MeterRegistry registry;

    MicrometerConnectorSyncRuntimeObserver(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void admitted(ConnectorKey key, ConnectorSyncDirection direction, boolean duplicate) {
        registry.counter(
                        PREFIX + "admissions",
                        "connector", key.value(),
                        "direction", token(direction),
                        "outcome", duplicate ? "duplicate" : "created")
                .increment();
    }

    @Override
    public void resumed(ConnectorKey key, ConnectorSyncDirection direction) {
        registry.counter(
                        PREFIX + "activations",
                        "connector", key.value(),
                        "direction", token(direction),
                        "operation", "resume")
                .increment();
    }

    @Override
    public void batchApplied(
            ConnectorKey key,
            ConnectorSyncDirection direction,
            long processed,
            long changed,
            long rejected,
            boolean completed) {
        String directionToken = token(direction);
        registry.counter(
                        PREFIX + "batches",
                        "connector", key.value(),
                        "direction", directionToken,
                        "outcome", completed ? "completed" : "continued")
                .increment();
        incrementRecords(key, directionToken, "processed", processed);
        incrementRecords(key, directionToken, "changed", changed);
        incrementRecords(key, directionToken, "rejected", rejected);
    }

    @Override
    public void paused(ConnectorKey key, ConnectorSyncDirection direction, ConnectorSyncPauseCause cause) {
        registry.counter(
                        PREFIX + "pauses",
                        "connector", key.value(),
                        "direction", token(direction),
                        "cause", token(cause))
                .increment();
    }

    @Override
    public void compensationStarted(ConnectorKey key, ConnectorRollbackStrategy rollbackStrategy) {
        registry.counter(
                        PREFIX + "compensations",
                        "connector", key.value(),
                        "rollback", token(rollbackStrategy),
                        "outcome", "started")
                .increment();
    }

    @Override
    public void terminal(
            ConnectorKey key,
            ConnectorSyncDirection direction,
            ConnectorSyncRunStatus status,
            Duration elapsed) {
        if (!statusIsTerminal(status)) throw new IllegalArgumentException("sync terminal metric requires a terminal status");
        String directionToken = token(direction);
        String statusToken = token(status);
        registry.counter(
                        PREFIX + "terminal",
                        "connector", key.value(),
                        "direction", directionToken,
                        "status", statusToken)
                .increment();
        Timer.builder(PREFIX + "duration")
                .tag("connector", key.value())
                .tag("direction", directionToken)
                .tag("status", statusToken)
                .register(registry)
                .record(Objects.requireNonNull(elapsed, "elapsed"));
    }

    private void incrementRecords(ConnectorKey key, String direction, String kind, long count) {
        if (count < 0) throw new IllegalArgumentException("sync record count must be non-negative");
        if (count == 0) return;
        registry.counter(
                        PREFIX + "records",
                        "connector", key.value(),
                        "direction", direction,
                        "kind", kind)
                .increment(count);
    }

    private static boolean statusIsTerminal(ConnectorSyncRunStatus status) {
        return status == ConnectorSyncRunStatus.SUCCEEDED
                || status == ConnectorSyncRunStatus.FAILED
                || status == ConnectorSyncRunStatus.COMPENSATED
                || status == ConnectorSyncRunStatus.COMPENSATION_FAILED;
    }

    private static String token(Enum<?> value) {
        return Objects.requireNonNull(value, "metric enum").name().toLowerCase(Locale.ROOT);
    }
}
