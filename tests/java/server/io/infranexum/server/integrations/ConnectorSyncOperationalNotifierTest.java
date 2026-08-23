package io.infranexum.server.integrations;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorRollbackStrategy;
import io.infranexum.integrations.ConnectorSyncDirection;
import io.infranexum.integrations.ConnectorSyncRun;
import io.infranexum.integrations.ConnectorSyncRunStatus;
import io.infranexum.integrations.OutboundNotificationPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/** Regression coverage for durable operational notifications emitted by connector synchronization. */
final class ConnectorSyncOperationalNotifierTest {
    private static final ConnectorKey ENDPOINT = new ConnectorKey("ops-webhook");
    private static final Instant NOW = Instant.parse("2026-08-23T17:00:00Z");

    @Test
    void criticalStatusPublishesDeterministicSecretFreePayload() throws Exception {
        OutboundNotificationPublisher publisher = mock(OutboundNotificationPublisher.class);
        when(publisher.publish(any(), any(), any(), any())).thenReturn(List.of());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            var notifier = new OutboundConnectorSyncOperationalNotifier(
                    publisher, new ObjectMapper(), meters, List.of(ENDPOINT));

            notifier.publish(run(ConnectorSyncRunStatus.PAUSED, "REMOTE_TIMEOUT"));

            ArgumentCaptor<String> eventId = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
            verify(publisher).publish(
                    eventId.capture(), eq("integrations.sync.paused"), payload.capture(), eq(List.of(ENDPOINT)));
            assertTrue(eventId.getValue().startsWith("sync.0198b180-0000-7001-8000-000000000001.paused.7."));

            var json = new ObjectMapper().readTree(payload.getValue());
            assertEquals("1.0", json.get("schemaVersion").asText());
            assertEquals("future-sync", json.get("connectorKey").asText());
            assertEquals("PAUSED", json.get("status").asText());
            assertEquals("REMOTE_TIMEOUT", json.get("failureCode").asText());
            assertEquals(7L, json.get("checkpointRevision").asLong());
            assertFalse(json.has("actorId"));
            assertFalse(json.has("idempotencyKey"));
            assertFalse(json.has("requestSha256"));
            assertFalse(json.has("fields"));
            assertFalse(json.has("cursor"));
            assertEquals(1.0, meters.find("infranexum.integrations.sync.notifications")
                    .tag("status", "paused").tag("outcome", "admitted").counter().count());
        } finally {
            meters.close();
        }
    }

    @Test
    void successfulAndRunningStatesDoNotGenerateOperationalNoise() {
        OutboundNotificationPublisher publisher = mock(OutboundNotificationPublisher.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            var notifier = new OutboundConnectorSyncOperationalNotifier(
                    publisher, new ObjectMapper(), meters, List.of(ENDPOINT));
            notifier.publish(run(ConnectorSyncRunStatus.RUNNING, null));
            notifier.publish(run(ConnectorSyncRunStatus.SUCCEEDED, null));
            verifyNoInteractions(publisher);
        } finally {
            meters.close();
        }
    }

    @Test
    void notificationAdmissionFailureIsObservableButCannotRewriteSyncOutcome() {
        OutboundNotificationPublisher publisher = mock(OutboundNotificationPublisher.class);
        doThrow(new IllegalStateException("repository unavailable"))
                .when(publisher).publish(any(), any(), any(), any());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            var notifier = new OutboundConnectorSyncOperationalNotifier(
                    publisher, new ObjectMapper(), meters, List.of(ENDPOINT));
            assertDoesNotThrow(() -> notifier.publish(run(ConnectorSyncRunStatus.FAILED, "REMOTE_REJECTED")));
            assertEquals(1.0, meters.find("infranexum.integrations.sync.notifications")
                    .tag("status", "failed").tag("outcome", "failed").counter().count());
        } finally {
            meters.close();
        }
    }

    @Test
    void emptySubscriptionListIsStrictlyOptIn() {
        OutboundNotificationPublisher publisher = mock(OutboundNotificationPublisher.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            var notifier = new OutboundConnectorSyncOperationalNotifier(
                    publisher, new ObjectMapper(), meters, List.of());
            notifier.publish(run(ConnectorSyncRunStatus.COMPENSATION_FAILED, "ROLLBACK_REJECTED"));
            verifyNoInteractions(publisher);
        } finally {
            meters.close();
        }
    }

    private static ConnectorSyncRun run(ConnectorSyncRunStatus status, String failureCode) {
        return new ConnectorSyncRun(
                id("0198b180-0000-7001-8000-000000000001"),
                new ConnectorKey("future-sync"),
                "future-provider",
                ConnectorSyncDirection.OUTBOUND,
                ConnectorRollbackStrategy.REMOTE_COMPENSATION,
                status,
                "idem-0001",
                "a".repeat(64),
                Set.of("name"),
                false,
                10,
                0,
                7,
                failureCode,
                id("0198b180-0000-7002-8000-000000000002"),
                id("0198b180-0000-7003-8000-000000000003"),
                NOW.minusSeconds(30),
                NOW,
                status == ConnectorSyncRunStatus.FAILED
                        || status == ConnectorSyncRunStatus.SUCCEEDED
                        || status == ConnectorSyncRunStatus.COMPENSATED
                        || status == ConnectorSyncRunStatus.COMPENSATION_FAILED ? NOW : null,
                status == ConnectorSyncRunStatus.COMPENSATED
                        || status == ConnectorSyncRunStatus.COMPENSATION_FAILED ? 7L : null);
    }

    private static DomainIdentifier id(String value) {
        return new DomainIdentifier(UUID.fromString(value));
    }
}
