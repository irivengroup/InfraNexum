package io.infranexum.core.entitlements;

import java.util.Objects;

/** Same trusted-time evidence held in two independent persistence locations. */
public record IntegrityProofPair(IntegrityProof databaseProof, IntegrityProof independentProof) {
    public IntegrityProofPair {
        Objects.requireNonNull(databaseProof, "databaseProof");
        Objects.requireNonNull(independentProof, "independentProof");
    }
}
