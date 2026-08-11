package io.infranexum.server.platform;

import io.infranexum.core.capabilities.CapabilityDecision;
import java.time.Instant;
import java.util.List;

/** Public, secret-free projection of one capability decision. */
public record CapabilityDecisionResponse(
        String capabilityCode,
        boolean available,
        String reasonCode,
        String profile,
        String topology,
        List<String> roles,
        List<String> traits,
        String dependencyStatus,
        String activationState,
        String catalogVersion,
        String capabilityHash,
        Instant evaluatedAt,
        long profileVersion) {
    static CapabilityDecisionResponse from(CapabilityDecision decision) {
        return new CapabilityDecisionResponse(
                decision.capabilityCode().value(),
                decision.available(),
                decision.reasonCode().name(),
                decision.profile().name(),
                decision.topology().code(),
                decision.roles().stream().map(Enum::name).sorted().toList(),
                decision.traits().stream().map(trait -> trait.code()).sorted().toList(),
                decision.dependencyStatus().name(),
                decision.activationState().name(),
                decision.catalogVersion(),
                decision.capabilityHash(),
                decision.evaluatedAt(),
                decision.profileVersion());
    }
}
