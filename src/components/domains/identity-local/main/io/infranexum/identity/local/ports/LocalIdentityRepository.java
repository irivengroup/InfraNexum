package io.infranexum.identity.local.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.local.domain.LocalAccount;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface LocalIdentityRepository {
    boolean hasAnyAccount();
    Optional<LocalAccount> findByUsername(String canonicalUsername);
    Optional<LocalAccount> findById(DomainIdentifier accountId);
    void insert(LocalAccount account);
    LocalAccount recordFailedAuthentication(
            DomainIdentifier accountId, long expectedSecurityEpoch,
            int lockThreshold, Duration lockDuration, Instant now);
    LocalAccount recordSuccessfulAuthentication(
            DomainIdentifier accountId, long expectedSecurityEpoch, String replacementHash, Instant now);
    LocalAccount changePassword(
            DomainIdentifier accountId, long expectedSecurityEpoch,
            String passwordHash, boolean mustChange, Instant now);
}
