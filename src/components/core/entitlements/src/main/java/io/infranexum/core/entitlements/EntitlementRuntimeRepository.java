package io.infranexum.core.entitlements;

import java.time.Instant;
import java.util.Optional;

/** Durable runtime boundary used to enforce entitlement decisions before opening network ports. */
public interface EntitlementRuntimeRepository extends ActivationOperationalRepository {
    Optional<EntitlementStateRecord> entitlementState(InstallationIdentity identity);

    Optional<String> acceptedManifestDocument(InstallationIdentity identity);

    void initializeLite(InstallationIdentity identity, IntegrityProof databaseProof, Instant initializedAt);

    void updateRuntimeState(
            InstallationIdentity identity,
            EntitlementRuntimeStatus status,
            IntegrityProof databaseProof,
            Instant updatedAt);
}
