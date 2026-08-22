package io.infranexum.server.integrations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.infranexum.adapters.jiraassets.JiraAssetsMutationSettings;
import io.infranexum.adapters.jiraassets.JiraAssetsSettings;
import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.integrations.ConnectorConflictStrategy;
import io.infranexum.integrations.ConnectorDataAuthority;
import io.infranexum.integrations.ConnectorDeletionPolicy;
import io.infranexum.integrations.ConnectorFieldAuthority;
import io.infranexum.integrations.ConnectorGovernancePolicy;
import io.infranexum.integrations.ConnectorGovernanceRegistry;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorRollbackStrategy;
import io.infranexum.integrations.ConnectorSyncDirection;
import io.infranexum.server.persistence.JdbcIsolation;
import io.infranexum.server.persistence.PersistenceMode;
import io.infranexum.server.persistence.PersistenceRuntimeProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Admission regressions for the first explicitly governed Jira Assets mutating handler. */
class ConfiguredJiraAssetsSyncHandlerCatalogTest {
    private static final ConnectorKey KEY = new ConnectorKey("jira-prod");
    private static final JiraAssetsMutationSettings MUTATION = new JiraAssetsMutationSettings(
            KEY, "23", "InfraNexum ID", "id", Map.of("id", "135", "asset_type", "144"), 50);

    @Test
    void registersOnlyWhenProviderMappingAndGovernanceMatchExactly() {
        ConfiguredJiraAssetsSyncHandlerCatalog catalog = new ConfiguredJiraAssetsSyncHandlerCatalog(
                Map.of(KEY, MUTATION), connectors(), governance(policy(true, List.of(
                        field("id", ConnectorDataAuthority.INFRANEXUM),
                        field("asset_type", ConnectorDataAuthority.INFRANEXUM)))),
                mock(DataSource.class), persistence(PersistenceMode.POSTGRESQL));

        assertEquals(1, catalog.handlers().size());
        assertEquals(KEY, catalog.handlers().getFirst().connectorKey());

        assertThrows(ConfigurationException.class, () -> new ConfiguredJiraAssetsSyncHandlerCatalog(
                Map.of(KEY, MUTATION), connectors(), governance(policy(false, List.of(
                        field("id", ConnectorDataAuthority.INFRANEXUM),
                        field("asset_type", ConnectorDataAuthority.INFRANEXUM)))),
                mock(DataSource.class), persistence(PersistenceMode.POSTGRESQL)));
        assertThrows(ConfigurationException.class, () -> new ConfiguredJiraAssetsSyncHandlerCatalog(
                Map.of(KEY, MUTATION), connectors(), governance(policy(true, List.of(
                        field("id", ConnectorDataAuthority.INFRANEXUM)))),
                mock(DataSource.class), persistence(PersistenceMode.POSTGRESQL)));
        assertThrows(ConfigurationException.class, () -> new ConfiguredJiraAssetsSyncHandlerCatalog(
                Map.of(KEY, MUTATION), connectors(), governance(policy(true, List.of(
                        field("id", ConnectorDataAuthority.INFRANEXUM),
                        field("asset_type", ConnectorDataAuthority.MANUAL)))),
                mock(DataSource.class), persistence(PersistenceMode.POSTGRESQL)));
        assertThrows(ConfigurationException.class, () -> new ConfiguredJiraAssetsSyncHandlerCatalog(
                Map.of(KEY, MUTATION), connectors(), governance(policy(true, List.of(
                        field("id", ConnectorDataAuthority.INFRANEXUM),
                        field("asset_type", ConnectorDataAuthority.INFRANEXUM)))),
                mock(DataSource.class), persistence(PersistenceMode.MEMORY)));
    }

    @Test
    void noMutationMappingKeepsProviderReadOnlyAndDoesNotRequireDurablePersistence() {
        ConfiguredJiraAssetsSyncHandlerCatalog catalog = new ConfiguredJiraAssetsSyncHandlerCatalog(
                Map.of(), connectors(), governance(policy(true, List.of(
                        field("id", ConnectorDataAuthority.INFRANEXUM),
                        field("asset_type", ConnectorDataAuthority.INFRANEXUM)))),
                mock(DataSource.class), persistence(PersistenceMode.MEMORY));
        assertTrue(catalog.handlers().isEmpty());
    }

    private static ConfiguredJiraAssetsConnectorRegistry connectors() {
        JiraAssetsSettings settings = new JiraAssetsSettings(
                KEY, "cloud", "workspace", "env:JIRA_TOKEN", Duration.ofSeconds(5), true);
        return new ConfiguredJiraAssetsConnectorRegistry(
                Map.of(KEY, settings), request -> new io.infranexum.adapters.jiraassets.JiraAssetsTransport.Response(
                        200, Map.of(), "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                reference -> "abcdefghijklmnopqrstuvwxyz0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new ObjectMapper());
    }

    private static ConnectorGovernanceRegistry governance(ConnectorGovernancePolicy policy) {
        return new ConnectorGovernanceRegistry() {
            @Override public List<ConnectorGovernancePolicy> policies() { return List.of(policy); }
            @Override public ConnectorGovernancePolicy require(ConnectorKey key) {
                if (!policy.connectorKey().equals(key)) throw new IllegalArgumentException("unexpected connector");
                return policy;
            }
        };
    }

    private static ConnectorGovernancePolicy policy(boolean enabled, List<ConnectorFieldAuthority> fields) {
        return new ConnectorGovernancePolicy(
                KEY, "jira-assets", ConnectorSyncDirection.OUTBOUND, ConnectorDataAuthority.INFRANEXUM,
                ConnectorConflictStrategy.PREFER_AUTHORITY, ConnectorDeletionPolicy.IGNORE,
                ConnectorRollbackStrategy.MANUAL, enabled, fields);
    }

    private static ConnectorFieldAuthority field(String name, ConnectorDataAuthority authority) {
        return new ConnectorFieldAuthority(name, authority);
    }

    private static PersistenceRuntimeProperties persistence(PersistenceMode mode) {
        return new PersistenceRuntimeProperties(mode, JdbcIsolation.READ_COMMITTED);
    }
}
