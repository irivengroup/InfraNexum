package io.infranexum.identity.access.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;

/** Direct membership edge owned by an IAM group. */
public record GroupMember(AssignmentActorType memberType, DomainIdentifier memberId) {
    public GroupMember {
        Objects.requireNonNull(memberType, "memberType");
        Objects.requireNonNull(memberId, "memberId");
    }
}
