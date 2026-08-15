package io.infranexum.identity.access;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.domain.AccessPolicy;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.PolicyState;
import io.infranexum.identity.access.domain.SeparationOfDutyConstraint;
import io.infranexum.identity.access.ports.AccessPolicyRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** In-memory PRP used by behavioral policy tests. */
final class AccessPolicyTestRepository implements AccessPolicyRepository {
    final Map<DomainIdentifier, AccessPolicy> policies = new LinkedHashMap<>();
    final List<SeparationOfDutyConstraint> constraints = new ArrayList<>();
    boolean failActivePolicies;

    @Override
    public long nextVersion(DomainIdentifier organizationId, String code) {
        return policies.values().stream()
                .filter(policy -> Objects.equals(policy.organizationId(), organizationId) && policy.code().equals(code))
                .mapToLong(AccessPolicy::version).max().orElse(0L) + 1L;
    }

    @Override public void insertPolicy(AccessPolicy policy) { policies.put(policy.id(), policy); }
    @Override public void updatePolicyState(AccessPolicy policy) { policies.put(policy.id(), policy); }
    @Override public Optional<AccessPolicy> findPolicy(DomainIdentifier policyId) { return Optional.ofNullable(policies.get(policyId)); }

    @Override
    public List<AccessPolicy> listPolicies(DomainIdentifier organizationId, int offset, int limit) {
        return policies.values().stream().filter(policy -> Objects.equals(policy.organizationId(), organizationId))
                .sorted(Comparator.comparing(AccessPolicy::code).thenComparingLong(AccessPolicy::version))
                .skip(offset).limit(limit).toList();
    }

    @Override
    public List<AccessPolicy> activePolicies(AuthorizationScope scope, Instant at) {
        if (failActivePolicies) throw new IllegalStateException("simulated PRP outage");
        return policies.values().stream()
                .filter(policy -> policy.effectiveAt(at))
                .filter(policy -> policy.scope().covers(scope))
                .toList();
    }

    @Override
    public void deprecateActiveVersions(DomainIdentifier organizationId, String code, DomainIdentifier exceptPolicyId, Instant at) {
        policies.replaceAll((id, policy) -> Objects.equals(policy.organizationId(), organizationId)
                        && policy.code().equals(code) && policy.state() == PolicyState.ACTIVE && !id.equals(exceptPolicyId)
                ? policy.deprecate(at) : policy);
    }

    @Override public void insertSeparationOfDutyConstraint(SeparationOfDutyConstraint constraint) { constraints.add(constraint); }

    @Override
    public List<SeparationOfDutyConstraint> activeSeparationOfDutyConstraints(
            DomainIdentifier organizationId, DomainIdentifier roleId, Instant at) {
        return constraints.stream()
                .filter(constraint -> Objects.equals(constraint.organizationId(), organizationId))
                .filter(constraint -> constraint.firstRoleId().equals(roleId) || constraint.secondRoleId().equals(roleId))
                .filter(constraint -> policies.get(constraint.policyId()) != null && policies.get(constraint.policyId()).effectiveAt(at))
                .toList();
    }
}
