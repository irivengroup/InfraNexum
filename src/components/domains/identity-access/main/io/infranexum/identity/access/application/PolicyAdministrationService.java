package io.infranexum.identity.access.application;

import io.infranexum.core.audit.AuditEntry;
import io.infranexum.core.audit.AuditJournal;
import io.infranexum.core.audit.AuditScope;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.core.events.TransactionalWork;
import io.infranexum.identity.access.domain.AccessPolicy;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.IdentityAccessException;
import io.infranexum.identity.access.domain.IdentityUserStatus;
import io.infranexum.identity.access.domain.PolicyRule;
import io.infranexum.identity.access.domain.PolicyRuleDefinition;
import io.infranexum.identity.access.domain.PolicyState;
import io.infranexum.identity.access.domain.SeparationOfDutyConstraint;
import io.infranexum.identity.access.domain.SeparationOfDutyDefinition;
import io.infranexum.identity.access.ports.AccessPolicyRepository;
import io.infranexum.identity.access.ports.IdentityAccessFeaturePolicy;
import io.infranexum.identity.access.ports.IdentityAccessRepository;
import io.infranexum.identity.access.ports.OrganizationScopeReferencePort;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** PAP use cases for immutable ABAC policy versions and static separation-of-duty rules. */
public final class PolicyAdministrationService {
    private final AccessPolicyRepository repository;
    private final IdentityAccessRepository identityRepository;
    private final IdentityAccessFeaturePolicy features;
    private final OrganizationScopeReferencePort organizationScopes;
    private final TransactionalEventStore transactions;
    private final AuditJournal audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public PolicyAdministrationService(
            AccessPolicyRepository repository,
            IdentityAccessRepository identityRepository,
            IdentityAccessFeaturePolicy features,
            OrganizationScopeReferencePort organizationScopes,
            TransactionalEventStore transactions,
            AuditJournal audit,
            UuidV7Generator ids,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.identityRepository = Objects.requireNonNull(identityRepository, "identityRepository");
        this.features = Objects.requireNonNull(features, "features");
        this.organizationScopes = Objects.requireNonNull(organizationScopes, "organizationScopes");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<AccessPolicy> listPolicies(DomainIdentifier organizationId, int offset, int limit) {
        requireAvailable();
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("pagination must use offset >= 0 and limit between 1 and 200");
        return repository.listPolicies(organizationId, offset, limit);
    }

    public AccessPolicy getPolicy(DomainIdentifier policyId) {
        requireAvailable();
        return repository.findPolicy(Objects.requireNonNull(policyId, "policyId"))
                .orElseThrow(() -> new IdentityAccessException("IAM_POLICY_NOT_FOUND", "access policy not found"));
    }

    public AccessPolicy createPolicy(
            DomainIdentifier organizationId,
            String code,
            String purpose,
            int priority,
            AuthorizationScope scope,
            Instant effectiveFrom,
            List<PolicyRuleDefinition> ruleDefinitions,
            List<SeparationOfDutyDefinition> sodDefinitions,
            IdentityAccessCommandContext context) {
        requireAvailable();
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(context, "context");
        validateScope(organizationId, scope);
        Instant now = clock.instant();
        Instant effective = effectiveFrom == null ? now : effectiveFrom;
        if (effective.isBefore(now)) throw new IllegalArgumentException("policy effectiveFrom cannot precede creation time");
        if (identityRepository.findUser(context.actorId())
                .filter(user -> user.status() == IdentityUserStatus.ACTIVE)
                .isEmpty()) {
            throw new IdentityAccessException("IAM_POLICY_OWNER_NOT_FOUND", "policy owner must be an active IAM identity");
        }
        String normalizedCode = AccessPolicy.normalizeCode(code);
        if (normalizedCode.startsWith("system.")) {
            throw new IdentityAccessException("IAM_SYSTEM_POLICY_PROTECTED", "system policy codes are reserved");
        }
        List<PolicyRuleDefinition> definitions = List.copyOf(Objects.requireNonNull(ruleDefinitions, "ruleDefinitions"));
        if (definitions.isEmpty() || definitions.size() > 256) throw new IllegalArgumentException("policy requires between 1 and 256 rules");
        List<SeparationOfDutyDefinition> sod = List.copyOf(Objects.requireNonNull(sodDefinitions, "sodDefinitions"));
        if (sod.size() > 128) throw new IllegalArgumentException("policy has too many SoD constraints");
        DomainIdentifier policyId = ids.next();
        long version = repository.nextVersion(organizationId, normalizedCode);
        List<PolicyRule> rules = new ArrayList<>(definitions.size());
        for (int index = 0; index < definitions.size(); index++) {
            PolicyRuleDefinition definition = definitions.get(index);
            rules.add(new PolicyRule(ids.next(), index + 1, definition.effect(), definition.action(), definition.resourceType(),
                    definition.conditions(), definition.obligations(), definition.advice()));
        }
        AccessPolicy policy = new AccessPolicy(policyId, organizationId, normalizedCode, version, context.actorId(), purpose,
                priority, scope, PolicyState.DRAFT, effective, null, null, null, null, null, now, now, rules);
        List<SeparationOfDutyConstraint> constraints = sod.stream().map(definition -> new SeparationOfDutyConstraint(
                ids.next(), policyId, organizationId, definition.firstRoleId(), definition.secondRoleId(),
                definition.reason(), now, context.actorId())).toList();
        validateSodRoles(organizationId, constraints);
        return execute(tx -> {
            repository.insertPolicy(policy);
            constraints.forEach(repository::insertSeparationOfDutyConstraint);
            auditMutation(context, "iam.policy.create", policy, Map.of(
                    "policy_code", policy.code(),
                    "policy_version", Long.toString(policy.version()),
                    "rules", Integer.toString(policy.rules().size()),
                    "sod_constraints", Integer.toString(constraints.size())));
            return policy;
        });
    }

    public AccessPolicy validatePolicy(DomainIdentifier policyId, IdentityAccessCommandContext context) {
        return transition(policyId, context, "iam.policy.validate", policy -> policy.validatePolicy(clock.instant()));
    }

    public AccessPolicy approvePolicy(DomainIdentifier policyId, IdentityAccessCommandContext context) {
        return transition(policyId, context, "iam.policy.approve", policy -> policy.approve(context.actorId(), clock.instant()));
    }

    public AccessPolicy activatePolicy(DomainIdentifier policyId, IdentityAccessCommandContext context) {
        requireAvailable();
        Objects.requireNonNull(context, "context");
        AccessPolicy current = getPolicy(policyId);
        Instant now = clock.instant();
        AccessPolicy activated = current.activate(now);
        return execute(tx -> {
            repository.deprecateActiveVersions(current.organizationId(), current.code(), current.id(), now);
            repository.updatePolicyState(activated);
            auditMutation(context, "iam.policy.activate", activated, Map.of(
                    "policy_code", activated.code(), "policy_version", Long.toString(activated.version())));
            return activated;
        });
    }

    private AccessPolicy transition(DomainIdentifier policyId, IdentityAccessCommandContext context, String action,
            java.util.function.UnaryOperator<AccessPolicy> transition) {
        requireAvailable();
        Objects.requireNonNull(context, "context");
        AccessPolicy changed = transition.apply(getPolicy(policyId));
        return execute(tx -> {
            repository.updatePolicyState(changed);
            auditMutation(context, action, changed, Map.of(
                    "policy_code", changed.code(), "policy_version", Long.toString(changed.version())));
            return changed;
        });
    }

    private void validateScope(DomainIdentifier organizationId, AuthorizationScope scope) {
        if (!Objects.equals(organizationId, scope.organizationId())) {
            throw new IdentityAccessException("IAM_POLICY_SCOPE_MISMATCH", "policy organization and authorization scope differ");
        }
        if (organizationId == null) return;
        if (!organizationScopes.organizationExists(organizationId)) {
            throw new IdentityAccessException("IAM_ORGANIZATION_NOT_FOUND", "referenced organization does not exist");
        }
        if (scope.subdivisionId() != null && !organizationScopes.subdivisionExists(organizationId, scope.subdivisionId())) {
            throw new IdentityAccessException("IAM_SUBDIVISION_NOT_FOUND", "referenced subdivision does not exist in organization");
        }
    }

    private void validateSodRoles(DomainIdentifier organizationId, List<SeparationOfDutyConstraint> constraints) {
        for (SeparationOfDutyConstraint constraint : constraints) {
            var first = identityRepository.findRole(constraint.firstRoleId())
                    .orElseThrow(() -> new IdentityAccessException("IAM_ROLE_NOT_FOUND", "SoD role not found"));
            var second = identityRepository.findRole(constraint.secondRoleId())
                    .orElseThrow(() -> new IdentityAccessException("IAM_ROLE_NOT_FOUND", "SoD role not found"));
            if (first.deleted() || !first.active() || second.deleted() || !second.active()) {
                throw new IdentityAccessException("IAM_SOD_ROLE_INACTIVE", "SoD roles must be active");
            }
            if (!Objects.equals(first.organizationId(), organizationId) || !Objects.equals(second.organizationId(), organizationId)) {
                throw new IdentityAccessException("IAM_SOD_SCOPE_MISMATCH", "SoD roles must belong to the policy scope");
            }
        }
    }

    private void requireAvailable() {
        if (!features.supportsAdvancedAuthorization()) {
            throw new IdentityAccessException("IAM_ADVANCED_AUTHORIZATION_UNAVAILABLE", "advanced authorization is unavailable for the active installation profile");
        }
    }

    private void auditMutation(IdentityAccessCommandContext context, String action, AccessPolicy policy, Map<String, String> metadata) {
        AuditScope scope = policy.organizationId() == null ? AuditScope.platform() : AuditScope.organization(policy.organizationId().toString());
        audit.append(new AuditEntry(ids.next(), scope, context.actorId().toString(), "USER", action, "policy", policy.id().toString(),
                "ALLOW", clock.instant(), context.correlationId(), "SUCCESS", context.origin(), context.reason(), null, null,
                metadata, "ELEVATED"));
    }

    private <T> T execute(TransactionalWork<T> work) {
        try {
            return transactions.execute(work).value();
        } catch (TransactionExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }
}
