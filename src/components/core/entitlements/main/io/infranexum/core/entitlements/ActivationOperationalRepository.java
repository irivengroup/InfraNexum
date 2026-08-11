package io.infranexum.core.entitlements;

import java.time.Instant;
import java.util.Optional;

/** Durable activation state boundary implemented by database adapters. */
public interface ActivationOperationalRepository {
    Optional<InstallationIdentity> installationIdentity();
    AcceptedSequence acceptedSequence(InstallationIdentity identity);
    Optional<IntegrityProof> databaseProof(InstallationIdentity identity);
    void accept(InstallationIdentity identity, ActivationManifest manifest,
                ActivationVerificationResult result, IntegrityProof databaseProof, Instant acceptedAt);
}
