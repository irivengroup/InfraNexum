package io.infranexum.identity.access.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;

/** Static SoD conflict between two roles, activated through its owning policy version. */
public record SeparationOfDutyConstraint(
        DomainIdentifier id,
        DomainIdentifier policyId,
        DomainIdentifier organizationId,
        DomainIdentifier firstRoleId,
        DomainIdentifier secondRoleId,
        String reason,
        Instant createdAt,
        DomainIdentifier createdBy) {
    public SeparationOfDutyConstraint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(firstRoleId, "firstRoleId");
        Objects.requireNonNull(secondRoleId, "secondRoleId");
        if (firstRoleId.equals(secondRoleId)) throw new IllegalArgumentException("SoD constraint requires two distinct roles");
        if (firstRoleId.compareTo(secondRoleId) > 0) { DomainIdentifier swap = firstRoleId; firstRoleId = secondRoleId; secondRoleId = swap; }
        reason = PolicyCondition.bounded(reason, "reason", 500);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(createdBy, "createdBy");
    }

    public DomainIdentifier conflictingRole(DomainIdentifier roleId) {
        Objects.requireNonNull(roleId, "roleId");
        if (firstRoleId.equals(roleId)) return secondRoleId;
        if (secondRoleId.equals(roleId)) return firstRoleId;
        return null;
    }
}
