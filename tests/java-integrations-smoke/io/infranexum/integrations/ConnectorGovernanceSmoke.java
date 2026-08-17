package io.infranexum.integrations;

import java.util.Set;

public final class ConnectorGovernanceSmoke {
    private ConnectorGovernanceSmoke() {}
    public static void main(String[] args) {
        ConnectorGovernancePolicy jira = ConnectorGovernancePolicy.externalFederatedRead(new ConnectorKey("jira-prod"), "jira-assets");
        ConnectorGovernancePlanner planner = new ConnectorGovernancePlanner();
        ConnectorSyncPlan read = planner.plan(jira, new ConnectorSyncPlanRequest(ConnectorSyncDirection.FEDERATED_READ, Set.of(), false));
        if (read.decision() != ConnectorSyncPlan.Decision.ALLOW || read.mutating()) throw new IllegalStateException("federated-read plan must be allowed and non-mutating");
        ConnectorSyncPlan importAttempt = planner.plan(jira, new ConnectorSyncPlanRequest(ConnectorSyncDirection.INBOUND, Set.of("name"), true));
        if (importAttempt.decision() != ConnectorSyncPlan.Decision.DENY || importAttempt.reasons().size() < 2) throw new IllegalStateException("mutating plan must fail closed");
        System.out.println("connector-governance-smoke: PASS");
    }
}
