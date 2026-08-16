package io.infranexum.server.identityaccess;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.application.PolicyDecisionService;
import io.infranexum.identity.access.domain.PolicyEvaluationRequest;
import io.infranexum.identity.access.domain.PolicyEvaluationResult;
import io.infranexum.identity.access.domain.PolicyObligation;
import io.infranexum.identity.access.ports.IdentityAccessFeaturePolicy;
import io.infranexum.server.http.ApiProblemSupport;
import io.infranexum.server.identity.LocalAuthenticationFilter;
import io.infranexum.server.observability.CorrelationContext;
import io.infranexum.server.platform.PlatformCapabilityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/** Advanced PEP applying ABAC after RBAC; every non-PERMIT decision is fail-closed. */
public final class AdvancedAuthorizationFilter extends OncePerRequestFilter implements Ordered {
    public static final int ORDER = RbacAuthorizationFilter.ORDER + 10;
    public static final String DECISION_ATTRIBUTE = "io.infranexum.authorization.abac.decision";
    public static final String DECISION_HEADER = "X-InfraNexum-Decision-Id";
    public static final String JUSTIFICATION_HEADER = "X-InfraNexum-Justification";
    private static final String API_PREFIX = "/api/v1/";
    private static final String AUTH_PREFIX = "/api/v1/iam/local-auth";
    private static final String PUBLIC_BUILD_PATH = "/api/v1/system/build";

    private final PolicyDecisionService decisions;
    private final IdentityAccessFeaturePolicy features;
    private final PlatformCapabilityService capabilities;
    private final ApiProblemSupport problems;

    public AdvancedAuthorizationFilter(
            PolicyDecisionService decisions,
            IdentityAccessFeaturePolicy features,
            PlatformCapabilityService capabilities,
            ApiProblemSupport problems) {
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.features = Objects.requireNonNull(features, "features");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @Override public int getOrder() { return ORDER; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !features.supportsAdvancedAuthorization() || !path.startsWith(API_PREFIX)
                || path.equals(AUTH_PREFIX) || path.startsWith(AUTH_PREFIX + "/") || PUBLIC_BUILD_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object actorValue = request.getAttribute(LocalAuthenticationFilter.ACCOUNT_ATTRIBUTE);
        Object requirementValue = request.getAttribute(RbacAuthorizationFilter.REQUIREMENT_ATTRIBUTE);
        DomainIdentifier correlation = CorrelationContext.identifier(request).orElse(null);
        if (!(actorValue instanceof DomainIdentifier actor) || !(requirementValue instanceof AuthorizationRequirement requirement)
                || correlation == null) {
            reject(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "INFRANEXUM_ADVANCED_AUTHORIZATION_CONTEXT_MISSING", "Advanced authorization context is incomplete");
            return;
        }
        if (requirement.type() == AuthorizationRequirement.Type.CONTROLLER_SCOPED) {
            chain.doFilter(request, response);
            return;
        }
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("channel", "HTTP");
        environment.put("method", request.getMethod().toUpperCase(java.util.Locale.ROOT));
        environment.put("justification_present", Boolean.toString(validJustification(request.getHeader(JUSTIFICATION_HEADER))));
        String capabilityVersion = capabilities.snapshot().catalogVersion() + ":" + capabilities.snapshot().profileVersion();
        PolicyEvaluationRequest evaluation = new PolicyEvaluationRequest(
                actor, action(requirement), requirement.targetType(), requirement.targetId(), requirement.scope(), environment,
                "LOCAL_SESSION", capabilityVersion, null, true);
        PolicyEvaluationResult decision = decisions.decide(evaluation, correlation, "HTTP");
        request.setAttribute(DECISION_ATTRIBUTE, decision);
        response.setHeader(DECISION_HEADER, decision.decisionId().toString());
        if (!decision.permitted()) {
            reject(request, response, HttpServletResponse.SC_FORBIDDEN,
                    "INFRANEXUM_ADVANCED_AUTHORIZATION_DENIED", decision.reasonCode());
            return;
        }
        if (!obligationsSatisfied(request, decision)) {
            reject(request, response, HttpServletResponse.SC_FORBIDDEN,
                    "INFRANEXUM_AUTHORIZATION_OBLIGATION_UNSATISFIED", "Required authorization obligation is not satisfied");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String action(AuthorizationRequirement requirement) {
        if (requirement.permissionCode() != null && !requirement.permissionCode().isBlank()) return requirement.permissionCode();
        return switch (requirement.type()) {
            case ORGANIZATION_VISIBILITY -> "organization.visibility";
            case PLATFORM_ADMINISTRATOR -> "system.platform_admin";
            case GROUP_PERMISSION -> "iam.group.read";
            case CONTROLLER_SCOPED -> throw new IllegalStateException("controller-scoped requirement must be resolved before ABAC evaluation");
            case PERMISSION -> throw new IllegalStateException("permission requirement is missing its permission code");
            case UNREGISTERED -> "api.unregistered";
        };
    }

    private static boolean obligationsSatisfied(HttpServletRequest request, PolicyEvaluationResult decision) {
        for (PolicyObligation obligation : decision.obligations()) {
            if (obligation == PolicyObligation.REQUIRE_JUSTIFICATION) {
                if (!validJustification(request.getHeader(JUSTIFICATION_HEADER))) return false;
                continue;
            }
            // MFA step-up, approval workflows and response-field controls require dedicated enforcement mechanisms.
            // Until those mechanisms are present, the PEP must refuse rather than silently ignore the obligation.
            return false;
        }
        return true;
    }

    private static boolean validJustification(String value) {
        if (value == null) return false;
        String normalized = value.strip();
        return normalized.length() >= 8 && normalized.length() <= 500
                && normalized.chars().noneMatch(Character::isISOControl);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, int status, String code, String detail)
            throws IOException {
        HttpStatus httpStatus = HttpStatus.valueOf(status);
        problems.write(response, problems.problem(
                httpStatus, code, "Authorization denied", detail, Map.of(), Map.of(), request));
    }

}
