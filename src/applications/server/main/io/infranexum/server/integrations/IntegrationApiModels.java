package io.infranexum.server.integrations;

import io.infranexum.integrations.ConnectorDelivery;
import io.infranexum.integrations.ConnectorRuntimeState;
import java.time.Instant;

/** Wire models for the integration webhook, inbox/DLQ and runtime-control API. */
final class IntegrationApiModels {
    private IntegrationApiModels() {}

    record WebhookAdmissionResponse(
            String deliveryId,
            String connectorKey,
            String externalDeliveryId,
            String status,
            Instant receivedAt) {
        static WebhookAdmissionResponse from(ConnectorDelivery delivery, boolean duplicate) {
            return new WebhookAdmissionResponse(
                    delivery.deliveryId().toString(),
                    delivery.connectorKey().value(),
                    delivery.externalDeliveryId(),
                    duplicate ? "DUPLICATE" : "ADMITTED",
                    delivery.receivedAt());
        }
    }

    record DeadLetterResponse(
            String deliveryId,
            String connectorKey,
            String externalDeliveryId,
            String status,
            int attempts,
            Instant receivedAt,
            String failureClass,
            int replayCount,
            Instant lastReplayedAt) {
        static DeadLetterResponse from(ConnectorDelivery delivery) {
            return new DeadLetterResponse(
                    delivery.deliveryId().toString(),
                    delivery.connectorKey().value(),
                    delivery.externalDeliveryId(),
                    delivery.status().name(),
                    delivery.attempts(),
                    delivery.receivedAt(),
                    delivery.lastFailure(),
                    delivery.replayCount(),
                    delivery.lastReplayedAt());
        }
    }

    record RuntimeStateResponse(
            String connectorKey,
            int consecutiveDeadLetters,
            Instant suspendedUntil,
            Instant lastSuccessAt,
            Instant lastFailureAt,
            boolean suspended,
            long backlog,
            long deadLetters) {
        static RuntimeStateResponse from(IntegrationOperationsService.RuntimeSnapshot snapshot, Instant now) {
            ConnectorRuntimeState state = snapshot.state();
            return new RuntimeStateResponse(
                    state.connectorKey().value(),
                    state.consecutiveDeadLetters(),
                    state.suspendedUntil(),
                    state.lastSuccessAt(),
                    state.lastFailureAt(),
                    state.suspendedAt(now),
                    snapshot.backlog(),
                    snapshot.deadLetters());
        }
    }

    record ReasonRequest(String reason) {}
}
