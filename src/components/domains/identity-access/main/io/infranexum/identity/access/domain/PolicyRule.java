package io.infranexum.identity.access.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Declarative, ordered ABAC rule without executable expressions or implicit I/O. */
public record PolicyRule(
        DomainIdentifier id,
        int position,
        PolicyEffect effect,
        String action,
        String resourceType,
        List<PolicyCondition> conditions,
        Set<PolicyObligation> obligations,
        String advice) {
    private static final Pattern ACTION = Pattern.compile("(?:\\*|[a-z][a-z0-9_.-]{2,127})");
    private static final Pattern RESOURCE = Pattern.compile("(?:\\*|[a-z][a-z0-9_.-]{1,79})");

    public PolicyRule {
        Objects.requireNonNull(id, "id");
        if (position < 1 || position > 10_000) throw new IllegalArgumentException("policy rule position must be between 1 and 10000");
        Objects.requireNonNull(effect, "effect");
        action = selector(action, "action", ACTION);
        resourceType = selector(resourceType, "resourceType", RESOURCE);
        conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions"));
        if (conditions.isEmpty() || conditions.size() > 32) throw new IllegalArgumentException("policy rule requires between 1 and 32 conditions");
        obligations = Set.copyOf(Objects.requireNonNull(obligations, "obligations"));
        if (obligations.size() > 8) throw new IllegalArgumentException("policy rule has too many obligations");
        if (advice != null) IdentityUser.rejectIsoControls(advice, "advice");
        advice = advice == null ? "" : advice.strip();
        if (advice.length() > 500 || advice.indexOf('\0') >= 0) throw new IllegalArgumentException("policy rule advice is invalid");
    }

    public boolean targets(String requestedAction, String requestedResourceType) {
        Objects.requireNonNull(requestedAction, "requestedAction");
        Objects.requireNonNull(requestedResourceType, "requestedResourceType");
        return (action.equals("*") || action.equals(requestedAction))
                && (resourceType.equals("*") || resourceType.equals(requestedResourceType));
    }

    private static String selector(String value, String name, Pattern pattern) {
        String normalized = PolicyCondition.bounded(value, name, 128).toLowerCase(java.util.Locale.ROOT);
        if (!pattern.matcher(normalized).matches()) throw new IllegalArgumentException("policy " + name + " selector is invalid");
        return normalized;
    }
}
