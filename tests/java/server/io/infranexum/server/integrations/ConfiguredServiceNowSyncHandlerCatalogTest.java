package io.infranexum.server.integrations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.infranexum.adapters.servicenow.ServiceNowMutationSettings;
import io.infranexum.adapters.servicenow.ServiceNowSettings;
import io.infranexum.adapters.servicenow.ServiceNowTombstoneSettings;
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

/** Admission regressions for governed ServiceNow CMDB outbound synchronization. */
class ConfiguredServiceNowSyncHandlerCatalogTest {
    private static final ConnectorKey KEY = new ConnectorKey("service-now-prod");
    private static final ServiceNowMutationSettings MUTATION = new ServiceNowMutationSettings(
            KEY, "id", Map.of("id", "u_infranexum_id", "asset_type", "u_asset_type"), 50);
    private static final ServiceNowMutationSettings TOMBSTONE_MUTATION = new ServiceNowMutationSettings(
            KEY, "id", Map.of("id", "u_infranexum_id", "asset_type", "u_asset_type"), 50,
            new ServiceNowTombstoneSettings("u_infranexum_state", "disposed"));

    @Test
    void registersOnlyWhenProviderMappingAndGovernanceMatchExactly() {
        ConfiguredServiceNowSyncHandlerCatalog catalog = new ConfiguredServiceNowSyncHandlerCatalog(
                Map.of(KEY, MUTATION), connectors(), governance(policy(true, List.of(
                        field("id", ConnectorDataAuthority.INFRANEXUM),
                        field("asset_type", ConnectorDataAuthority.INFRANEXUM)))),
                mock(DataSource.class), persistence(PersistenceMode.POSTGRESQL));

        assertEquals(1, catalog.handlers().size());
        assertEquals(KEY, catalog.handlers().getFirst().connectorKey());

        assertThrows(ConfigurationException.class, () -> new ConfiguredServiceNowSyncHandlerCatalog(
                Map.of(KEY, MUTATION), connectors(), governance(policy(false, List.of(
                        field("id", ConnectorDataAuthority.INFRANEXUM),
                        field("asset_type", ConnectorDataAuthority.INFRANEXUM)))),
                mock(DataSource.class), persistence(PersistenceMode.POSTGRESQL)));
        assertThrows(ConfigurationException.class, () -> new ConfiguredServiceNowSyncHandlerCatalog(
                Map.of(KEY, MUTATION), connectors(), governance(policy(true, List.of(
                        field("id", ConnectorDataAuthority.INFRANEXUM)))),
                mock(DataSource.class), persistence(PersistenceMode.POSTGRESQL)));
        assertThrows(ConfigurationException.class, () -> new ConfiguredServiceNowSyncHandlerCatalog(
                Map.of(KEY, MUTATION), connectors(), governance(policy(true, List.of(
                        field("id", ConnectorDataAuthority.INFRANEXUM),
                        field("asset_type", ConnectorDataAuthority.MANUAL)))),
                mock(DataSource.class), persistence(PersistenceMode.POSTGRESQL)));
        assertThrows(ConfigurationException.class, () -> new ConfiguredServiceNowSyncHandlerCatalog(
                Map.of(KEY, MUTATION), connectors(), governance(policy(true, List.of(
                        field("id", ConnectorDataAuthority.INFRANEXUM),
                        field("asset_type", ConnectorDataAuthority.INFRANEXUM)))),
                mock(DataSource.class), persistence(PersistenceMode.MEMORY)));
    }

    @Test
    void noMutationMappingKeepsProviderReadOnlyAndDoesNotRequireDurablePersistence() {
        ConfiguredServiceNowSyncHandlerCatalog catalog = new ConfiguredServiceNowSyncHandlerCatalog(
                Map.of(), connectors(), governance(policy(true, List.of(
                        field("id", ConnectorDataAuthority.INFRANEXUM),
                        field("asset_type", ConnectorDataAuthority.INFRANEXUM)))),
                mock(DataSource.class), persistence(PersistenceMode.MEMORY));
        assertTrue(catalog.handlers().isEmpty());
    }

    @Test
    void connectorRegistryPreservesTypedConnectorKeysAcrossServerWiring() {
        ConfiguredServiceNowConnectorRegistry registry = connectors();

        assertEquals(KEY, registry.require(KEY).settings().connectorKey());
        assertEquals(KEY, registry.require(KEY.value()).settings().connectorKey());
        assertThrows(NullPointerException.class, () -> registry.require((ConnectorKey) null));
    }

    @Test
    void tombstoneAdmissionRequiresExactDeletionPolicyAndExplicitProviderMarker() {
        var fields = List.of(
                field("id", ConnectorDataAuthority.INFRANEXUM),
                field("asset_type", ConnectorDataAuthority.INFRANEXUM));
        ConfiguredServiceNowSyncHandlerCatalog catalog = new ConfiguredServiceNowSyncHandlerCatalog(
                Map.of(KEY, TOMBSTONE_MUTATION), connectors(),
                governance(policy(true, fields, ConnectorDeletionPolicy.TOMBSTONE)),
                mock(DataSource.class), persistence(PersistenceMode.POSTGRESQL));
        assertEquals(1, catalog.handlers().size());

        assertThrows(ConfigurationException.class, () -> new ConfiguredServiceNowSyncHandlerCatalog(
                Map.of(KEY, TOMBSTONE_MUTATION), connectors(), governance(policy(true, fields)),
                mock(DataSource.class), persistence(PersistenceMode.POSTGRESQL)));
        assertThrows(ConfigurationException.class, () -> new ConfiguredServiceNowSyncHandlerCatalog(
                Map.of(KEY, MUTATION), connectors(),
                governance(policy(true, fields, ConnectorDeletionPolicy.TOMBSTONE)),
                mock(DataSource.class), persistence(PersistenceMode.POSTGRESQL)));
        assertThrows(ConfigurationException.class, () -> new ConfiguredServiceNowSyncHandlerCatalog(
                Map.of(KEY, TOMBSTONE_MUTATION), connectors(),
                governance(policy(true, fields, ConnectorDeletionPolicy.MANUAL)),
                mock(DataSource.class), persistence(PersistenceMode.POSTGRESQL)));
    }

    private static ConfiguredServiceNowConnectorRegistry connectors() {
        ServiceNowSettings settings = new ServiceNowSettings(
                KEY, "tenant.service-now.com", "cmdb_ci_server", "env:SN_TOKEN", Duration.ofSeconds(5), true);
        return new ConfiguredServiceNowConnectorRegistry(
                Map.of(KEY, settings),
                request -> new io.infranexum.adapters.servicenow.ServiceNowTransport.Response(
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
        return policy(enabled, fields, ConnectorDeletionPolicy.IGNORE);
    }

    private static ConnectorGovernancePolicy policy(
            boolean enabled, List<ConnectorFieldAuthority> fields, ConnectorDeletionPolicy deletionPolicy) {
        return new ConnectorGovernancePolicy(
                KEY, "service-now", ConnectorSyncDirection.OUTBOUND, ConnectorDataAuthority.INFRANEXUM,
                ConnectorConflictStrategy.PREFER_AUTHORITY, deletionPolicy,
                ConnectorRollbackStrategy.MANUAL, enabled, fields);
    }

    private static ConnectorFieldAuthority field(String name, ConnectorDataAuthority authority) {
        return new ConnectorFieldAuthority(name, authority);
    }

    private static PersistenceRuntimeProperties persistence(PersistenceMode mode) {
        return new PersistenceRuntimeProperties(mode, JdbcIsolation.READ_COMMITTED);
    }
}
