package io.infranexum.core.entitlements;

import java.time.Instant;
import java.util.Objects;

/** Result returned after a durable, verified activation import. */
public record ActivationImportResult(ActivationUsageState state, long sequence, Instant graceUntil) {
    public ActivationImportResult {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(graceUntil, "graceUntil");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
    }
}
