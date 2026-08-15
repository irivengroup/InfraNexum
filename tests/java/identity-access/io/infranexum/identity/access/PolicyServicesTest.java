package io.infranexum.identity.access;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.audit.InMemoryAppendOnlyAuditJournal;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.InMemoryEventStore;
import io.infranexum.identity.access.application.*;
import io.infranexum.identity.access.domain.*;
import io.infranexum.identity.access.ports.*;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Behavioral coverage for PAP/PDP cache, fail-closed behavior and static SoD. */
class PolicyServicesTest {
    private static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");
    private static final DomainIdentifier ORG = IdentityAccessDomainTest.id(800);
    private static final DomainIdentifier OWNER = IdentityAccessDomainTest.id(801);
    private static final DomainIdentifier APPROVER = IdentityAccessDomainTest.id(802);
    private static final DomainIdentifier CORRELATION = IdentityAccessDomainTest.id(803);

    private IdentityAccessTestRepository identities;
    private AccessPolicyTestRepository policies;
    private InMemoryAppendOnlyAuditJournal audit;
    private UuidV7Generator ids;
    private Clock clock;
    private IdentityAccessFeaturePolicy advanced;

    @BeforeEach
    void setUp() {
        identities = new IdentityAccessTestRepository();
        policies = new AccessPolicyTestRepository();
        audit = new InMemoryAppendOnlyAuditJournal();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ids = new UuidV7Generator(clock, new SecureRandom(new byte[] {7, 0, 0, 4}));
        advanced = new Features(true);
        identities.insertUser(activeUser(OWNER, "policy.owner"));
        identities.insertUser(activeUser(APPROVER, "policy.approver"));
    }

    @Test
    void papCreatesVersionedPolicyAndEnforcesFourEyesLifecycleAndRollback() {
        PolicyAdministrationService service = administration(advanced);
        IdentityAccessCommandContext owner = context(OWNER);
        IdentityAccessCommandContext approver = context(APPROVER);
        PolicyRuleDefinition rule = permitRule("asset.read", Set.of(PolicyObligation.REQUIRE_JUSTIFICATION));

        AccessPolicy first = service.createPolicy(ORG, "asset.access", "Asset access", 100,
                AuthorizationScope.organization(ORG), NOW, List.of(rule), List.of(), owner);
        assertEquals(1, first.version());
        assertEquals(first, service.getPolicy(first.id()));
        assertEquals(1, service.listPolicies(ORG, 0, 10).size());
        assertThrows(IllegalArgumentException.class, () -> service.listPolicies(ORG, -1, 10));
        assertCode("IAM_POLICY_SELF_APPROVAL_FORBIDDEN", () -> service.approvePolicy(service.validatePolicy(first.id(), owner).id(), owner));
        AccessPolicy active1 = service.activatePolicy(service.approvePolicy(first.id(), approver).id(), approver);
        assertEquals(PolicyState.ACTIVE, active1.state());

        AccessPolicy second = service.createPolicy(ORG, "asset.access", "Asset access v2", 200,
                AuthorizationScope.organization(ORG), NOW, List.of(rule), List.of(), owner);
        assertEquals(2, second.version());
        service.validatePolicy(second.id(), owner);
        service.approvePolicy(second.id(), approver);
        AccessPolicy active2 = service.activatePolicy(second.id(), approver);
        assertEquals(PolicyState.DEPRECATED, policies.findPolicy(active1.id()).orElseThrow().state());
        assertEquals(PolicyState.ACTIVE, active2.state());
        AccessPolicy rollback = service.activatePolicy(active1.id(), approver);
        assertEquals(PolicyState.ACTIVE, rollback.state());
        assertEquals(PolicyState.DEPRECATED, policies.findPolicy(active2.id()).orElseThrow().state());
    }

    @Test
    void papRejectsLiteSystemCodesInactiveOwnersDanglingScopesAndInvalidSodRoles() {
        PolicyRuleDefinition rule = permitRule("asset.read", Set.of());
        assertCode("IAM_ADVANCED_AUTHORIZATION_UNAVAILABLE", () -> administration(new Features(false)).createPolicy(
                ORG, "asset.access", "x", 1, AuthorizationScope.organization(ORG), NOW, List.of(rule), List.of(), context(OWNER)));
        assertCode("IAM_SYSTEM_POLICY_PROTECTED", () -> administration(advanced).createPolicy(
                ORG, "system.custom", "x", 1, AuthorizationScope.organization(ORG), NOW, List.of(rule), List.of(), context(OWNER)));
        identities.updateUser(identities.findUser(OWNER).orElseThrow().suspend(NOW));
        assertCode("IAM_POLICY_OWNER_NOT_FOUND", () -> administration(advanced).createPolicy(
                ORG, "asset.access", "x", 1, AuthorizationScope.organization(ORG), NOW, List.of(rule), List.of(), context(OWNER)));
        identities.updateUser(activeUser(OWNER, "policy.owner"));
        DomainIdentifier unknownOrg = IdentityAccessDomainTest.id(899);
        assertCode("IAM_ORGANIZATION_NOT_FOUND", () -> administration(advanced).createPolicy(
                unknownOrg, "asset.access", "x", 1, AuthorizationScope.organization(unknownOrg), NOW, List.of(rule), List.of(), context(OWNER)));
        assertCode("IAM_POLICY_SCOPE_MISMATCH", () -> administration(advanced).createPolicy(
                ORG, "asset.access", "x", 1, AuthorizationScope.platform(), NOW, List.of(rule), List.of(), context(OWNER)));
        assertCode("IAM_ROLE_NOT_FOUND", () -> administration(advanced).createPolicy(
                ORG, "asset.access", "x", 1, AuthorizationScope.organization(ORG), NOW, List.of(rule),
                List.of(new SeparationOfDutyDefinition(IdentityAccessDomainTest.id(880), IdentityAccessDomainTest.id(881), "four eyes")), context(OWNER)));
    }

    @Test
    void pdpUsesDenyOverridesObligationsCacheAndFailsClosedOnUnavailableInputs() {
        AccessPolicy bridge = activePolicy("system.rbac-bridge", 0, PolicyEffect.PERMIT,
                new PolicyCondition(PolicyAttributeSource.RBAC, "permitted", PolicyOperator.EQUALS, "true"), Set.of());
        AccessPolicy deny = activePolicy("asset.block", 500, PolicyEffect.DENY,
                new PolicyCondition(PolicyAttributeSource.SUBJECT, "department", PolicyOperator.EQUALS, "blocked"), Set.of());
        policies.insertPolicy(bridge);
        policies.insertPolicy(deny);
        List<Boolean> hits = new ArrayList<>();
        PolicyInformationPort pip = (request, at) -> PolicyAttributeBag.builder()
                .put(PolicyAttributeSource.RBAC, "permitted", Boolean.toString(request.rbacPermitted()))
                .put(PolicyAttributeSource.SUBJECT, "department", request.environment().getOrDefault("department", "ops"))
                .build();
        PolicyDecisionService service = decision(pip, advanced, (result, elapsed, cacheHit) -> hits.add(cacheHit));

        PolicyEvaluationRequest permitRequest = request(Map.of("department", "ops"), true, null);
        PolicyEvaluationResult permit = service.decide(permitRequest, CORRELATION, "TEST");
        assertEquals(PolicyDecision.PERMIT, permit.decision());
        assertTrue(service.decide(permitRequest, CORRELATION, "TEST").permitted());
        assertEquals(List.of(false, true), hits);

        assertEquals(PolicyDecision.DENY, service.decide(request(Map.of("department", "blocked"), true, null), CORRELATION, "TEST").decision());
        assertEquals(PolicyDecision.INDETERMINATE,
                decision((request, at) -> { throw new IllegalStateException("PIP down"); }, advanced, (r, d, c) -> {})
                        .decide(permitRequest, CORRELATION, "TEST").decision());
        policies.failActivePolicies = true;
        assertEquals(PolicyDecision.INDETERMINATE, service.decide(permitRequest, CORRELATION, "TEST").decision());
        policies.failActivePolicies = false;
        assertEquals(PolicyDecision.INDETERMINATE, service.decide(request(Map.of(), true, "wrong"), CORRELATION, "TEST").decision());
        assertEquals(PolicyDecision.NOT_APPLICABLE,
                decision(pip, new Features(false), (r,d,c)->{}).decide(permitRequest, CORRELATION, "TEST").decision());
        assertTrue(service.activePolicyVersion(AuthorizationScope.organization(ORG)).startsWith("sha256:"));
    }

    @Test
    void missingRequiredAttributeIsIndeterminateAndPermitObligationIsReturned() {
        AccessPolicy required = activePolicy("asset.justified", 100, PolicyEffect.PERMIT,
                new PolicyCondition(PolicyAttributeSource.SUBJECT, "clearance", PolicyOperator.EQUALS, "approved"),
                Set.of(PolicyObligation.REQUIRE_JUSTIFICATION));
        policies.insertPolicy(required);
        PolicyDecisionService missing = decision((request, at) -> PolicyAttributeBag.builder().build(), advanced, (r,d,c)->{});
        assertEquals(PolicyDecision.INDETERMINATE, missing.decide(request(Map.of(), true, null), CORRELATION, "TEST").decision());
        PolicyDecisionService present = decision((request, at) -> PolicyAttributeBag.builder()
                .put(PolicyAttributeSource.SUBJECT, "clearance", "approved").build(), advanced, (r,d,c)->{});
        PolicyEvaluationResult result = present.decide(request(Map.of(), true, null), CORRELATION, "TEST");
        assertEquals(PolicyDecision.PERMIT, result.decision());
        assertEquals(Set.of(PolicyObligation.REQUIRE_JUSTIFICATION), result.obligations());
    }

    @Test
    void staticSodRejectsDirectAndGroupAssignmentsBeforePersistence() {
        DomainIdentifier roleA = IdentityAccessDomainTest.id(850);
        DomainIdentifier roleB = IdentityAccessDomainTest.id(851);
        DomainIdentifier user = IdentityAccessDomainTest.id(852);
        identities.insertUser(activeUser(user, "sod.user"));
        identities.insertRole(new Role(roleA, ORG, "ops.requester", "Requester", ScopeKind.ORGANIZATION, false, true, NOW, NOW, null), Set.of());
        identities.insertRole(new Role(roleB, ORG, "ops.approver", "Approver", ScopeKind.ORGANIZATION, false, true, NOW, NOW, null), Set.of());
        identities.insertMembership(new UserMembership(IdentityAccessDomainTest.id(853), user, ORG, null, NOW, null, null));
        identities.insertAssignment(new RoleAssignment(IdentityAccessDomainTest.id(854), roleA, AssignmentActorType.USER, user,
                AuthorizationScope.organization(ORG), NOW, null, null, null));
        AccessPolicy active = activePolicy("sod.four-eyes", 100, PolicyEffect.PERMIT,
                new PolicyCondition(PolicyAttributeSource.RBAC, "permitted", PolicyOperator.EQUALS, "true"), Set.of());
        policies.insertPolicy(active);
        policies.insertSeparationOfDutyConstraint(new SeparationOfDutyConstraint(IdentityAccessDomainTest.id(855), active.id(), ORG,
                roleA, roleB, "four eyes", NOW, OWNER));
        SeparationOfDutyService guard = new SeparationOfDutyService(policies, identities, advanced);
        assertCode("IAM_SOD_CONFLICT", () -> guard.check(roleB, AssignmentActorType.USER, user, AuthorizationScope.organization(ORG), NOW));

        DomainIdentifier group = IdentityAccessDomainTest.id(856);
        identities.insertGroup(new IdentityGroup(group, ORG, "ops.team", "Ops team", false, NOW, NOW, null));
        identities.addUserToGroup(ORG, group, user, NOW);
        assertCode("IAM_SOD_CONFLICT", () -> guard.check(roleB, AssignmentActorType.GROUP, group, AuthorizationScope.organization(ORG), NOW));
        assertDoesNotThrow(() -> new SeparationOfDutyService(policies, identities, new Features(false))
                .check(roleB, AssignmentActorType.USER, user, AuthorizationScope.organization(ORG), NOW));
    }

    private PolicyAdministrationService administration(IdentityAccessFeaturePolicy features) {
        OrganizationScopeReferencePort scopes = new OrganizationScopeReferencePort() {
            @Override public boolean organizationExists(DomainIdentifier organizationId) { return ORG.equals(organizationId); }
            @Override public boolean subdivisionExists(DomainIdentifier organizationId, DomainIdentifier subdivisionId) { return false; }
        };
        return new PolicyAdministrationService(policies, identities, features, scopes, new InMemoryEventStore(), audit, ids, clock);
    }

    private PolicyDecisionService decision(PolicyInformationPort pip, IdentityAccessFeaturePolicy features, PolicyDecisionObserver observer) {
        return new PolicyDecisionService(policies, pip, features, observer, audit, ids, clock);
    }

    private static PolicyRuleDefinition permitRule(String action, Set<PolicyObligation> obligations) {
        return new PolicyRuleDefinition(PolicyEffect.PERMIT, action, "asset",
                List.of(new PolicyCondition(PolicyAttributeSource.RBAC, "permitted", PolicyOperator.EQUALS, "true")), obligations, "");
    }

    private AccessPolicy activePolicy(String code, int priority, PolicyEffect effect, PolicyCondition condition, Set<PolicyObligation> obligations) {
        PolicyRule rule = new PolicyRule(ids.next(), 1, effect, "asset.read", "asset", List.of(condition), obligations, "policy advice");
        return new AccessPolicy(ids.next(), ORG, code, 1, OWNER, "test policy", priority, AuthorizationScope.organization(ORG),
                PolicyState.ACTIVE, NOW, APPROVER, NOW, NOW, null, null, NOW, NOW, List.of(rule));
    }

    private static PolicyEvaluationRequest request(Map<String,String> environment, boolean rbac, String version) {
        return new PolicyEvaluationRequest(OWNER, "asset.read", "asset", "node-1", AuthorizationScope.organization(ORG),
                environment, "LOCAL_SESSION", "cap-v1", version, rbac);
    }

    private static IdentityAccessCommandContext context(DomainIdentifier actor) {
        return new IdentityAccessCommandContext(actor, CORRELATION, "policy administration", "TEST");
    }

    private static IdentityUser activeUser(DomainIdentifier id, String login) {
        return IdentityUser.pending(id, login, null, login, NOW).activate(NOW);
    }

    private static void assertCode(String expected, Runnable operation) {
        IdentityAccessException error = assertThrows(IdentityAccessException.class, operation::run);
        assertEquals(expected, error.code());
    }

    private record Features(boolean advanced) implements IdentityAccessFeaturePolicy {
        @Override public boolean supportsNestedGroups() { return true; }
        @Override public boolean supportsMultiMembership() { return true; }
        @Override public boolean supportsAdvancedAuthorization() { return advanced; }
    }
}
