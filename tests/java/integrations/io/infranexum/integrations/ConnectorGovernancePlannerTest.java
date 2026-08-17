package io.infranexum.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ConnectorGovernancePlannerTest {
    private final ConnectorGovernancePlanner planner = new ConnectorGovernancePlanner();

    @Test
    void federatedReadAllowsOnlyNonMutatingIntent() {
        ConnectorGovernancePolicy policy = ConnectorGovernancePolicy.externalFederatedRead(new ConnectorKey("jira-prod"), "jira-assets");
        ConnectorSyncPlan allowed = planner.plan(policy, new ConnectorSyncPlanRequest(ConnectorSyncDirection.FEDERATED_READ, Set.of(), false));
        assertEquals(ConnectorSyncPlan.Decision.ALLOW, allowed.decision());
        assertEquals(ConnectorRollbackStrategy.NONE_REQUIRED, allowed.rollbackStrategy());

        ConnectorSyncPlan denied = planner.plan(policy, new ConnectorSyncPlanRequest(ConnectorSyncDirection.INBOUND, Set.of("name"), false));
        assertEquals(ConnectorSyncPlan.Decision.DENY, denied.decision());
    }

    @Test
    void mutatingPolicyRequiresFieldAuthorityAndRollback() {
        ConnectorKey key = new ConnectorKey("future-import");
        assertThrows(IllegalArgumentException.class, () -> new ConnectorGovernancePolicy(
                key, "future-provider", ConnectorSyncDirection.INBOUND, ConnectorDataAuthority.EXTERNAL,
                ConnectorConflictStrategy.PREFER_AUTHORITY, ConnectorDeletionPolicy.TOMBSTONE,
                ConnectorRollbackStrategy.NONE_REQUIRED, List.of(new ConnectorFieldAuthority("name", ConnectorDataAuthority.EXTERNAL))));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorGovernancePolicy(
                key, "future-provider", ConnectorSyncDirection.INBOUND, ConnectorDataAuthority.EXTERNAL,
                ConnectorConflictStrategy.PREFER_AUTHORITY, ConnectorDeletionPolicy.TOMBSTONE,
                ConnectorRollbackStrategy.LOCAL_CHECKPOINT, List.of()));
    }

    @Test
    void plannerRejectsUnknownFieldsAndDisabledDeletionPropagation() {
        ConnectorGovernancePolicy policy = new ConnectorGovernancePolicy(
                new ConnectorKey("future-import"), "future-provider", ConnectorSyncDirection.INBOUND,
                ConnectorDataAuthority.EXTERNAL, ConnectorConflictStrategy.PREFER_AUTHORITY,
                ConnectorDeletionPolicy.IGNORE, ConnectorRollbackStrategy.LOCAL_CHECKPOINT,
                List.of(new ConnectorFieldAuthority("name", ConnectorDataAuthority.EXTERNAL)));
        ConnectorSyncPlan plan = planner.plan(policy,
                new ConnectorSyncPlanRequest(ConnectorSyncDirection.INBOUND, Set.of("name", "serial"), true));
        assertEquals(ConnectorSyncPlan.Decision.DENY, plan.decision());
        assertEquals(2, plan.reasons().size());
    }
}
