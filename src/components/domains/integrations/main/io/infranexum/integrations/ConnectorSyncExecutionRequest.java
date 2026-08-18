package io.infranexum.integrations;

import java.util.Objects;
import java.util.Set;

/** Bounded execution request evaluated against ConnectorGovernancePolicy before any handler is called. */
public record ConnectorSyncExecutionRequest(
        ConnectorSyncDirection direction,
        Set<String> fields,
        boolean propagateDeletions,
        int maxBatches) {
    public ConnectorSyncExecutionRequest {
        Objects.requireNonNull(direction, "direction");
        ConnectorSyncPlanRequest normalized = new ConnectorSyncPlanRequest(direction, fields, propagateDeletions);
        fields = normalized.fields();
        if (maxBatches < 1 || maxBatches > 100) throw new IllegalArgumentException("maxBatches must be between 1 and 100");
    }

    public ConnectorSyncPlanRequest asPlanRequest() {
        return new ConnectorSyncPlanRequest(direction, fields, propagateDeletions);
    }
}
