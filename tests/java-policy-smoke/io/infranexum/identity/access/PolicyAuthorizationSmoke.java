package io.infranexum.identity.access;

import io.infranexum.core.audit.InMemoryAppendOnlyAuditJournal;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.InMemoryEventStore;
import io.infranexum.identity.access.application.IdentityAccessCommandContext;
import io.infranexum.identity.access.application.PolicyAdministrationService;
import io.infranexum.identity.access.application.PolicyDecisionService;
import io.infranexum.identity.access.application.SeparationOfDutyService;
import io.infranexum.identity.access.domain.AccessPolicy;
import io.infranexum.identity.access.domain.AssignmentActorType;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.IdentityAccessException;
import io.infranexum.identity.access.domain.IdentityUser;
import io.infranexum.identity.access.domain.PolicyAttributeBag;
import io.infranexum.identity.access.domain.PolicyAttributeSource;
import io.infranexum.identity.access.domain.PolicyCondition;
import io.infranexum.identity.access.domain.PolicyDecision;
import io.infranexum.identity.access.domain.PolicyEffect;
import io.infranexum.identity.access.domain.PolicyEvaluationRequest;
import io.infranexum.identity.access.domain.PolicyObligation;
import io.infranexum.identity.access.domain.PolicyOperator;
import io.infranexum.identity.access.domain.PolicyRule;
import io.infranexum.identity.access.domain.PolicyRuleDefinition;
import io.infranexum.identity.access.domain.PolicyState;
import io.infranexum.identity.access.domain.Role;
import io.infranexum.identity.access.domain.RoleAssignment;
import io.infranexum.identity.access.domain.ScopeKind;
import io.infranexum.identity.access.domain.SeparationOfDutyConstraint;
import io.infranexum.identity.access.domain.SeparationOfDutyDefinition;
import io.infranexum.identity.access.domain.UserMembership;
import io.infranexum.identity.access.ports.IdentityAccessFeaturePolicy;
import io.infranexum.identity.access.ports.OrganizationScopeReferencePort;
import io.infranexum.identity.access.ports.PolicyDecisionObserver;
import io.infranexum.identity.access.ports.PolicyInformationPort;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Executable E04 smoke proving fail-closed PAP/PDP and static SoD without external dependencies. */
public final class PolicyAuthorizationSmoke {
    private static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");
    private static final DomainIdentifier ORG = id(1);
    private static final DomainIdentifier OWNER = id(2);
    private static final DomainIdentifier APPROVER = id(3);
    private static final DomainIdentifier CORRELATION = id(4);

    private PolicyAuthorizationSmoke() {}

    public static void main(String[] args) {
        IdentityAccessTestRepository identities = new IdentityAccessTestRepository();
        AccessPolicyTestRepository policies = new AccessPolicyTestRepository();
        identities.insertUser(activeUser(OWNER, "policy.owner"));
        identities.insertUser(activeUser(APPROVER, "policy.approver"));
        var feature = new Features(true);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var ids = new UuidV7Generator(clock, new SecureRandom(new byte[] {7, 0, 0, 4}));
        var audit = new InMemoryAppendOnlyAuditJournal();
        var scopes = new OrganizationScopeReferencePort() {
            @Override public boolean organizationExists(DomainIdentifier organizationId) { return ORG.equals(organizationId); }
            @Override public boolean subdivisionExists(DomainIdentifier organizationId, DomainIdentifier subdivisionId) { return false; }
        };
        var pap = new PolicyAdministrationService(policies, identities, feature, scopes, new InMemoryEventStore(), audit, ids, clock);
        var owner = new IdentityAccessCommandContext(OWNER, CORRELATION, "create policy", "SMOKE");
        var approver = new IdentityAccessCommandContext(APPROVER, CORRELATION, "approve policy", "SMOKE");
        var rule = new PolicyRuleDefinition(PolicyEffect.PERMIT, "asset.read", "asset",
                List.of(new PolicyCondition(PolicyAttributeSource.RBAC, "permitted", PolicyOperator.EQUALS, "true")),
                Set.of(PolicyObligation.REQUIRE_JUSTIFICATION), "policy advice");

        AccessPolicy v1 = pap.createPolicy(ORG, "asset.access", "Asset access policy", 100,
                AuthorizationScope.organization(ORG), NOW, List.of(rule), List.of(), owner);
        assert v1.version() == 1L;
        pap.validatePolicy(v1.id(), owner);
        expectCode("IAM_POLICY_SELF_APPROVAL_FORBIDDEN", () -> pap.approvePolicy(v1.id(), owner));
        pap.approvePolicy(v1.id(), approver);
        assert pap.activatePolicy(v1.id(), approver).state() == PolicyState.ACTIVE;

        AccessPolicy v2 = pap.createPolicy(ORG, "asset.access", "Asset access policy v2", 200,
                AuthorizationScope.organization(ORG), NOW, List.of(rule), List.of(), owner);
        pap.validatePolicy(v2.id(), owner);
        pap.approvePolicy(v2.id(), approver);
        pap.activatePolicy(v2.id(), approver);
        assert policies.findPolicy(v1.id()).orElseThrow().state() == PolicyState.DEPRECATED;
        assert pap.activatePolicy(v1.id(), approver).state() == PolicyState.ACTIVE;
        assert policies.findPolicy(v2.id()).orElseThrow().state() == PolicyState.DEPRECATED;

        policies.policies.clear();
        AccessPolicy bridge = activePolicy(ids, "system.rbac-bridge", 0, PolicyEffect.PERMIT,
                new PolicyCondition(PolicyAttributeSource.RBAC, "permitted", PolicyOperator.EQUALS, "true"), Set.of());
        policies.insertPolicy(bridge);
        PolicyInformationPort pip = (request, at) -> PolicyAttributeBag.builder()
                .put(PolicyAttributeSource.RBAC, "permitted", Boolean.toString(request.rbacPermitted()))
                .put(PolicyAttributeSource.SUBJECT, "department", request.environment().getOrDefault("department", "ops"))
                .build();
        PolicyDecisionObserver observer = (result, elapsed, cacheHit) -> {};
        var pdp = new PolicyDecisionService(policies, pip, feature, observer, audit, ids, clock);
        var permit = request(Map.of("department", "ops"), true);
        assert pdp.decide(permit, CORRELATION, "SMOKE").decision() == PolicyDecision.PERMIT;
        assert pdp.decide(request(Map.of("department", "ops"), false), CORRELATION, "SMOKE").decision() == PolicyDecision.DENY;

        AccessPolicy deny = activePolicy(ids, "asset.block", 500, PolicyEffect.DENY,
                new PolicyCondition(PolicyAttributeSource.SUBJECT, "department", PolicyOperator.EQUALS, "blocked"), Set.of());
        policies.insertPolicy(deny);
        assert pdp.decide(request(Map.of("department", "blocked"), true), CORRELATION, "SMOKE").decision() == PolicyDecision.DENY;
        var pipDown = new PolicyDecisionService(policies, (request, at) -> { throw new IllegalStateException("PIP down"); },
                feature, observer, audit, ids, clock);
        assert pipDown.decide(permit, CORRELATION, "SMOKE").decision() == PolicyDecision.INDETERMINATE;
        policies.failActivePolicies = true;
        assert pdp.decide(permit, CORRELATION, "SMOKE").decision() == PolicyDecision.INDETERMINATE;
        policies.failActivePolicies = false;

        proveCachedDecisionPerformance(pdp, permit);
        proveStaticSod(identities, policies, feature, ids);
        System.out.println("java-policy-smoke: PASS");
    }


    /** Proves the draft.21 local cached-decision P95 target without external I/O. */
    private static void proveCachedDecisionPerformance(PolicyDecisionService pdp, PolicyEvaluationRequest request) {
        for (int index = 0; index < 200; index++) {
            assert pdp.decide(request, CORRELATION, "SMOKE").permitted();
        }
        long[] samples = new long[2_000];
        for (int index = 0; index < samples.length; index++) {
            long started = System.nanoTime();
            assert pdp.decide(request, CORRELATION, "SMOKE").permitted();
            samples[index] = System.nanoTime() - started;
        }
        java.util.Arrays.sort(samples);
        long p95 = samples[(int) Math.ceil(samples.length * 0.95) - 1];
        assert p95 < 50_000_000L : "cached PDP P95 exceeded 50ms: " + p95 + "ns";
        System.out.println("java-policy-smoke cached-pdp-p95-ns=" + p95);
    }

    private static void proveStaticSod(IdentityAccessTestRepository identities, AccessPolicyTestRepository policies,
            IdentityAccessFeaturePolicy feature, UuidV7Generator ids) {
        DomainIdentifier requester = id(20);
        DomainIdentifier approver = id(21);
        DomainIdentifier user = id(22);
        identities.insertUser(activeUser(user, "sod.user"));
        identities.insertRole(new Role(requester, ORG, "ops.requester", "Requester", ScopeKind.ORGANIZATION, false, true, NOW, NOW, null), Set.of());
        identities.insertRole(new Role(approver, ORG, "ops.approver", "Approver", ScopeKind.ORGANIZATION, false, true, NOW, NOW, null), Set.of());
        identities.insertMembership(new UserMembership(id(23), user, ORG, null, NOW, null, null));
        identities.insertAssignment(new RoleAssignment(id(24), requester, AssignmentActorType.USER, user,
                AuthorizationScope.organization(ORG), NOW, null, null, null));
        AccessPolicy sodPolicy = activePolicy(ids, "sod.four-eyes", 100, PolicyEffect.PERMIT,
                new PolicyCondition(PolicyAttributeSource.RBAC, "permitted", PolicyOperator.EQUALS, "true"), Set.of());
        policies.insertPolicy(sodPolicy);
        policies.insertSeparationOfDutyConstraint(new SeparationOfDutyConstraint(id(25), sodPolicy.id(), ORG,
                requester, approver, "maker checker", NOW, OWNER));
        var guard = new SeparationOfDutyService(policies, identities, feature);
        expectCode("IAM_SOD_CONFLICT", () -> guard.check(approver, AssignmentActorType.USER, user, AuthorizationScope.organization(ORG), NOW));
    }

    private static AccessPolicy activePolicy(UuidV7Generator ids, String code, int priority, PolicyEffect effect,
            PolicyCondition condition, Set<PolicyObligation> obligations) {
        PolicyRule rule = new PolicyRule(ids.next(), 1, effect, "asset.read", "asset", List.of(condition), obligations, "smoke advice");
        return new AccessPolicy(ids.next(), ORG, code, 1, OWNER, "smoke policy", priority, AuthorizationScope.organization(ORG),
                PolicyState.ACTIVE, NOW, APPROVER, NOW, NOW, null, null, NOW, NOW, List.of(rule));
    }

    private static PolicyEvaluationRequest request(Map<String, String> environment, boolean rbacPermitted) {
        return new PolicyEvaluationRequest(OWNER, "asset.read", "asset", "node-1", AuthorizationScope.organization(ORG),
                environment, "LOCAL_SESSION", "cap-v1", null, rbacPermitted);
    }

    private static IdentityUser activeUser(DomainIdentifier id, String login) {
        return IdentityUser.pending(id, login, null, login, NOW).activate(NOW);
    }

    private static DomainIdentifier id(int suffix) {
        return DomainIdentifier.parse(String.format("00000000-0000-7000-8000-%012d", suffix));
    }

    private static void expectCode(String code, Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("expected " + code);
        } catch (IdentityAccessException error) {
            assert error.code().equals(code) : error.code();
        }
    }

    private record Features(boolean advanced) implements IdentityAccessFeaturePolicy {
        @Override public boolean supportsNestedGroups() { return true; }
        @Override public boolean supportsMultiMembership() { return true; }
        @Override public boolean supportsAdvancedAuthorization() { return advanced; }
    }
}
