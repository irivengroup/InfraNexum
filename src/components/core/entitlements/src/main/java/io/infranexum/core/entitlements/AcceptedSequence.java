package io.infranexum.core.entitlements;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;

/** Highest activation sequence durably accepted for an installation. */
public record AcceptedSequence(long value, DomainIdentifier activationId) {
    public AcceptedSequence {
        if (value < 0) {
            throw new IllegalArgumentException("accepted sequence must be non-negative");
        }
        if (value == 0 && activationId != null) {
            throw new IllegalArgumentException("sequence zero cannot have an activation ID");
        }
        if (value > 0) {
            Objects.requireNonNull(activationId, "activationId");
        }
    }

    public static AcceptedSequence none() {
        return new AcceptedSequence(0, null);
    }

    public boolean accepts(long candidate, DomainIdentifier candidateId) {
        Objects.requireNonNull(candidateId, "candidateId");
        return candidate > value || (candidate == value && candidateId.equals(activationId));
    }
}
