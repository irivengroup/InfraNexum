package io.infranexum.integrations;

import java.util.List;
import java.util.Set;

/** Deterministic dry-run result. DENY is the default whenever policy and intent diverge. */
public record ConnectorSyncPlan(
        ConnectorKey connectorKey,
        String provider,
        ConnectorSyncDirection configuredDirection,
        ConnectorSyncDirection requestedDirection,
        Decision decision,
        boolean mutating,
        ConnectorRollbackStrategy rollbackStrategy,
        Set<String> fields,
        List<String> reasons) {
    public enum Decision { ALLOW, DENY }
}
