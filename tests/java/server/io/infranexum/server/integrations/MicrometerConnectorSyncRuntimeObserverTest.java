package io.infranexum.server.integrations;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorRollbackStrategy;
import io.infranexum.integrations.ConnectorSyncDirection;
import io.infranexum.integrations.ConnectorSyncPauseCause;
import io.infranexum.integrations.ConnectorSyncRunStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Verifies bounded sync metrics and rejects invalid terminal telemetry. */
final class MicrometerConnectorSyncRuntimeObserverTest {
    private static final ConnectorKey KEY = new ConnectorKey("jira-assets.itam");

    @Test void recordsLifecycleWithoutProviderPayloadCursorOrFailureDimensions() {
        try (SimpleMeterRegistry meters = new SimpleMeterRegistry()) {
            var observer = new MicrometerConnectorSyncRuntimeObserver(meters);
            observer.admitted(KEY, ConnectorSyncDirection.OUTBOUND, false);
            observer.admitted(KEY, ConnectorSyncDirection.OUTBOUND, true);
            observer.resumed(KEY, ConnectorSyncDirection.OUTBOUND);
            observer.batchApplied(KEY, ConnectorSyncDirection.OUTBOUND, 10, 7, 3, false);
            observer.batchApplied(KEY, ConnectorSyncDirection.OUTBOUND, 2, 2, 0, true);
            observer.paused(KEY, ConnectorSyncDirection.OUTBOUND, ConnectorSyncPauseCause.RETRYABLE_FAILURE);
            observer.compensationStarted(KEY, ConnectorRollbackStrategy.DUAL_COMPENSATION);
            observer.terminal(KEY, ConnectorSyncDirection.OUTBOUND, ConnectorSyncRunStatus.COMPENSATED, Duration.ofSeconds(4));

            assertEquals(2.0, meters.find("infranexum.integrations.sync.admissions").counters().stream().mapToDouble(c -> c.count()).sum());
            assertEquals(1.0, meters.find("infranexum.integrations.sync.activations").counter().count());
            assertEquals(2.0, meters.find("infranexum.integrations.sync.batches").counters().stream().mapToDouble(c -> c.count()).sum());
            assertEquals(24.0, meters.find("infranexum.integrations.sync.records").counters().stream().mapToDouble(c -> c.count()).sum());
            assertEquals(1.0, meters.find("infranexum.integrations.sync.pauses").counter().count());
            assertEquals(1.0, meters.find("infranexum.integrations.sync.compensations").counter().count());
            assertEquals(1.0, meters.find("infranexum.integrations.sync.terminal").counter().count());
            assertEquals(1, meters.find("infranexum.integrations.sync.duration").timer().count());
            meters.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> {
                assertFalse(tag.getKey().matches("(?i).*cursor.*|.*failure.*|.*payload.*|.*idempotency.*"));
                assertFalse(tag.getValue().contains("REMOTE_503"));
            }));
        }
    }

    @Test void validatesCountsTerminalStatusesAndDurations() {
        try (SimpleMeterRegistry meters = new SimpleMeterRegistry()) {
            var observer = new MicrometerConnectorSyncRuntimeObserver(meters);
            assertThrows(IllegalArgumentException.class, () -> observer.batchApplied(KEY, ConnectorSyncDirection.OUTBOUND, -1, 0, 0, false));
            assertThrows(IllegalArgumentException.class, () -> observer.terminal(KEY, ConnectorSyncDirection.OUTBOUND, ConnectorSyncRunStatus.PAUSED, Duration.ZERO));
            assertThrows(NullPointerException.class, () -> observer.terminal(KEY, ConnectorSyncDirection.OUTBOUND, ConnectorSyncRunStatus.FAILED, null));
        }
    }
}
