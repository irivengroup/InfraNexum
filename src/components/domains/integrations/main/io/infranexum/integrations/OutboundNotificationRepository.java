package io.infranexum.integrations;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.RetryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Durable outbox/DLQ port for outbound operational notifications. */
public interface OutboundNotificationRepository {
    OutboundNotificationAdmissionOutcome admit(OutboundNotificationAdmission admission);
    List<OutboundNotificationDelivery> claimBatch(String workerId, int limit, Instant now, Duration leaseDuration);
    void markDelivered(DomainIdentifier deliveryId, String workerId, Instant deliveredAt);
    OutboundNotificationStatus markFailed(DomainIdentifier deliveryId, String workerId, Instant failedAt, RetryPolicy retryPolicy,
            Throwable failure, boolean retryable, int suspendAfterDeadLetters, Duration suspensionDuration);
    List<OutboundNotificationDelivery> listDeadLetters(ConnectorKey endpointKey, int offset, int limit);
    OutboundNotificationDelivery replay(DomainIdentifier deliveryId, Instant replayedAt);
    OutboundNotificationRuntimeState runtimeState(ConnectorKey endpointKey);
    OutboundNotificationRuntimeState resume(ConnectorKey endpointKey, Instant resumedAt);
    long backlogSize(ConnectorKey endpointKey, Instant now);
    long deadLetterCount(ConnectorKey endpointKey);
}
