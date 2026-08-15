package io.infranexum.identity.access.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.domain.AccessPolicy;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.SeparationOfDutyConstraint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** PRP persistence port for immutable policy versions and static SoD constraints. */
public interface AccessPolicyRepository {
    long nextVersion(DomainIdentifier organizationId, String code);
    void insertPolicy(AccessPolicy policy);
    void updatePolicyState(AccessPolicy policy);
    Optional<AccessPolicy> findPolicy(DomainIdentifier policyId);
    List<AccessPolicy> listPolicies(DomainIdentifier organizationId, int offset, int limit);
    List<AccessPolicy> activePolicies(AuthorizationScope scope, Instant at);
    void deprecateActiveVersions(DomainIdentifier organizationId, String code, DomainIdentifier exceptPolicyId, Instant at);

    void insertSeparationOfDutyConstraint(SeparationOfDutyConstraint constraint);
    List<SeparationOfDutyConstraint> activeSeparationOfDutyConstraints(DomainIdentifier organizationId, DomainIdentifier roleId, Instant at);
}
