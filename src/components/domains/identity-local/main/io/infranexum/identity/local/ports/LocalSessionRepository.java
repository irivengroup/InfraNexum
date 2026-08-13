package io.infranexum.identity.local.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.local.domain.LocalSession;
import java.time.Instant;
import java.util.Optional;

public interface LocalSessionRepository {
    void insert(LocalSession session);
    Optional<LocalSession> findByTokenHash(String tokenHash);
    void touch(DomainIdentifier sessionId, Instant lastSeenAt, Instant idleExpiresAt);
    void revoke(DomainIdentifier sessionId, Instant revokedAt);
    void revokeAllForAccount(DomainIdentifier accountId, Instant revokedAt);
}
