package io.infranexum.core.entitlements;

import java.util.Optional;

/** Independent durable temporal evidence boundary, physically separate from the database. */
public interface IndependentIntegrityProofStore {
    Optional<IntegrityProof> load(InstallationIdentity identity);
    void store(IntegrityProof proof);
    void delete(InstallationIdentity identity);
}
