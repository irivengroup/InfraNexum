package io.infranexum.identity.access.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Client-independent definition used by the PAP before persistent rule identifiers are allocated. */
public record PolicyRuleDefinition(
        PolicyEffect effect,
        String action,
        String resourceType,
        List<PolicyCondition> conditions,
        Set<PolicyObligation> obligations,
        String advice) {
    public PolicyRuleDefinition {
        Objects.requireNonNull(effect, "effect");
        conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions"));
        obligations = Set.copyOf(Objects.requireNonNull(obligations, "obligations"));
        new PolicyRule(io.infranexum.core.contracts.DomainIdentifier.parse("00000000-0000-7000-8000-000000000000"),
                1, effect, action, resourceType, conditions, obligations, advice);
    }
}
