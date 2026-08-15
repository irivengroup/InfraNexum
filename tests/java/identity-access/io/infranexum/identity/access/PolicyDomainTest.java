package io.infranexum.identity.access;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit coverage for the deterministic PAP/PDP value model. */
class PolicyDomainTest {
    private static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");
    private static final DomainIdentifier OWNER = IdentityAccessDomainTest.id(700);
    private static final DomainIdentifier APPROVER = IdentityAccessDomainTest.id(701);

    @Test
    void conditionsRulesAndAttributeBagsAreClosedBoundedAndDeterministic() {
        PolicyCondition condition = new PolicyCondition(PolicyAttributeSource.SUBJECT, "Department", PolicyOperator.EQUALS, "ops");
        assertEquals("department", condition.attribute());
        assertEquals("true", new PolicyCondition(PolicyAttributeSource.RBAC, "permitted", PolicyOperator.EXISTS, "TRUE").expectedValue());
        assertThrows(IllegalArgumentException.class, () -> new PolicyCondition(PolicyAttributeSource.SUBJECT, "bad name", PolicyOperator.EQUALS, "x"));
        assertThrows(IllegalArgumentException.class, () -> new PolicyCondition(PolicyAttributeSource.SUBJECT, "ok", PolicyOperator.EXISTS, "yes"));
        assertThrows(IllegalArgumentException.class, () -> new PolicyCondition(PolicyAttributeSource.SUBJECT, "ok", PolicyOperator.EQUALS, " x"));

        PolicyRule rule = new PolicyRule(IdentityAccessDomainTest.id(702), 1, PolicyEffect.PERMIT, "asset.read", "asset",
                List.of(condition), Set.of(PolicyObligation.REQUIRE_JUSTIFICATION), " explain ");
        assertTrue(rule.targets("asset.read", "asset"));
        assertFalse(rule.targets("asset.write", "asset"));
        assertEquals("explain", rule.advice());
        assertThrows(IllegalArgumentException.class, () -> new PolicyRule(IdentityAccessDomainTest.id(703), 0, PolicyEffect.PERMIT,
                "asset.read", "asset", List.of(condition), Set.of(), ""));
        assertThrows(IllegalArgumentException.class, () -> new PolicyRule(IdentityAccessDomainTest.id(703), 1, PolicyEffect.PERMIT,
                "x", "asset", List.of(condition), Set.of(), ""));
        assertThrows(IllegalArgumentException.class, () -> new PolicyRule(IdentityAccessDomainTest.id(703), 1, PolicyEffect.PERMIT,
                "asset.read", "asset", List.of(), Set.of(), ""));

        PolicyAttributeBag bag = PolicyAttributeBag.builder().put(PolicyAttributeSource.SUBJECT, "department", "ops")
                .putAll(PolicyAttributeSource.SUBJECT, "groups", List.of("a", "b")).build();
        assertEquals(Set.of("ops"), bag.values(PolicyAttributeSource.SUBJECT, "department"));
        assertTrue(bag.fingerprintMaterial().contains("SUBJECT:department"));
        assertEquals(Set.of(), bag.values(PolicyAttributeSource.RESOURCE, "missing"));
    }

    @Test
    void accessPolicyLifecycleRequiresFourEyesAndProtectsSystemVersions() {
        AccessPolicy draft = policy("asset.operations", PolicyState.DRAFT, OWNER, null, null, null, null, null);
        AccessPolicy validated = draft.validatePolicy(NOW.plusSeconds(1));
        assertEquals(PolicyState.VALIDATED, validated.state());
        IdentityAccessException self = assertThrows(IdentityAccessException.class, () -> validated.approve(OWNER, NOW.plusSeconds(2)));
        assertEquals("IAM_POLICY_SELF_APPROVAL_FORBIDDEN", self.code());
        AccessPolicy approved = validated.approve(APPROVER, NOW.plusSeconds(2));
        AccessPolicy active = approved.activate(NOW.plusSeconds(3));
        assertTrue(active.effectiveAt(NOW.plusSeconds(3)));
        AccessPolicy deprecated = active.deprecate(NOW.plusSeconds(4));
        assertEquals(PolicyState.ACTIVE, deprecated.activate(NOW.plusSeconds(5)).state());
        assertEquals(PolicyState.RETIRED, deprecated.retire(NOW.plusSeconds(5)).state());
        assertFalse(active.effectiveAt(NOW.minusSeconds(1)));
        assertThrows(IdentityAccessException.class, () -> draft.activate(NOW));
        assertThrows(IllegalArgumentException.class, () -> draft.validatePolicy(NOW.minusSeconds(1)));

        AccessPolicy system = policy("system.rbac-bridge", PolicyState.ACTIVE, AccessPolicy.SYSTEM_OWNER_ID,
                AccessPolicy.SYSTEM_OWNER_ID, NOW, NOW, null, null);
        assertTrue(system.systemPolicy());
        assertThrows(IdentityAccessException.class, () -> system.deprecate(NOW.plusSeconds(1)));
        assertEquals("system.rbac-bridge", AccessPolicy.normalizeCode("System.Rbac-Bridge"));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicy.normalizeCode("invalid"));
    }

    @Test
    void requestsResultsAndSodDefinitionsRejectAmbiguity() {
        AuthorizationScope scope = AuthorizationScope.organization(IdentityAccessDomainTest.id(704));
        PolicyEvaluationRequest request = new PolicyEvaluationRequest(OWNER, "ASSET.READ", "ASSET", "node-1", scope,
                Map.of("channel", "HTTP"), "LOCAL_SESSION", "cap-v1", null, true);
        assertEquals("asset.read", request.action());
        assertThrows(IllegalArgumentException.class, () -> new PolicyEvaluationRequest(OWNER, "asset.read", "asset", "node",
                scope, java.util.stream.IntStream.range(0, 33).boxed().collect(java.util.stream.Collectors.toMap(i -> "k"+i, i -> "v")),
                "LOCAL", "v1", null, true));
        PolicyEvaluationResult result = new PolicyEvaluationResult(PolicyDecision.PERMIT, "OK", Set.of(), List.of(), "v1",
                IdentityAccessDomainTest.id(705), NOW.plusSeconds(30), List.of("asset.operations@1"));
        assertTrue(result.permitted());
        assertFalse(new PolicyEvaluationResult(PolicyDecision.INDETERMINATE, "X", Set.of(), List.of(), "v1",
                IdentityAccessDomainTest.id(706), NOW.plusSeconds(30), List.of()).permitted());

        SeparationOfDutyDefinition sod = new SeparationOfDutyDefinition(IdentityAccessDomainTest.id(708), IdentityAccessDomainTest.id(707), "four eyes");
        assertTrue(sod.firstRoleId().compareTo(sod.secondRoleId()) < 0);
        assertThrows(IllegalArgumentException.class, () -> new SeparationOfDutyDefinition(sod.firstRoleId(), sod.firstRoleId(), "x"));
    }

    private static AccessPolicy policy(String code, PolicyState state, DomainIdentifier owner, DomainIdentifier approvedBy,
            Instant approvedAt, Instant activatedAt, Instant deprecatedAt, Instant retiredAt) {
        PolicyRule rule = new PolicyRule(IdentityAccessDomainTest.id(710), 1, PolicyEffect.PERMIT, "asset.read", "asset",
                List.of(new PolicyCondition(PolicyAttributeSource.RBAC, "permitted", PolicyOperator.EQUALS, "true")), Set.of(), "");
        return new AccessPolicy(IdentityAccessDomainTest.id(711), null, code, 1, owner, "policy purpose", 100,
                AuthorizationScope.platform(), state, NOW, approvedBy, approvedAt, activatedAt, deprecatedAt, retiredAt,
                NOW, NOW, List.of(rule));
    }
}
