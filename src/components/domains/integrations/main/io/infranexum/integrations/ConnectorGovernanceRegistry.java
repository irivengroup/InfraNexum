package io.infranexum.integrations;

import java.util.List;

/** Read-only registry of connector governance policies. */
public interface ConnectorGovernanceRegistry {
    List<ConnectorGovernancePolicy> policies();
    ConnectorGovernancePolicy require(ConnectorKey connectorKey);
}
