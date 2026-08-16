package io.infranexum.integrations;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.RetryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Persistence port for durable connector inbox, DLQ, replay and suspension state. */
public interface ConnectorInboxRepository {
    WebhookAdmissionOutcome admit(WebhookAdmission admission);
    List<ConnectorDelivery> claimBatch(String workerId, int limit, Instant now, Duration leaseDuration);
    void markProcessed(DomainIdentifier deliveryId, String workerId, Instant processedAt);
    ConnectorDeliveryStatus markFailed(DomainIdentifier deliveryId, String workerId, Instant failedAt, RetryPolicy retryPolicy, Throwable failure, int suspendAfterDeadLetters, Duration suspensionDuration);
    List<ConnectorDelivery> listDeadLetters(ConnectorKey connectorKey, int offset, int limit);
    ConnectorDelivery replay(DomainIdentifier deliveryId, Instant replayedAt);
    ConnectorRuntimeState runtimeState(ConnectorKey connectorKey);
    ConnectorRuntimeState resume(ConnectorKey connectorKey, Instant resumedAt);
    long backlogSize(ConnectorKey connectorKey, Instant now);
    long deadLetterCount(ConnectorKey connectorKey);
}
