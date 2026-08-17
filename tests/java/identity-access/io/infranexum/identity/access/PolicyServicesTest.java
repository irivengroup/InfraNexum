package io.infranexum.identity.access;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.audit.InMemoryAppendOnlyAuditJournal;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.*;
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


    @Test
    void papCoversPaginationGlobalScopeRuleBoundsSubdivisionAndSodRoleBranches() {
        PolicyAdministrationService service = administration(advanced);
        PolicyRuleDefinition rule = permitRule("asset.read", Set.of());
        assertThrows(IllegalArgumentException.class, () -> service.listPolicies(ORG, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.listPolicies(ORG, 0, 201));
        assertThrows(IllegalArgumentException.class, () -> service.createPolicy(
                ORG, "asset.past", "Past", 1, AuthorizationScope.organization(ORG), NOW.minusSeconds(1),
                List.of(rule), List.of(), context(OWNER)));
        assertThrows(IllegalArgumentException.class, () -> service.createPolicy(
                ORG, "asset.empty", "Empty", 1, AuthorizationScope.organization(ORG), NOW,
                List.of(), List.of(), context(OWNER)));
        assertThrows(IllegalArgumentException.class, () -> service.createPolicy(
                ORG, "asset.many", "Many", 1, AuthorizationScope.organization(ORG), NOW,
                java.util.Collections.nCopies(257, rule), List.of(), context(OWNER)));
        SeparationOfDutyDefinition repeated = new SeparationOfDutyDefinition(
                IdentityAccessDomainTest.id(870), IdentityAccessDomainTest.id(871), "four eyes");
        assertThrows(IllegalArgumentException.class, () -> service.createPolicy(
                ORG, "asset.sod-many", "Many SoD", 1, AuthorizationScope.organization(ORG), NOW,
                List.of(rule), java.util.Collections.nCopies(129, repeated), context(OWNER)));

        AccessPolicy global = service.createPolicy(null, "global.audit", "Global", 1,
                AuthorizationScope.platform(), NOW, List.of(rule), List.of(), context(OWNER));
        assertNull(global.organizationId());
        DomainIdentifier subdivision = IdentityAccessDomainTest.id(872);
        assertCode("IAM_SUBDIVISION_NOT_FOUND", () -> service.createPolicy(
                ORG, "asset.bad-subdivision", "Bad subdivision", 1,
                AuthorizationScope.subdivision(ORG, subdivision), NOW, List.of(rule), List.of(), context(OWNER)));

        DomainIdentifier roleA = IdentityAccessDomainTest.id(873);
        DomainIdentifier roleB = IdentityAccessDomainTest.id(874);
        identities.insertRole(new Role(roleA, ORG, "ops.inactive-a", "Inactive A", ScopeKind.ORGANIZATION,
                false, false, NOW, NOW, null), Set.of());
        identities.insertRole(new Role(roleB, ORG, "ops.active-b", "Active B", ScopeKind.ORGANIZATION,
                false, true, NOW, NOW, null), Set.of());
        assertCode("IAM_SOD_ROLE_INACTIVE", () -> service.createPolicy(
                ORG, "asset.sod-inactive-a", "SoD", 1, AuthorizationScope.organization(ORG), NOW, List.of(rule),
                List.of(new SeparationOfDutyDefinition(roleA, roleB, "four eyes")), context(OWNER)));

        identities.insertRole(new Role(roleA, ORG, "ops.active-a", "Active A", ScopeKind.ORGANIZATION,
                false, true, NOW, NOW, null), Set.of());
        identities.insertRole(new Role(roleB, ORG, "ops.inactive-b", "Inactive B", ScopeKind.ORGANIZATION,
                false, false, NOW, NOW, null), Set.of());
        assertCode("IAM_SOD_ROLE_INACTIVE", () -> service.createPolicy(
                ORG, "asset.sod-inactive-b", "SoD", 1, AuthorizationScope.organization(ORG), NOW, List.of(rule),
                List.of(new SeparationOfDutyDefinition(roleA, roleB, "four eyes")), context(OWNER)));

        identities.insertRole(new Role(roleB, IdentityAccessDomainTest.id(875), "ops.other", "Other", ScopeKind.ORGANIZATION,
                false, true, NOW, NOW, null), Set.of());
        assertCode("IAM_SOD_SCOPE_MISMATCH", () -> service.createPolicy(
                ORG, "asset.sod-scope", "SoD", 1, AuthorizationScope.organization(ORG), NOW, List.of(rule),
                List.of(new SeparationOfDutyDefinition(roleA, roleB, "four eyes")), context(OWNER)));
    }

    @Test
    void pdpCoversRbacEmptyPolicyExistsSelectorsSafeTargetAndVersionInvalidation() {
        PolicyInformationPort emptyAttributes = (request, at) -> PolicyAttributeBag.builder().build();
        PolicyDecisionService emptyService = decision(emptyAttributes, advanced, (r, d, c) -> {});
        assertEquals(PolicyDecision.DENY,
                emptyService.decide(request(Map.of(), false, null), CORRELATION, "TEST").decision());
        assertEquals(PolicyDecision.NOT_APPLICABLE,
                emptyService.decide(request(Map.of(), true, null), CORRELATION, "TEST").decision());
        assertEquals("unavailable", decision(emptyAttributes, new Features(false), (r,d,c)->{})
                .activePolicyVersion(AuthorizationScope.organization(ORG)));

        PolicyCondition existsFalse = new PolicyCondition(PolicyAttributeSource.SUBJECT, "flag", PolicyOperator.EXISTS, "false");
        PolicyRule emptyAdviceRule = new PolicyRule(ids.next(), 1, PolicyEffect.PERMIT, "asset.read", "asset",
                List.of(existsFalse), Set.of(), "");
        AccessPolicy existsPolicy = new AccessPolicy(ids.next(), ORG, "asset.exists", 1, OWNER, "exists", 10,
                AuthorizationScope.organization(ORG), PolicyState.ACTIVE, NOW, APPROVER, NOW, NOW, null, null,
                NOW, NOW, List.of(emptyAdviceRule));
        policies.insertPolicy(existsPolicy);
        PolicyEvaluationRequest unsafeTarget = new PolicyEvaluationRequest(OWNER, "asset.read", "asset", "unsafe target",
                AuthorizationScope.organization(ORG), Map.of(), "LOCAL_SESSION", "cap-v1", null, true);
        PolicyEvaluationResult existsPermit = emptyService.decide(unsafeTarget, CORRELATION, "TEST");
        assertEquals(PolicyDecision.PERMIT, existsPermit.decision());

        PolicyDecisionService presentExists = decision((request, at) -> PolicyAttributeBag.builder()
                .put(PolicyAttributeSource.SUBJECT, "flag", "present").build(), advanced, (r,d,c)->{});
        assertEquals(PolicyDecision.NOT_APPLICABLE,
                presentExists.decide(request(Map.of(), true, null), CORRELATION, "TEST").decision());

        PolicyCondition neq = new PolicyCondition(PolicyAttributeSource.SUBJECT, "department", PolicyOperator.NOT_EQUALS, "blocked");
        PolicyCondition contains = new PolicyCondition(PolicyAttributeSource.SUBJECT, "roles", PolicyOperator.CONTAINS, "operator");
        PolicyRule selectorMiss = new PolicyRule(ids.next(), 1, PolicyEffect.PERMIT, "asset.write", "asset",
                List.of(neq), Set.of(), "not targeted");
        PolicyRule selectorHit = new PolicyRule(ids.next(), 2, PolicyEffect.PERMIT, "asset.read", "asset",
                List.of(neq, contains), Set.of(), "matched advice");
        AccessPolicy selectors = new AccessPolicy(ids.next(), ORG, "asset.selectors", 1, OWNER, "selectors", 20,
                AuthorizationScope.organization(ORG), PolicyState.ACTIVE, NOW, APPROVER, NOW, NOW, null, null,
                NOW, NOW, List.of(selectorMiss, selectorHit));
        policies.insertPolicy(selectors);
        PolicyDecisionService selectorService = decision((request, at) -> PolicyAttributeBag.builder()
                .put(PolicyAttributeSource.SUBJECT, "department", "ops")
                .put(PolicyAttributeSource.SUBJECT, "roles", "operator").build(), advanced, (r,d,c)->{});
        assertEquals(PolicyDecision.PERMIT, selectorService.decide(request(Map.of(), true, null), CORRELATION, "TEST").decision());

        String before = selectorService.activePolicyVersion(AuthorizationScope.organization(ORG));
        policies.insertPolicy(activePolicy("asset.version-change", 30, PolicyEffect.PERMIT,
                new PolicyCondition(PolicyAttributeSource.RBAC, "permitted", PolicyOperator.EQUALS, "true"), Set.of()));
        String after = selectorService.activePolicyVersion(AuthorizationScope.organization(ORG));
        assertNotEquals(before, after);
        assertEquals(PolicyDecision.INDETERMINATE,
                selectorService.decide(request(Map.of(), true, null), CORRELATION, "TEST").decision());
    }


    @Test
    void pdpCoversMatchingRequestedVersionCacheCapacityAndLongAuditTarget() {
        AccessPolicy bridge = activePolicy("asset.cache", 1, PolicyEffect.PERMIT,
                new PolicyCondition(PolicyAttributeSource.SUBJECT, "tenant", PolicyOperator.EQUALS, "ops"), Set.of());
        policies.insertPolicy(bridge);
        PolicyDecisionService service = decision((request, at) -> PolicyAttributeBag.builder()
                .put(PolicyAttributeSource.SUBJECT, "tenant", "ops")
                .put(PolicyAttributeSource.ENVIRONMENT, "nonce", request.environment().getOrDefault("nonce", "0"))
                .build(), advanced, (r,d,c)->{});
        String version = service.activePolicyVersion(AuthorizationScope.organization(ORG));
        PolicyEvaluationRequest matching = request(Map.of(), true, version);
        assertEquals(PolicyDecision.PERMIT, service.decide(matching, CORRELATION, "TEST").decision());
        PolicyEvaluationRequest longTarget = new PolicyEvaluationRequest(OWNER, "asset.read", "asset", "x".repeat(201),
                AuthorizationScope.organization(ORG), Map.of(), "LOCAL_SESSION", "cap-v1", null, true);
        assertEquals(PolicyDecision.PERMIT, service.decide(longTarget, CORRELATION, "TEST").decision());
        for (int index = 0; index <= 4096; index++) {
            PolicyEvaluationRequest unique = request(Map.of("nonce", Integer.toString(index)), true, null);
            assertEquals(PolicyDecision.PERMIT, service.decide(unique, CORRELATION, "TEST").decision());
        }
    }

    @Test
    void papImplicitEffectiveTimeAndTransactionFailuresCoverBothExecutionBranches() {
        PolicyRuleDefinition rule = permitRule("asset.read", Set.of());
        AccessPolicy implicit = administration(advanced).createPolicy(ORG, "asset.implicit-time", "Implicit", 1,
                AuthorizationScope.organization(ORG), null, List.of(rule), List.of(), context(OWNER));
        assertEquals(NOW, implicit.effectiveFrom());

        TransactionalEventStore runtimeFailure = new FailingEventStore(new IdentityAccessException("IAM_FORCED", "forced"));
        PolicyAdministrationService runtimeService = administration(advanced, runtimeFailure);
        IdentityAccessException domain = assertThrows(IdentityAccessException.class, () -> runtimeService.createPolicy(
                ORG, "asset.runtime-failure", "Failure", 1, AuthorizationScope.organization(ORG), NOW,
                List.of(rule), List.of(), context(OWNER)));
        assertEquals("IAM_FORCED", domain.code());

        Exception checked = new Exception("checked transaction failure");
        TransactionExecutionException wrapped = assertThrows(TransactionExecutionException.class, () -> administration(advanced,
                new FailingEventStore(checked)).createPolicy(ORG, "asset.checked-failure", "Failure", 1,
                AuthorizationScope.organization(ORG), NOW, List.of(rule), List.of(), context(OWNER)));
        assertEquals(checked, wrapped.getCause());
    }

    @Test
    void pdpCoversPlatformAuditExistsAndMultiValuedEqualsBranches() {
        PolicyRule exists = new PolicyRule(ids.next(), 1, PolicyEffect.PERMIT, "asset.read", "asset",
                List.of(new PolicyCondition(PolicyAttributeSource.SUBJECT, "flag", PolicyOperator.EXISTS, "true")), Set.of(), "");
        AccessPolicy global = new AccessPolicy(ids.next(), null, "global.exists", 1, OWNER, "global", 1,
                AuthorizationScope.platform(), PolicyState.ACTIVE, NOW, APPROVER, NOW, NOW, null, null, NOW, NOW, List.of(exists));
        policies.insertPolicy(global);
        PolicyDecisionService existsService = decision((request, at) -> PolicyAttributeBag.builder()
                .put(PolicyAttributeSource.SUBJECT, "flag", "yes").build(), advanced, (r,d,c)->{});
        PolicyEvaluationRequest platform = new PolicyEvaluationRequest(OWNER, "asset.read", "asset", "node-1",
                AuthorizationScope.platform(), Map.of(), "LOCAL_SESSION", "cap-v1", null, true);
        assertEquals(PolicyDecision.PERMIT, existsService.decide(platform, CORRELATION, "TEST").decision());

        PolicyRule equals = new PolicyRule(ids.next(), 1, PolicyEffect.PERMIT, "asset.read", "asset",
                List.of(new PolicyCondition(PolicyAttributeSource.SUBJECT, "tenant", PolicyOperator.EQUALS, "ops")), Set.of(), "");
        policies.insertPolicy(new AccessPolicy(ids.next(), ORG, "asset.multi", 1, OWNER, "multi", 50,
                AuthorizationScope.organization(ORG), PolicyState.ACTIVE, NOW, APPROVER, NOW, NOW, null, null, NOW, NOW, List.of(equals)));
        PolicyDecisionService multi = decision((request, at) -> PolicyAttributeBag.builder()
                .put(PolicyAttributeSource.SUBJECT, "tenant", "ops")
                .put(PolicyAttributeSource.SUBJECT, "tenant", "other").build(), advanced, (r,d,c)->{});
        assertEquals(PolicyDecision.NOT_APPLICABLE, multi.decide(request(Map.of(), true, null), CORRELATION, "TEST").decision());
    }

    @Test
    void papCoversDeletedAndValidSodRoleCombinationsIncludingSubdivisionSuccess() {
        PolicyRuleDefinition rule = permitRule("asset.read", Set.of());
        DomainIdentifier subdivision = IdentityAccessDomainTest.id(890);
        OrganizationScopeReferencePort scopes = new OrganizationScopeReferencePort() {
            @Override public boolean organizationExists(DomainIdentifier organizationId) { return ORG.equals(organizationId); }
            @Override public boolean subdivisionExists(DomainIdentifier organizationId, DomainIdentifier subdivisionId) {
                return ORG.equals(organizationId) && subdivision.equals(subdivisionId);
            }
        };
        PolicyAdministrationService service = new PolicyAdministrationService(
                policies, identities, advanced, scopes, new InMemoryEventStore(), audit, ids, clock);
        AccessPolicy subdivisionPolicy = service.createPolicy(ORG, "asset.subdivision", "Subdivision", 1,
                AuthorizationScope.subdivision(ORG, subdivision), NOW, List.of(rule), List.of(), context(OWNER));
        assertEquals(subdivision, subdivisionPolicy.scope().subdivisionId());

        DomainIdentifier firstId = IdentityAccessDomainTest.id(891);
        DomainIdentifier secondId = IdentityAccessDomainTest.id(892);
        Role firstDeleted = new Role(firstId, ORG, "ops.deleted-first", "Deleted First", ScopeKind.ORGANIZATION,
                false, false, NOW, NOW, NOW);
        Role secondActive = new Role(secondId, ORG, "ops.active-second", "Active Second", ScopeKind.ORGANIZATION,
                false, true, NOW, NOW, null);
        identities.insertRole(firstDeleted, Set.of()); identities.insertRole(secondActive, Set.of());
        assertCode("IAM_SOD_ROLE_INACTIVE", () -> service.createPolicy(ORG, "asset.sod-deleted-first", "SoD", 1,
                AuthorizationScope.organization(ORG), NOW, List.of(rule),
                List.of(new SeparationOfDutyDefinition(firstId, secondId, "four eyes")), context(OWNER)));

        Role firstActive = new Role(firstId, ORG, "ops.active-first", "Active First", ScopeKind.ORGANIZATION,
                false, true, NOW, NOW, null);
        Role secondDeleted = new Role(secondId, ORG, "ops.deleted-second", "Deleted Second", ScopeKind.ORGANIZATION,
                false, false, NOW, NOW, NOW);
        identities.insertRole(firstActive, Set.of()); identities.insertRole(secondDeleted, Set.of());
        assertCode("IAM_SOD_ROLE_INACTIVE", () -> service.createPolicy(ORG, "asset.sod-deleted-second", "SoD", 1,
                AuthorizationScope.organization(ORG), NOW, List.of(rule),
                List.of(new SeparationOfDutyDefinition(firstId, secondId, "four eyes")), context(OWNER)));

        identities.insertRole(firstActive, Set.of()); identities.insertRole(secondActive, Set.of());
        AccessPolicy validSod = service.createPolicy(ORG, "asset.sod-valid", "SoD", 1,
                AuthorizationScope.organization(ORG), NOW, List.of(rule),
                List.of(new SeparationOfDutyDefinition(firstId, secondId, "four eyes")), context(OWNER));
        assertEquals("asset.sod-valid", validSod.code());

        DomainIdentifier otherOrg = IdentityAccessDomainTest.id(893);
        identities.insertRole(new Role(firstId, otherOrg, "ops.other-first", "Other First", ScopeKind.ORGANIZATION,
                false, true, NOW, NOW, null), Set.of());
        assertCode("IAM_SOD_SCOPE_MISMATCH", () -> service.createPolicy(ORG, "asset.sod-first-scope", "SoD", 1,
                AuthorizationScope.organization(ORG), NOW, List.of(rule),
                List.of(new SeparationOfDutyDefinition(firstId, secondId, "four eyes")), context(OWNER)));
    }

    @Test
    void sodGuardCoversNoConstraintNoConflictAndNullConflictPaths() {
        DomainIdentifier roleA = IdentityAccessDomainTest.id(876);
        DomainIdentifier roleB = IdentityAccessDomainTest.id(877);
        DomainIdentifier user = IdentityAccessDomainTest.id(878);
        identities.insertUser(activeUser(user, "sod.no-conflict"));
        SeparationOfDutyService guard = new SeparationOfDutyService(policies, identities, advanced);
        assertDoesNotThrow(() -> guard.check(roleA, AssignmentActorType.USER, user, AuthorizationScope.organization(ORG), NOW));

        AccessPolicy active = activePolicy("sod.no-conflict-policy", 100, PolicyEffect.PERMIT,
                new PolicyCondition(PolicyAttributeSource.RBAC, "permitted", PolicyOperator.EQUALS, "true"), Set.of());
        policies.insertPolicy(active);
        policies.insertSeparationOfDutyConstraint(new SeparationOfDutyConstraint(ids.next(), active.id(), ORG,
                roleA, roleB, "four eyes", NOW, OWNER));
        assertDoesNotThrow(() -> guard.check(roleA, AssignmentActorType.USER, user, AuthorizationScope.organization(ORG), NOW));
        policies.returnUnrelatedConstraints = true;
        assertDoesNotThrow(() -> guard.check(IdentityAccessDomainTest.id(879), AssignmentActorType.USER, user,
                AuthorizationScope.organization(ORG), NOW));
        policies.returnUnrelatedConstraints = false;
    }

    private PolicyAdministrationService administration(IdentityAccessFeaturePolicy features) {
        return administration(features, new InMemoryEventStore());
    }

    private PolicyAdministrationService administration(IdentityAccessFeaturePolicy features, TransactionalEventStore eventStore) {
        OrganizationScopeReferencePort scopes = new OrganizationScopeReferencePort() {
            @Override public boolean organizationExists(DomainIdentifier organizationId) { return ORG.equals(organizationId); }
            @Override public boolean subdivisionExists(DomainIdentifier organizationId, DomainIdentifier subdivisionId) { return false; }
        };
        return new PolicyAdministrationService(policies, identities, features, scopes, eventStore, audit, ids, clock);
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

    private static final class FailingEventStore implements TransactionalEventStore {
        private final Throwable cause;
        FailingEventStore(Throwable cause) { this.cause = cause; }
        @Override public <T> TransactionOutcome<T> execute(TransactionalWork<T> work) {
            throw new TransactionExecutionException("forced policy test failure", cause);
        }
        @Override public List<OutboxRecord> claimBatch(String workerId, int limit, Instant now, Duration leaseDuration) {
            throw new UnsupportedOperationException("not used");
        }
        @Override public void markPublished(DomainIdentifier eventId, String workerId, Instant publishedAt) {
            throw new UnsupportedOperationException("not used");
        }
        @Override public OutboxStatus markFailed(DomainIdentifier eventId, String workerId, Instant failedAt,
                RetryPolicy retryPolicy, Throwable failure) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private record Features(boolean advanced) implements IdentityAccessFeaturePolicy {
        @Override public boolean supportsNestedGroups() { return true; }
        @Override public boolean supportsMultiMembership() { return true; }
        @Override public boolean supportsAdvancedAuthorization() { return advanced; }
    }
}
