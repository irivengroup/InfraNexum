package io.infranexum.identity.access.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;

/** Static SoD role pair attached to one versioned access policy. */
public record SeparationOfDutyDefinition(DomainIdentifier firstRoleId, DomainIdentifier secondRoleId, String reason) {
    public SeparationOfDutyDefinition {
        Objects.requireNonNull(firstRoleId, "firstRoleId");
        Objects.requireNonNull(secondRoleId, "secondRoleId");
        if (firstRoleId.equals(secondRoleId)) throw new IllegalArgumentException("SoD definition requires two distinct roles");
        if (firstRoleId.compareTo(secondRoleId) > 0) { DomainIdentifier swap = firstRoleId; firstRoleId = secondRoleId; secondRoleId = swap; }
        reason = PolicyCondition.bounded(reason, "reason", 500);
    }
}
