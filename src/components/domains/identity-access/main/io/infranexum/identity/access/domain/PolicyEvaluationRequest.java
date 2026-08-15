package io.infranexum.identity.access.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Map;
import java.util.Objects;

/** Trusted PDP request assembled by a PEP; authority attributes are not client supplied. */
public record PolicyEvaluationRequest(
        DomainIdentifier subjectId,
        String action,
        String resourceType,
        String resourceId,
        AuthorizationScope scope,
        Map<String, String> environment,
        String authenticationContext,
        String capabilityVersion,
        String requestedPolicyVersion,
        boolean rbacPermitted) {
    public PolicyEvaluationRequest {
        Objects.requireNonNull(subjectId, "subjectId");
        action = PolicyCondition.bounded(action, "action", 128).toLowerCase(java.util.Locale.ROOT);
        resourceType = PolicyCondition.bounded(resourceType, "resourceType", 80).toLowerCase(java.util.Locale.ROOT);
        resourceId = PolicyCondition.bounded(resourceId, "resourceId", 512);
        Objects.requireNonNull(scope, "scope");
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        if (environment.size() > 32) throw new IllegalArgumentException("policy environment has too many entries");
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            new PolicyCondition(PolicyAttributeSource.ENVIRONMENT, entry.getKey(), PolicyOperator.EQUALS, entry.getValue());
        }
        authenticationContext = PolicyCondition.bounded(authenticationContext, "authenticationContext", 80);
        capabilityVersion = PolicyCondition.bounded(capabilityVersion, "capabilityVersion", 80);
        if (requestedPolicyVersion != null) requestedPolicyVersion = PolicyCondition.bounded(requestedPolicyVersion, "requestedPolicyVersion", 128);
    }
}
