package io.infranexum.server.platform;

import io.infranexum.core.capabilities.CapabilitySnapshot;
import java.time.Instant;
import java.util.List;

/** Public registry snapshot consumed by Web, CLI and documentation filters. */
public record CapabilitySnapshotResponse(
        String catalogVersion,
        long profileVersion,
        String capabilityHash,
        Instant evaluatedAt,
        List<CapabilityDecisionResponse> capabilities) {
    static CapabilitySnapshotResponse from(CapabilitySnapshot snapshot) {
        return new CapabilitySnapshotResponse(
                snapshot.catalogVersion(),
                snapshot.profileVersion(),
                snapshot.capabilityHash(),
                snapshot.evaluatedAt(),
                snapshot.decisions().values().stream()
                        .sorted(java.util.Comparator.comparing(item -> item.capabilityCode().value()))
                        .map(CapabilityDecisionResponse::from)
                        .toList());
    }
}
