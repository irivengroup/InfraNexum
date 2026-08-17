package io.infranexum.integrations;

import java.util.Objects;

/** Result of idempotent durable notification admission. */
public record OutboundNotificationAdmissionOutcome(OutboundNotificationDelivery delivery, boolean duplicate) {
    public OutboundNotificationAdmissionOutcome {
        Objects.requireNonNull(delivery, "delivery");
    }
}
