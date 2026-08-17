package io.infranexum.server.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.infranexum.adapters.jiraassets.JiraAssetsSettings;
import io.infranexum.adapters.servicenow.ServiceNowSettings;
import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.integrations.ConnectorDataAuthority;
import io.infranexum.integrations.ConnectorGovernanceNotFoundException;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorRollbackStrategy;
import io.infranexum.integrations.ConnectorSyncDirection;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ConnectorGovernanceServerTest {
    @Test
    void registryBuildsDeterministicPoliciesForConfiguredProviders() {
        ConnectorKey jiraKey = new ConnectorKey("jira-prod");
        ConnectorKey serviceNowKey = new ConnectorKey("cmdb-prod");
        var registry = new ConfiguredConnectorGovernanceRegistry(
                Map.of(jiraKey, new JiraAssetsSettings(jiraKey, "cloud-1", "workspace-1", "env:JIRA_TOKEN", Duration.ofSeconds(15), true)),
                Map.of(serviceNowKey, new ServiceNowSettings(serviceNowKey, "acme.service-now.com", "cmdb_ci", "file:/run/secrets/sn", Duration.ofSeconds(15), true)));

        assertEquals(2, registry.policies().size());
        assertEquals("cmdb-prod", registry.policies().get(0).connectorKey().value());
        var jira = registry.require(jiraKey);
        assertEquals("jira-assets", jira.provider());
        assertEquals(ConnectorSyncDirection.FEDERATED_READ, jira.direction());
        assertEquals(ConnectorDataAuthority.EXTERNAL, jira.authority());
        assertEquals(ConnectorRollbackStrategy.NONE_REQUIRED, jira.rollbackStrategy());
    }

    @Test
    void connectorKeysAreGloballyUniqueAcrossProviderGovernance() {
        ConnectorKey key = new ConnectorKey("shared-key");
        var jira = new JiraAssetsSettings(key, "cloud-1", "workspace-1", "env:JIRA_TOKEN", Duration.ofSeconds(15), true);
        var serviceNow = new ServiceNowSettings(key, "acme.service-now.com", "cmdb_ci", "env:SN_TOKEN", Duration.ofSeconds(15), true);
        assertThrows(ConfigurationException.class,
                () -> new ConfiguredConnectorGovernanceRegistry(Map.of(key, jira), Map.of(key, serviceNow)));
    }

    @Test
    void unknownGovernancePolicyFailsClosed() {
        var registry = new ConfiguredConnectorGovernanceRegistry(Map.of(), Map.of());
        assertThrows(ConnectorGovernanceNotFoundException.class, () -> registry.require(new ConnectorKey("missing")));
    }
}
