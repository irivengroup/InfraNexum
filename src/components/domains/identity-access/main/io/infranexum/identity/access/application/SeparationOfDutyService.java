package io.infranexum.identity.access.application;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.domain.AssignmentActorType;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.IdentityAccessException;
import io.infranexum.identity.access.domain.SeparationOfDutyConstraint;
import io.infranexum.identity.access.ports.AccessPolicyRepository;
import io.infranexum.identity.access.ports.IdentityAccessFeaturePolicy;
import io.infranexum.identity.access.ports.IdentityAccessRepository;
import io.infranexum.identity.access.ports.RoleAssignmentPolicyGuard;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Static SoD guard evaluated before any Pro/Enterprise role assignment is persisted. */
public final class SeparationOfDutyService implements RoleAssignmentPolicyGuard {
    private final AccessPolicyRepository policies;
    private final IdentityAccessRepository identities;
    private final IdentityAccessFeaturePolicy features;

    public SeparationOfDutyService(
            AccessPolicyRepository policies,
            IdentityAccessRepository identities,
            IdentityAccessFeaturePolicy features) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.features = Objects.requireNonNull(features, "features");
    }

    @Override
    public void check(DomainIdentifier roleId, AssignmentActorType actorType, DomainIdentifier actorId,
            AuthorizationScope scope, Instant effectiveAt) {
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        if (!features.supportsAdvancedAuthorization()) return;
        var constraints = policies.activeSeparationOfDutyConstraints(scope.organizationId(), roleId, effectiveAt);
        if (constraints.isEmpty()) return;
        if (actorType == AssignmentActorType.USER) {
            checkUser(actorId, roleId, scope, effectiveAt, constraints);
            return;
        }
        Set<DomainIdentifier> members = identities.effectiveGroupMembers(actorId);
        for (DomainIdentifier member : members) checkUser(member, roleId, scope, effectiveAt, constraints);
    }

    private void checkUser(DomainIdentifier userId, DomainIdentifier targetRoleId, AuthorizationScope scope,
            Instant effectiveAt, java.util.List<SeparationOfDutyConstraint> constraints) {
        for (SeparationOfDutyConstraint constraint : constraints) {
            DomainIdentifier conflicting = constraint.conflictingRole(targetRoleId);
            if (conflicting != null && identities.hasEffectiveRole(userId, conflicting, scope, effectiveAt)) {
                throw new IdentityAccessException("IAM_SOD_CONFLICT",
                        "role assignment conflicts with an active separation-of-duty policy");
            }
        }
    }
}
