package io.infranexum.integrations;

import java.time.Instant;
import java.util.Objects;

/** Per-connector failure/suspension state used to bound repeated failing deliveries. */
public record ConnectorRuntimeState(ConnectorKey connectorKey, int consecutiveDeadLetters, Instant suspendedUntil, Instant lastSuccessAt, Instant lastFailureAt) {
    public ConnectorRuntimeState {
        Objects.requireNonNull(connectorKey, "connectorKey");
        if (consecutiveDeadLetters < 0) throw new IllegalArgumentException("consecutiveDeadLetters must be non-negative");
    }
    public boolean suspendedAt(Instant now) { return suspendedUntil != null && suspendedUntil.isAfter(Objects.requireNonNull(now, "now")); }
}
