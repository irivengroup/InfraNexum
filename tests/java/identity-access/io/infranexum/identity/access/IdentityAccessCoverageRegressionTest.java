package io.infranexum.identity.access;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.application.AuthorizationDecision;
import io.infranexum.identity.access.application.IdentityAccessCommandContext;
import io.infranexum.identity.access.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Exhaustive boundary coverage for IAM value objects and policy records. */
class IdentityAccessCoverageRegressionTest {
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    @Test
    void scopeAndTemporalRecordsCoverAllBoundaryBranches() {
        var platform = AuthorizationScope.platform();
        var org = AuthorizationScope.organization(id(1));
        var sub = AuthorizationScope.subdivision(id(1), id(2));
        assertTrue(platform.covers(org));
        assertTrue(org.covers(sub));
        assertFalse(sub.covers(org));
        assertFalse(sub.covers(AuthorizationScope.subdivision(id(1), id(3))));
        assertFalse(org.covers(AuthorizationScope.organization(id(9))));
        assertThrows(NullPointerException.class, () -> new AuthorizationScope(null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new AuthorizationScope(ScopeKind.PLATFORM, id(1), id(2)));
        assertThrows(NullPointerException.class, () -> new AuthorizationScope(ScopeKind.SUBDIVISION, null, id(2)));

        var membership = new UserMembership(id(10), id(11), id(1), null, NOW, null, null);
        assertFalse(membership.effectiveAt(NOW.minusNanos(1)));
        assertTrue(membership.effectiveAt(NOW));
        var revoked = new UserMembership(id(12), id(11), id(1), null, NOW, null, NOW.plusSeconds(1));
        assertFalse(revoked.effectiveAt(NOW.plusSeconds(2)));
        assertThrows(NullPointerException.class, () -> membership.effectiveAt(null));
        assertThrows(IllegalArgumentException.class, () -> new UserMembership(id(13), id(11), id(1), null, NOW, NOW.minusSeconds(1), null));

        var assignment = new RoleAssignment(id(20), id(21), AssignmentActorType.USER, id(11), org, NOW, null, null, null);
        assertTrue(assignment.effectiveAt(NOW));
        assertFalse(assignment.effectiveAt(NOW.minusNanos(1)));
        assertFalse(new RoleAssignment(id(22), id(21), AssignmentActorType.USER, id(11), org, NOW, NOW.plusSeconds(1), null, null)
                .effectiveAt(NOW.plusSeconds(1)));
        assertFalse(new RoleAssignment(id(23), id(21), AssignmentActorType.USER, id(11), org, NOW, null, NOW, id(99)).effectiveAt(NOW));
        assertThrows(IllegalArgumentException.class, () -> new RoleAssignment(id(24), id(21), AssignmentActorType.USER, id(11), org, NOW, NOW.minusSeconds(1), null, null));
        assertThrows(IllegalArgumentException.class, () -> new RoleAssignment(id(25), id(21), AssignmentActorType.USER, id(11), org, NOW, null, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> new RoleAssignment(id(26), id(21), AssignmentActorType.USER, id(11), org, NOW, null, null, id(99)));
    }

    @Test
    void identityObjectsRejectMalformedAndExerciseLifecycleBranches() {
        var user = IdentityUser.pending(id(30), "valid.user", "valid@example.test", "Valid User", NOW);
        assertEquals(IdentityUserStatus.ACTIVE, user.activate(NOW.plusSeconds(1)).status());
        assertEquals(IdentityUserStatus.SUSPENDED, user.suspend(NOW.plusSeconds(1)).status());
        assertThrows(IllegalArgumentException.class, () -> IdentityUser.pending(id(31), "valid.user", "x".repeat(321) + "@x", "Valid", NOW));
        assertThrows(IllegalArgumentException.class, () -> IdentityUser.pending(id(31), "valid.user", "a@b\n", "Valid", NOW));
        assertThrows(IllegalArgumentException.class, () -> IdentityUser.pending(id(31), "valid.user", null, "x".repeat(201), NOW));
        assertThrows(IllegalArgumentException.class, () -> IdentityUser.pending(id(31), "valid user", null, "Valid", NOW));

        var group = new IdentityGroup(id(40), id(1), "ops.team", "Ops", false, NOW, NOW, null);
        assertFalse(group.deleted());
        assertThrows(IllegalArgumentException.class, () -> new IdentityGroup(id(41), id(1), "1bad.code", "Ops", false, NOW, NOW, null));

        var permission = new Permission(id(50), id(1), "asset.read", "asset", "read", "normal", ScopeKind.ORGANIZATION, false, true, NOW, NOW, null);
        assertTrue(permission.active());
        assertThrows(IdentityAccessException.class, () -> new Permission(id(51), null, "asset.read", "asset", "read", "normal", ScopeKind.PLATFORM, true, true, NOW, NOW, null).update("asset", "read", "normal", ScopeKind.PLATFORM, true, NOW));
        assertThrows(IllegalArgumentException.class, () -> new Permission(id(52), id(1), "asset.read", "bad token!", "read", "normal", ScopeKind.ORGANIZATION, false, true, NOW, NOW, null));

        var role = new Role(id(60), id(1), "asset.reader", "Reader", ScopeKind.ORGANIZATION, false, true, NOW, NOW, null);
        assertTrue(role.active());
        assertThrows(IdentityAccessException.class, () -> role.delete(NOW).update("asset.reader", "x", NOW));
        assertThrows(IllegalArgumentException.class, () -> new Role(id(61), id(1), "x", "X", ScopeKind.ORGANIZATION, false, true, NOW, NOW, null));
    }

    @Test
    void policyConditionRuleAndBagCoverSelectorsExistsAndValidationBoundaries() {
        var exists = new PolicyCondition(PolicyAttributeSource.SUBJECT, "group", PolicyOperator.EXISTS, "TRUE");
        assertEquals("true", exists.expectedValue());
        var equals = new PolicyCondition(PolicyAttributeSource.RESOURCE, "classification", PolicyOperator.EQUALS, "secret");
        assertEquals("secret", equals.expectedValue());
        assertThrows(IllegalArgumentException.class, () -> new PolicyCondition(PolicyAttributeSource.SUBJECT, "Bad Attribute", PolicyOperator.EQUALS, "x"));
        assertThrows(IllegalArgumentException.class, () -> new PolicyCondition(PolicyAttributeSource.SUBJECT, "group", PolicyOperator.EXISTS, "maybe"));
        assertThrows(IllegalArgumentException.class, () -> new PolicyCondition(PolicyAttributeSource.SUBJECT, "group", PolicyOperator.EQUALS, " x "));
        assertThrows(IllegalArgumentException.class, () -> new PolicyCondition(PolicyAttributeSource.SUBJECT, "group", PolicyOperator.EQUALS, "x\0"));

        var rule = new PolicyRule(id(70), 1, PolicyEffect.PERMIT, "*", "*", List.of(equals), Set.of(), null);
        assertTrue(rule.targets("asset.read", "asset"));
        assertThrows(NullPointerException.class, () -> rule.targets(null, "asset"));
        assertThrows(IllegalArgumentException.class, () -> new PolicyRule(id(71), 0, PolicyEffect.PERMIT, "asset.read", "asset", List.of(equals), Set.of(), ""));
        assertThrows(IllegalArgumentException.class, () -> new PolicyRule(id(72), 1, PolicyEffect.PERMIT, "x", "asset", List.of(equals), Set.of(), ""));
        assertThrows(IllegalArgumentException.class, () -> new PolicyRule(id(73), 1, PolicyEffect.PERMIT, "asset.read", "x!", List.of(equals), Set.of(), ""));
        assertThrows(IllegalArgumentException.class, () -> new PolicyRule(id(74), 1, PolicyEffect.PERMIT, "asset.read", "asset", List.of(), Set.of(), ""));
        assertThrows(IllegalArgumentException.class, () -> new PolicyRule(id(75), 1, PolicyEffect.PERMIT, "asset.read", "asset", List.of(equals), Set.of(), "x\0"));

        PolicyAttributeBag bag = PolicyAttributeBag.builder()
                .put(PolicyAttributeSource.SUBJECT, "group", "ops")
                .putAll(PolicyAttributeSource.SUBJECT, "roles", List.of("reader", "writer", "reader"))
                .build();
        assertEquals(Set.of("ops"), bag.values(PolicyAttributeSource.SUBJECT, "group"));
        assertTrue(bag.values(PolicyAttributeSource.RESOURCE, "missing").isEmpty());
        assertTrue(bag.fingerprintMaterial().contains("SUBJECT"));
        assertThrows(NullPointerException.class, () -> PolicyAttributeBag.builder().put(PolicyAttributeSource.SUBJECT, "group", null));
        assertThrows(IllegalArgumentException.class, () -> PolicyAttributeBag.builder().put(PolicyAttributeSource.SUBJECT, "group", " x "));
    }

    @Test
    void policyRecordsExerciseApprovalLifecycleAndConstructorGuards() {
        var condition = new PolicyCondition(PolicyAttributeSource.RBAC, "permitted", PolicyOperator.EQUALS, "true");
        var rule = new PolicyRule(id(80), 1, PolicyEffect.PERMIT, "asset.read", "asset", List.of(condition), Set.of(PolicyObligation.REQUIRE_JUSTIFICATION), "ok");
        var draft = policy(PolicyState.DRAFT, null, null, null, null, null, List.of(rule));
        assertFalse(draft.systemPolicy());
        var validated = draft.validatePolicy(NOW.plusSeconds(1));
        assertThrows(IdentityAccessException.class, () -> validated.validatePolicy(NOW.plusSeconds(2)));
        assertThrows(IdentityAccessException.class, () -> validated.approve(validated.ownerId(), NOW.plusSeconds(2)));
        var approved = validated.approve(id(999), NOW.plusSeconds(2));
        var active = approved.activate(NOW.plusSeconds(3));
        assertTrue(active.effectiveAt(NOW.plusSeconds(3)));
        var deprecated = active.deprecate(NOW.plusSeconds(4));
        assertEquals(PolicyState.ACTIVE, deprecated.activate(NOW.plusSeconds(5)).state());
        assertEquals(PolicyState.RETIRED, deprecated.retire(NOW.plusSeconds(5)).state());
        assertThrows(IllegalArgumentException.class, () -> validated.approve(id(998), NOW));

        assertThrows(IllegalArgumentException.class, () -> policyWith(0, 100, AuthorizationScope.platform(), PolicyState.DRAFT, null, null, null, null, null, List.of(rule)));
        assertThrows(IllegalArgumentException.class, () -> policyWith(1, -1, AuthorizationScope.platform(), PolicyState.DRAFT, null, null, null, null, null, List.of(rule)));
        assertThrows(IllegalArgumentException.class, () -> policyWith(1, 10001, AuthorizationScope.platform(), PolicyState.DRAFT, null, null, null, null, null, List.of(rule)));
        assertThrows(IllegalArgumentException.class, () -> policyWith(1, 1, AuthorizationScope.organization(id(1)), PolicyState.DRAFT, null, null, null, null, null, List.of(rule)));
        assertThrows(IllegalArgumentException.class, () -> policyWith(1, 1, AuthorizationScope.platform(), PolicyState.APPROVED, null, null, null, null, null, List.of(rule)));
        assertThrows(IllegalArgumentException.class, () -> policyWith(1, 1, AuthorizationScope.platform(), PolicyState.APPROVED, id(2), null, null, null, null, List.of(rule)));
        assertThrows(IllegalArgumentException.class, () -> policyWith(1, 1, AuthorizationScope.platform(), PolicyState.ACTIVE, id(2), NOW, null, null, null, List.of(rule)));
        assertThrows(IllegalArgumentException.class, () -> policyWith(1, 1, AuthorizationScope.platform(), PolicyState.DEPRECATED, id(2), NOW, NOW, null, null, List.of(rule)));
        assertThrows(IllegalArgumentException.class, () -> policyWith(1, 1, AuthorizationScope.platform(), PolicyState.RETIRED, id(2), NOW, NOW, NOW, null, List.of(rule)));
        assertThrows(IllegalArgumentException.class, () -> policyWith(1, 1, AuthorizationScope.platform(), PolicyState.DRAFT, null, null, null, null, null, List.of(rule, new PolicyRule(id(81), 1, PolicyEffect.DENY, "asset.read", "asset", List.of(condition), Set.of(), ""))));
    }

    @Test
    void evaluationSodExceptionsAndContextExerciseRemainingBranches() {
        var org = AuthorizationScope.organization(id(1));
        var request = new PolicyEvaluationRequest(id(90), "ASSET.READ", "ASSET", "node-1", org, Map.of("channel", "web"), "LOCAL", "v1", null, false);
        assertEquals("asset.read", request.action());
        assertNull(request.requestedPolicyVersion());
        assertThrows(IllegalArgumentException.class, () -> new PolicyEvaluationRequest(id(90), "asset.read", "asset", "node", org, Map.of("bad key!", "x"), "LOCAL", "v1", null, true));
        assertThrows(IllegalArgumentException.class, () -> new PolicyEvaluationRequest(id(90), "asset.read", "asset", "node", org, Map.of("key", " x "), "LOCAL", "v1", null, true));

        var deny = new PolicyEvaluationResult(PolicyDecision.DENY, "DENIED", Set.of(), List.of(), "v1", id(91), NOW.plusSeconds(5), List.of());
        assertFalse(deny.permitted());
        assertThrows(NullPointerException.class, () -> new PolicyEvaluationResult(null, "X", Set.of(), List.of(), "v1", id(91), NOW, List.of()));

        var sod = new SeparationOfDutyConstraint(id(100), id(101), id(1), id(103), id(102), "reason", NOW, id(104));
        assertEquals(id(103), sod.conflictingRole(id(102)));
        assertEquals(id(102), sod.conflictingRole(id(103)));
        assertNull(sod.conflictingRole(id(999)));
        assertThrows(IllegalArgumentException.class, () -> new SeparationOfDutyConstraint(id(100), id(101), id(1), id(102), id(102), "reason", NOW, id(104)));
        assertThrows(NullPointerException.class, () -> sod.conflictingRole(null));

        var definition = new SeparationOfDutyDefinition(id(106), id(105), "reason");
        assertTrue(definition.firstRoleId().compareTo(definition.secondRoleId()) < 0);
        assertThrows(IllegalArgumentException.class, () -> new SeparationOfDutyDefinition(id(105), id(105), "reason"));

        var failure = new IdentityAccessException(" IAM_BAD ", "bad");
        assertEquals("IAM_BAD", failure.code());
        assertThrows(IllegalArgumentException.class, () -> new IdentityAccessException("x", "bad"));
        assertEquals(true, AuthorizationDecision.allow("OK", "ok").allowed());
        assertEquals(false, AuthorizationDecision.deny("NO", "no").allowed());
        assertThrows(IllegalArgumentException.class, () -> new IdentityAccessCommandContext(id(1), id(2), " ", "origin"));
        assertThrows(IllegalArgumentException.class, () -> new IdentityAccessCommandContext(id(1), id(2), "corr\n", "origin"));
    }

    private static AccessPolicy policy(PolicyState state, DomainIdentifier approvedBy, Instant approvedAt,
            Instant activatedAt, Instant deprecatedAt, Instant retiredAt, List<PolicyRule> rules) {
        return policyWith(1, 100, AuthorizationScope.platform(), state, approvedBy, approvedAt, activatedAt, deprecatedAt, retiredAt, rules);
    }

    private static AccessPolicy policyWith(long version, int priority, AuthorizationScope scope, PolicyState state,
            DomainIdentifier approvedBy, Instant approvedAt, Instant activatedAt, Instant deprecatedAt,
            Instant retiredAt, List<PolicyRule> rules) {
        return new AccessPolicy(id(200), null, "asset.operations", version, id(201), "purpose", priority, scope, state,
                NOW, approvedBy, approvedAt, activatedAt, deprecatedAt, retiredAt, NOW, NOW, rules);
    }

    private static DomainIdentifier id(int value) {
        return new DomainIdentifier(new UUID(0x0198_0000_0000_7000L + value, 0x8000_0000_0000_0000L + value));
    }
}
