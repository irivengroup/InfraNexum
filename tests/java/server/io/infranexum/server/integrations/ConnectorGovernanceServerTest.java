package io.infranexum.server.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.infranexum.adapters.jiraassets.JiraAssetsSettings;
import io.infranexum.adapters.servicenow.ServiceNowSettings;
import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.integrations.ConnectorConflictStrategy;
import io.infranexum.integrations.ConnectorDataAuthority;
import io.infranexum.integrations.ConnectorDeletionPolicy;
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
                Map.of(jiraKey, jira(jiraKey, true)),
                Map.of(serviceNowKey, serviceNow(serviceNowKey, true)));

        assertEquals(2, registry.policies().size());
        assertEquals("cmdb-prod", registry.policies().get(0).connectorKey().value());
        var jira = registry.require(jiraKey);
        assertEquals("jira-assets", jira.provider());
        assertEquals(ConnectorSyncDirection.FEDERATED_READ, jira.direction());
        assertEquals(ConnectorDataAuthority.EXTERNAL, jira.authority());
        assertEquals(ConnectorRollbackStrategy.NONE_REQUIRED, jira.rollbackStrategy());
        assertFalse(jira.executionEnabled());
    }

    @Test
    void explicitMutatingAuthorityMappingCanBePreparedWithoutExecution() {
        ConnectorKey key = new ConnectorKey("jira-prod");
        var registry = new ConfiguredConnectorGovernanceRegistry(
                Map.of(key, jira(key, true)), Map.of(),
                Map.of(key, governance(false)));

        var policy = registry.require(key);
        assertEquals("jira-assets", policy.provider());
        assertEquals(ConnectorSyncDirection.INBOUND, policy.direction());
        assertEquals(ConnectorRollbackStrategy.LOCAL_CHECKPOINT, policy.rollbackStrategy());
        assertEquals("name", policy.fields().getFirst().field());
        assertFalse(policy.executionEnabled());
        assertFalse(ConnectorGovernanceController.GovernancePolicyResponse.from(policy).executionEnabled());
    }

    @Test
    void governanceOverrideMustReferenceKnownAndEnabledProviderBeforeExecution() {
        ConnectorKey unknown = new ConnectorKey("unknown-provider");
        assertThrows(ConfigurationException.class, () -> new ConfiguredConnectorGovernanceRegistry(
                Map.of(), Map.of(), Map.of(unknown, governance(false))));

        ConnectorKey disabled = new ConnectorKey("jira-disabled");
        assertThrows(ConfigurationException.class, () -> new ConfiguredConnectorGovernanceRegistry(
                Map.of(disabled, jira(disabled, false)), Map.of(), Map.of(disabled, governance(true))));
    }

    @Test
    void connectorKeysAreGloballyUniqueAcrossProviderGovernance() {
        ConnectorKey key = new ConnectorKey("shared-key");
        assertThrows(ConfigurationException.class,
                () -> new ConfiguredConnectorGovernanceRegistry(
                        Map.of(key, jira(key, true)), Map.of(key, serviceNow(key, true))));
    }

    @Test
    void unknownGovernancePolicyFailsClosed() {
        var registry = new ConfiguredConnectorGovernanceRegistry(Map.of(), Map.of());
        assertThrows(ConnectorGovernanceNotFoundException.class, () -> registry.require(new ConnectorKey("missing")));
    }

    private static IntegrationRuntimeProperties.GovernanceProperties governance(boolean executionEnabled) {
        return new IntegrationRuntimeProperties.GovernanceProperties(
                ConnectorSyncDirection.INBOUND,
                ConnectorDataAuthority.EXTERNAL,
                ConnectorConflictStrategy.PREFER_AUTHORITY,
                ConnectorDeletionPolicy.IGNORE,
                ConnectorRollbackStrategy.LOCAL_CHECKPOINT,
                executionEnabled,
                Map.of("name", ConnectorDataAuthority.EXTERNAL));
    }

    private static JiraAssetsSettings jira(ConnectorKey key, boolean enabled) {
        return new JiraAssetsSettings(
                key, "cloud-1", "workspace-1", "env:JIRA_TOKEN", Duration.ofSeconds(15), enabled);
    }

    private static ServiceNowSettings serviceNow(ConnectorKey key, boolean enabled) {
        return new ServiceNowSettings(
                key, "acme.service-now.com", "cmdb_ci", "file:/run/secrets/sn", Duration.ofSeconds(15), enabled);
    }
}
