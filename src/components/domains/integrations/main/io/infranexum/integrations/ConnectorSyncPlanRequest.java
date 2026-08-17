package io.infranexum.integrations;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Operator-requested synchronization intent. The planner never executes mutations. */
public record ConnectorSyncPlanRequest(
        ConnectorSyncDirection direction,
        Set<String> fields,
        boolean propagateDeletions) {
    public ConnectorSyncPlanRequest {
        Objects.requireNonNull(direction, "direction");
        Set<String> normalized = new LinkedHashSet<>();
        for (String field : Objects.requireNonNullElse(fields, Set.<String>of())) {
            normalized.add(new ConnectorFieldAuthority(field, ConnectorDataAuthority.MANUAL).field());
        }
        if (normalized.size() > 512) throw new IllegalArgumentException("connector sync plan supports at most 512 fields");
        fields = Set.copyOf(normalized);
    }
}
