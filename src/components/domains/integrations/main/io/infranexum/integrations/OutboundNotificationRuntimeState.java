package io.infranexum.integrations;

import java.time.Instant;
import java.util.Objects;

/** Failure/suspension state for one outbound notification endpoint. */
public record OutboundNotificationRuntimeState(
        ConnectorKey endpointKey,
        int consecutiveDeadLetters,
        Instant suspendedUntil,
        Instant lastSuccessAt,
        Instant lastFailureAt) {
    public OutboundNotificationRuntimeState {
        Objects.requireNonNull(endpointKey, "endpointKey");
        if (consecutiveDeadLetters < 0) throw new IllegalArgumentException("consecutiveDeadLetters must be non-negative");
    }
    public boolean suspendedAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return suspendedUntil != null && suspendedUntil.isAfter(now);
    }
}
