package io.infranexum.server.integrations;

import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorSyncRun;
import io.infranexum.integrations.ConnectorSyncRunStatus;
import io.infranexum.integrations.OutboundNotificationPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/**
 * Admits critical connector-sync lifecycle events to the durable signed-webhook notification outbox.
 *
 * <p>Notification admission is intentionally best-effort relative to the synchronization mutation: a
 * notification storage/configuration failure is surfaced through a metric and warning but never rewrites a
 * successfully persisted connector-sync result into an operator-visible mutation failure.</p>
 */
final class OutboundConnectorSyncOperationalNotifier implements ConnectorSyncOperationalNotifier {
    private static final System.Logger LOGGER =
            System.getLogger(OutboundConnectorSyncOperationalNotifier.class.getName());
    private static final String METRIC = "infranexum.integrations.sync.notifications";

    private final OutboundNotificationPublisher publisher;
    private final ObjectMapper json;
    private final MeterRegistry meters;
    private final List<ConnectorKey> endpointKeys;

    OutboundConnectorSyncOperationalNotifier(
            OutboundNotificationPublisher publisher,
            ObjectMapper json,
            MeterRegistry meters,
            List<ConnectorKey> endpointKeys) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.json = Objects.requireNonNull(json, "json");
        this.meters = Objects.requireNonNull(meters, "meters");
        this.endpointKeys = List.copyOf(Objects.requireNonNullElse(endpointKeys, List.<ConnectorKey>of()));
    }

    @Override
    public void publish(ConnectorSyncRun run) {
        Objects.requireNonNull(run, "run");
        String eventType = eventType(run.status());
        if (eventType == null || endpointKeys.isEmpty()) return;
        try {
            byte[] payload = json.writeValueAsBytes(Payload.from(run));
            publisher.publish(eventId(run), eventType, payload, endpointKeys);
            meters.counter(METRIC, "status", metricStatus(run.status()), "outcome", "admitted").increment();
        } catch (Exception failure) {
            meters.counter(METRIC, "status", metricStatus(run.status()), "outcome", "failed").increment();
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Connector sync operational notification admission failed: run={0} connector={1} status={2} failure={3}",
                    run.runId(), run.connectorKey().value(), run.status().name(), failure.getClass().getSimpleName());
        }
    }

    private static String eventType(ConnectorSyncRunStatus status) {
        return switch (status) {
            case PAUSED -> "integrations.sync.paused";
            case FAILED -> "integrations.sync.failed";
            case COMPENSATED -> "integrations.sync.compensated";
            case COMPENSATION_FAILED -> "integrations.sync.compensation-failed";
            case RUNNING, SUCCEEDED, COMPENSATING -> null;
        };
    }

    private static String eventId(ConnectorSyncRun run) {
        return "sync.%s.%s.%d.%d".formatted(
                run.runId(), metricStatus(run.status()), run.lastCheckpointRevision(), run.updatedAt().toEpochMilli());
    }

    private static String metricStatus(ConnectorSyncRunStatus status) {
        return status.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Secret-free payload intentionally limited to operational identifiers and durable run state. */
    private record Payload(
            String schemaVersion,
            String runId,
            String connectorKey,
            String provider,
            String direction,
            String rollbackStrategy,
            String status,
            long checkpointRevision,
            String failureCode,
            String correlationId,
            Instant occurredAt) {
        static Payload from(ConnectorSyncRun run) {
            return new Payload(
                    "1.0",
                    run.runId().toString(),
                    run.connectorKey().value(),
                    run.provider(),
                    run.direction().name(),
                    run.rollbackStrategy().name(),
                    run.status().name(),
                    run.lastCheckpointRevision(),
                    run.failureCode(),
                    run.correlationId().toString(),
                    run.updatedAt());
        }
    }
}
