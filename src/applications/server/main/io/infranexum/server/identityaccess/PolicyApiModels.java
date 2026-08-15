package io.infranexum.server.identityaccess;

import io.infranexum.identity.access.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Stable JSON contracts for the PGM-03-E04 PAP/PDP surface. */
final class PolicyApiModels {
    private PolicyApiModels() {}

    record ConditionRequest(
            @NotNull PolicyAttributeSource source,
            @NotBlank @Size(max = 64) String attribute,
            @NotNull PolicyOperator operator,
            @NotBlank @Size(max = 256) String expectedValue) {
        PolicyCondition toDomain() { return new PolicyCondition(source, attribute, operator, expectedValue); }
    }

    record RuleRequest(
            @NotNull PolicyEffect effect,
            @NotBlank @Size(max = 128) String action,
            @NotBlank @Size(max = 80) String resourceType,
            @NotEmpty @Size(max = 32) List<@Valid ConditionRequest> conditions,
            @Size(max = 8) Set<PolicyObligation> obligations,
            @Size(max = 500) String advice) {
        PolicyRuleDefinition toDomain() {
            return new PolicyRuleDefinition(effect, action, resourceType, conditions.stream().map(ConditionRequest::toDomain).toList(),
                    obligations == null ? Set.of() : obligations, advice == null ? "" : advice);
        }
    }

    record SodRequest(
            @NotBlank String firstRoleId,
            @NotBlank String secondRoleId,
            @NotBlank @Size(max = 500) String reason) {
        SeparationOfDutyDefinition toDomain() {
            return new SeparationOfDutyDefinition(id(firstRoleId), id(secondRoleId), reason);
        }
    }

    record CreatePolicyRequest(
            String organizationId,
            @NotBlank @Size(max = 128) String code,
            @NotBlank @Size(max = 500) String purpose,
            @Min(0) @Max(10_000) int priority,
            @NotNull ScopeKind scopeKind,
            String subdivisionId,
            @Size(max = 80) String effectiveFrom,
            @NotEmpty @Size(max = 256) List<@Valid RuleRequest> rules,
            @Size(max = 128) List<@Valid SodRequest> sodConstraints,
            @NotBlank @Size(max = 1024) String reason) {}

    record LifecycleRequest(@NotBlank @Size(max = 1024) String reason) {}

    record DecisionRequest(
            @NotBlank String subjectId,
            @NotBlank @Size(max = 128) String action,
            @NotBlank @Size(max = 80) String resourceType,
            @NotBlank @Size(max = 512) String resourceId,
            @NotNull ScopeKind scopeKind,
            String organizationId,
            String subdivisionId,
            @Size(max = 128) String requestedPolicyVersion) {}

    record PolicyResponse(
            String id,
            String organizationId,
            String code,
            long version,
            String ownerId,
            String purpose,
            int priority,
            String scopeKind,
            String subdivisionId,
            String state,
            Instant effectiveFrom,
            String approvedBy,
            Instant approvedAt,
            Instant activatedAt,
            Instant deprecatedAt,
            Instant retiredAt,
            Instant createdAt,
            Instant updatedAt,
            int ruleCount) {
        static PolicyResponse from(AccessPolicy policy) {
            return new PolicyResponse(policy.id().toString(), text(policy.organizationId()), policy.code(), policy.version(),
                    policy.ownerId().toString(), policy.purpose(), policy.priority(), policy.scope().kind().name(),
                    text(policy.scope().subdivisionId()), policy.state().name(), policy.effectiveFrom(), text(policy.approvedBy()),
                    policy.approvedAt(), policy.activatedAt(), policy.deprecatedAt(), policy.retiredAt(), policy.createdAt(),
                    policy.updatedAt(), policy.rules().size());
        }
    }

    record DecisionResponse(
            String decision,
            String reasonCode,
            Set<String> obligations,
            String policyVersion,
            String decisionId,
            Instant expiresAt) {
        static DecisionResponse from(PolicyEvaluationResult result) {
            return new DecisionResponse(result.decision().name().toLowerCase(java.util.Locale.ROOT), result.reasonCode(),
                    result.obligations().stream().map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    result.policyVersion(), result.decisionId().toString(), result.expiresAt());
        }
    }

    record ExplainResponse(
            String decision,
            String reasonCode,
            Set<String> obligations,
            List<String> advice,
            String policyVersion,
            String decisionId,
            Instant expiresAt,
            List<String> matchedPolicies) {
        static ExplainResponse from(PolicyEvaluationResult result) {
            return new ExplainResponse(result.decision().name().toLowerCase(java.util.Locale.ROOT), result.reasonCode(),
                    result.obligations().stream().map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    result.advice(), result.policyVersion(), result.decisionId().toString(), result.expiresAt(),
                    result.matchedPolicies());
        }
    }

    static AuthorizationScope scope(ScopeKind kind, String organizationId, String subdivisionId) {
        return switch (kind) {
            case PLATFORM -> AuthorizationScope.platform();
            case ORGANIZATION -> AuthorizationScope.organization(idRequired(organizationId, "organizationId"));
            case SUBDIVISION -> AuthorizationScope.subdivision(
                    idRequired(organizationId, "organizationId"), idRequired(subdivisionId, "subdivisionId"));
        };
    }

    static io.infranexum.core.contracts.DomainIdentifier id(String value) { return io.infranexum.core.contracts.DomainIdentifier.parse(value); }
    static io.infranexum.core.contracts.DomainIdentifier nullableId(String value) { return value == null || value.isBlank() ? null : id(value); }
    private static io.infranexum.core.contracts.DomainIdentifier idRequired(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required for the requested scope");
        return id(value);
    }
    private static String text(io.infranexum.core.contracts.DomainIdentifier value) { return value == null ? null : value.toString(); }
}
