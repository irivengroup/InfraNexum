package io.infranexum.identity.access.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;

/** Temporal role assignment to a user or group. */
public record RoleAssignment(
        DomainIdentifier id, DomainIdentifier roleId, AssignmentActorType actorType, DomainIdentifier actorId,
        AuthorizationScope scope, Instant effectiveFrom, Instant effectiveTo, Instant revokedAt, DomainIdentifier revokedBy) {
    public RoleAssignment {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(roleId, "roleId"); Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(actorId, "actorId"); Objects.requireNonNull(scope, "scope"); Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
        if ((revokedAt == null) != (revokedBy == null)) throw new IllegalArgumentException("revocation timestamp and actor must be supplied together");
    }

    public boolean effectiveAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return revokedAt == null && !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo));
    }
}
