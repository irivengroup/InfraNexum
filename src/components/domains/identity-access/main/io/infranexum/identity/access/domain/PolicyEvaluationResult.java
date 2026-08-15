package io.infranexum.identity.access.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Explainable decision returned by the PDP without exposing raw authoritative attributes. */
public record PolicyEvaluationResult(
        PolicyDecision decision,
        String reasonCode,
        Set<PolicyObligation> obligations,
        List<String> advice,
        String policyVersion,
        DomainIdentifier decisionId,
        Instant expiresAt,
        List<String> matchedPolicies) {
    public PolicyEvaluationResult {
        Objects.requireNonNull(decision, "decision");
        reasonCode = PolicyCondition.bounded(reasonCode, "reasonCode", 128);
        obligations = Set.copyOf(Objects.requireNonNull(obligations, "obligations"));
        advice = List.copyOf(Objects.requireNonNull(advice, "advice"));
        policyVersion = PolicyCondition.bounded(policyVersion, "policyVersion", 128);
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        matchedPolicies = List.copyOf(Objects.requireNonNull(matchedPolicies, "matchedPolicies"));
    }

    public boolean permitted() { return decision == PolicyDecision.PERMIT; }
}
