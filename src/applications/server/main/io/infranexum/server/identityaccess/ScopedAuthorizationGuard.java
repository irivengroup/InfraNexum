package io.infranexum.server.identityaccess;

import io.infranexum.server.http.AuthenticatedActorContext;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.application.AuthorizationDecision;
import io.infranexum.identity.access.application.PolicyDecisionService;
import io.infranexum.identity.access.application.RbacAuthorizationService;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.IdentityAccessException;
import io.infranexum.identity.access.domain.PolicyEvaluationRequest;
import io.infranexum.identity.access.domain.PolicyObligation;
import io.infranexum.identity.access.ports.IdentityAccessFeaturePolicy;
import io.infranexum.server.observability.CorrelationContext;
import io.infranexum.server.platform.PlatformCapabilityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Resolves RBAC/ABAC scopes that are only known after query/body binding at the HTTP controller boundary. */
public final class ScopedAuthorizationGuard {
    private final RbacAuthorizationService rbac;
    private final PolicyDecisionService policies;
    private final IdentityAccessFeaturePolicy features;
    private final PlatformCapabilityService capabilities;

    public ScopedAuthorizationGuard(
            RbacAuthorizationService rbac,
            PolicyDecisionService policies,
            IdentityAccessFeaturePolicy features,
            PlatformCapabilityService capabilities) {
        this.rbac = Objects.requireNonNull(rbac, "rbac");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.features = Objects.requireNonNull(features, "features");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    public void require(
            HttpServletRequest request, HttpServletResponse response, String permission, AuthorizationScope scope,
            String targetType, String targetId) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(scope, "scope");
        DomainIdentifier actor = actor(request);
        DomainIdentifier correlation = CorrelationContext.identifier(request)
                .orElseThrow(() -> new IllegalStateException("correlation context missing after authorization boundary"));
        AuthorizationDecision decision = rbac.decide(actor, permission, scope, correlation, targetType, targetId, "HTTP");
        if (!decision.allowed()) {
            throw new IdentityAccessException("IAM_AUTHORIZATION_DENIED", decision.explanation());
        }
        if (!features.supportsAdvancedAuthorization()) {
            return;
        }

        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("channel", "HTTP");
        environment.put("method", request.getMethod().toUpperCase(java.util.Locale.ROOT));
        environment.put("justification_present", Boolean.toString(validJustification(
                request.getHeader(AdvancedAuthorizationFilter.JUSTIFICATION_HEADER))));
        String capabilityVersion = capabilities.snapshot().catalogVersion() + ":" + capabilities.snapshot().profileVersion();
        var evaluation = new PolicyEvaluationRequest(
                actor, permission, targetType, targetId, scope, environment, "LOCAL_SESSION", capabilityVersion, null, true);
        var advanced = policies.decide(evaluation, correlation, "HTTP");
        response.setHeader(AdvancedAuthorizationFilter.DECISION_HEADER, advanced.decisionId().toString());
        if (!advanced.permitted()) {
            throw new IdentityAccessException("IAM_ADVANCED_AUTHORIZATION_DENIED", advanced.reasonCode());
        }
        for (PolicyObligation obligation : advanced.obligations()) {
            if (obligation == PolicyObligation.REQUIRE_JUSTIFICATION
                    && validJustification(request.getHeader(AdvancedAuthorizationFilter.JUSTIFICATION_HEADER))) {
                continue;
            }
            throw new IdentityAccessException(
                    "IAM_AUTHORIZATION_OBLIGATION_UNSATISFIED", "required authorization obligation is not satisfied");
        }
    }

    private static DomainIdentifier actor(HttpServletRequest request) {
        Object value = request.getAttribute(AuthenticatedActorContext.ACCOUNT_ATTRIBUTE);
        if (!(value instanceof DomainIdentifier actor)) {
            throw new IllegalStateException("authenticated actor missing after RBAC boundary");
        }
        return actor;
    }

    private static boolean validJustification(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.strip();
        return normalized.length() >= 8 && normalized.length() <= 500
                && normalized.chars().noneMatch(Character::isISOControl);
    }
}
