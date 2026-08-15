package io.infranexum.server.identityaccess;

import static io.infranexum.server.identityaccess.PolicyApiModels.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.application.IdentityAccessCommandContext;
import io.infranexum.identity.access.application.PolicyAdministrationService;
import io.infranexum.identity.access.application.PolicyDecisionService;
import io.infranexum.identity.access.application.RbacAuthorizationService;
import io.infranexum.identity.access.domain.Role;
import io.infranexum.identity.access.domain.PolicyEvaluationRequest;
import io.infranexum.server.configuration.ServerTemporalInputParser;
import io.infranexum.server.identity.LocalAuthenticationFilter;
import io.infranexum.server.observability.CorrelationContext;
import io.infranexum.server.platform.PlatformCapabilityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Normative PGM-03-E04 PAP/PDP HTTP adapter. */
@RestController
public final class PolicyController {
    private final PolicyAdministrationService administration;
    private final PolicyDecisionService decisions;
    private final RbacAuthorizationService rbac;
    private final PlatformCapabilityService capabilities;
    private final ServerTemporalInputParser temporal;

    public PolicyController(
            PolicyAdministrationService administration,
            PolicyDecisionService decisions,
            RbacAuthorizationService rbac,
            PlatformCapabilityService capabilities,
            ServerTemporalInputParser temporal) {
        this.administration = Objects.requireNonNull(administration, "administration");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.rbac = Objects.requireNonNull(rbac, "rbac");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.temporal = Objects.requireNonNull(temporal, "temporal");
    }

    @GetMapping("/api/v1/iam/policies")
    List<PolicyResponse> list(
            @RequestParam(required = false) String organizationId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        return administration.listPolicies(nullableId(organizationId), offset, limit).stream()
                .map(PolicyResponse::from)
                .toList();
    }

    @PostMapping("/api/v1/iam/policies")
    ResponseEntity<PolicyResponse> create(@Valid @RequestBody CreatePolicyRequest body, HttpServletRequest request) {
        var scope = scope(body.scopeKind(), body.organizationId(), body.subdivisionId());
        var policy = administration.createPolicy(nullableId(body.organizationId()), body.code(), body.purpose(), body.priority(),
                scope, temporal.optionalInstant(body.effectiveFrom(), "effectiveFrom"), body.rules().stream().map(RuleRequest::toDomain).toList(),
                body.sodConstraints() == null ? java.util.List.of() : body.sodConstraints().stream().map(SodRequest::toDomain).toList(),
                context(request, body.reason()));
        return ResponseEntity.status(HttpStatus.CREATED).body(PolicyResponse.from(policy));
    }

    @PostMapping("/api/v1/iam/policies/{policyId}/validate")
    PolicyResponse validate(@PathVariable String policyId, @Valid @RequestBody LifecycleRequest body, HttpServletRequest request) {
        return PolicyResponse.from(administration.validatePolicy(id(policyId), context(request, body.reason())));
    }

    @PostMapping("/api/v1/iam/policies/{policyId}/approve")
    PolicyResponse approve(@PathVariable String policyId, @Valid @RequestBody LifecycleRequest body, HttpServletRequest request) {
        return PolicyResponse.from(administration.approvePolicy(id(policyId), context(request, body.reason())));
    }

    @PostMapping("/api/v1/iam/policies/{policyId}/activate")
    PolicyResponse activate(@PathVariable String policyId, @Valid @RequestBody LifecycleRequest body, HttpServletRequest request) {
        return PolicyResponse.from(administration.activatePolicy(id(policyId), context(request, body.reason())));
    }

    @PostMapping("/api/v1/iam/authorization/decisions")
    DecisionResponse decide(@Valid @RequestBody DecisionRequest body, HttpServletRequest request) {
        return DecisionResponse.from(decisions.decide(evaluation(body, request), correlation(request), "HTTP-PDP"));
    }

    @PostMapping("/api/v1/iam/authorization/explain")
    ExplainResponse explain(@Valid @RequestBody DecisionRequest body, HttpServletRequest request) {
        // Explanation intentionally exposes policy identifiers/reasons only; trusted PIP attributes are never serialized.
        return ExplainResponse.from(decisions.decide(evaluation(body, request), correlation(request), "HTTP-EXPLAIN"));
    }

    private PolicyEvaluationRequest evaluation(DecisionRequest body, HttpServletRequest request) {
        Map<String, String> environment = Map.of(
                "channel", "HTTP_SIMULATION",
                "method", request.getMethod().toUpperCase(java.util.Locale.ROOT),
                "justification_present", Boolean.toString(validJustification(request.getHeader(AdvancedAuthorizationFilter.JUSTIFICATION_HEADER))));
        String capabilityVersion = capabilities.snapshot().catalogVersion() + ":" + capabilities.snapshot().profileVersion();
        DomainIdentifier subject = id(body.subjectId());
        var targetScope = scope(body.scopeKind(), body.organizationId(), body.subdivisionId());
        boolean rbacPermitted = Role.PLATFORM_ADMIN_CODE.equals(body.action())
                ? rbac.isPlatformAdministrator(subject)
                : rbac.evaluatePermission(subject, body.action(), targetScope, actor(request), correlation(request), "HTTP-PDP-BASELINE").allowed();
        return new PolicyEvaluationRequest(subject, body.action(), body.resourceType(), body.resourceId(), targetScope, environment,
                "LOCAL_SESSION", capabilityVersion, body.requestedPolicyVersion(), rbacPermitted);
    }

    private static boolean validJustification(String value) {
        if (value == null) return false;
        String normalized = value.strip();
        return normalized.length() >= 8 && normalized.length() <= 500 && normalized.chars().noneMatch(Character::isISOControl);
    }

    private static IdentityAccessCommandContext context(HttpServletRequest request, String reason) {
        return new IdentityAccessCommandContext(actor(request), correlation(request), reason, "HTTP");
    }

    private static DomainIdentifier actor(HttpServletRequest request) {
        Object value = request.getAttribute(LocalAuthenticationFilter.ACCOUNT_ATTRIBUTE);
        if (!(value instanceof DomainIdentifier actor)) throw new IllegalStateException("authenticated actor missing after authorization boundary");
        return actor;
    }

    private static DomainIdentifier correlation(HttpServletRequest request) {
        return CorrelationContext.identifier(request)
                .orElseThrow(() -> new IllegalStateException("correlation identifier missing after observability boundary"));
    }
}
