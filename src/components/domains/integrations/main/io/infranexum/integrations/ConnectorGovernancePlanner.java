package io.infranexum.integrations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure planner enforcing connector authority, direction, deletion and rollback contracts. */
public final class ConnectorGovernancePlanner {
    public ConnectorSyncPlan plan(ConnectorGovernancePolicy policy, ConnectorSyncPlanRequest request) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(request, "request");
        List<String> reasons = new ArrayList<>();
        if (request.direction() != policy.direction()) {
            reasons.add("requested direction is not enabled by connector policy");
        }
        Set<String> governed = new HashSet<>();
        policy.fields().forEach(field -> governed.add(field.field()));
        if (request.direction().mutating()) {
            if (request.fields().isEmpty()) reasons.add("mutating synchronization requires explicit fields");
            for (String field : request.fields()) {
                if (!governed.contains(field)) reasons.add("field is not governed: " + field);
            }
            if (policy.rollbackStrategy() == ConnectorRollbackStrategy.NONE_REQUIRED) {
                reasons.add("mutating synchronization has no rollback strategy");
            }
        } else if (!request.fields().isEmpty()) {
            reasons.add("federated read does not accept local field mappings");
        }
        if (request.propagateDeletions() && policy.deletionPolicy() == ConnectorDeletionPolicy.IGNORE) {
            reasons.add("deletion propagation is disabled by connector policy");
        }
        return new ConnectorSyncPlan(
                policy.connectorKey(),
                policy.provider(),
                policy.direction(),
                request.direction(),
                reasons.isEmpty() ? ConnectorSyncPlan.Decision.ALLOW : ConnectorSyncPlan.Decision.DENY,
                request.direction().mutating(),
                policy.rollbackStrategy(),
                request.fields(),
                List.copyOf(reasons));
    }
}
