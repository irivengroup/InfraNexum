package io.infranexum.identity.access.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;

/** Temporal user membership granting visibility into an organization or subdivision. */
public record UserMembership(
        DomainIdentifier id, DomainIdentifier userId, DomainIdentifier organizationId, DomainIdentifier subdivisionId,
        Instant effectiveFrom, Instant effectiveTo, Instant revokedAt) {
    public UserMembership {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(userId, "userId"); Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) throw new IllegalArgumentException("effectiveTo must not precede effectiveFrom");
    }
    public boolean effectiveAt(Instant at) { return revokedAt == null && !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo)); }
}
