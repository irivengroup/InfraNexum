package io.infranexum.identity.access.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.domain.AssignmentActorType;
import io.infranexum.identity.access.domain.AuthorizationScope;
import java.time.Instant;

/** Policy hook executed before a role assignment is persisted. */
@FunctionalInterface
public interface RoleAssignmentPolicyGuard {
    void check(DomainIdentifier roleId, AssignmentActorType actorType, DomainIdentifier actorId, AuthorizationScope scope, Instant effectiveAt);

    static RoleAssignmentPolicyGuard allowAll() { return (roleId, actorType, actorId, scope, effectiveAt) -> {}; }
}
