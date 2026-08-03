package io.infranexum.core.entitlements;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;

/** Offline revocation view distributed with trusted entitlement metadata. */
public interface RevocationRegistry {
    boolean isKeyRevoked(String keyId, Instant at);

    boolean isActivationRevoked(DomainIdentifier activationId, Instant at);
}
