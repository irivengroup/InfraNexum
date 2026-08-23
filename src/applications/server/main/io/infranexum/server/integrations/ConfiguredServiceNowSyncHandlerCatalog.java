package io.infranexum.server.integrations;

import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.adapters.persistence.jdbc.JdbcItamAssetOutboundSource;
import io.infranexum.adapters.servicenow.ServiceNowMutationSettings;
import io.infranexum.adapters.servicenow.ServiceNowSyncHandler;
import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.integrations.ConnectorConflictStrategy;
import io.infranexum.integrations.ConnectorDataAuthority;
import io.infranexum.integrations.ConnectorDeletionPolicy;
import io.infranexum.integrations.ConnectorGovernancePolicy;
import io.infranexum.integrations.ConnectorGovernanceRegistry;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorRollbackStrategy;
import io.infranexum.integrations.ConnectorSyncDirection;
import io.infranexum.integrations.ConnectorSyncHandler;
import io.infranexum.server.persistence.PersistenceRuntimeProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/** Builds ServiceNow mutating handlers only when provider mapping and authority policy match exactly. */
final class ConfiguredServiceNowSyncHandlerCatalog {
    private final List<ConnectorSyncHandler> handlers;

    ConfiguredServiceNowSyncHandlerCatalog(
            Map<ConnectorKey, ServiceNowMutationSettings> mutations,
            ConfiguredServiceNowConnectorRegistry connectors,
            ConnectorGovernanceRegistry governance,
            DataSource dataSource,
            PersistenceRuntimeProperties persistence) {
        List<ConnectorSyncHandler> values = new ArrayList<>();
        JdbcDatabaseDialect dialect = null;
        for (Map.Entry<ConnectorKey, ServiceNowMutationSettings> entry : mutations.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ConnectorKey::value))).toList()) {
            ConnectorKey key = entry.getKey();
            ServiceNowMutationSettings mutation = entry.getValue();
            ConnectorGovernancePolicy policy = governance.require(key);
            validateAdmission(policy, mutation);
            if (dialect == null) dialect = dialect(persistence);
            values.add(new ServiceNowSyncHandler(
                    mutation,
                    new JdbcItamAssetOutboundSource(dataSource, dialect, mutation.batchSize()),
                    connectors.require(key)));
        }
        handlers = List.copyOf(values);
    }

    List<ConnectorSyncHandler> handlers() { return handlers; }

    private static void validateAdmission(ConnectorGovernancePolicy policy, ServiceNowMutationSettings mutation) {
        if (!policy.executionEnabled()
                || policy.direction() != ConnectorSyncDirection.OUTBOUND
                || policy.authority() != ConnectorDataAuthority.INFRANEXUM
                || policy.conflictStrategy() != ConnectorConflictStrategy.PREFER_AUTHORITY
                || policy.rollbackStrategy() != ConnectorRollbackStrategy.MANUAL) {
            throw new ConfigurationException(
                    "ServiceNow mutation requires OUTBOUND/INFRANEXUM/PREFER_AUTHORITY/MANUAL governance: "
                            + policy.connectorKey().value());
        }
        boolean tombstoneConfigured = mutation.tombstone() != null;
        if ((policy.deletionPolicy() == ConnectorDeletionPolicy.IGNORE && tombstoneConfigured)
                || (policy.deletionPolicy() == ConnectorDeletionPolicy.TOMBSTONE && !tombstoneConfigured)
                || (policy.deletionPolicy() == ConnectorDeletionPolicy.MANUAL)) {
            throw new ConfigurationException(
                    "ServiceNow deletion policy must match the explicit tombstone mapping: "
                            + policy.connectorKey().value());
        }
        Set<String> governed = policy.fields().stream()
                .filter(field -> field.authority() == ConnectorDataAuthority.INFRANEXUM)
                .map(field -> field.field())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (governed.size() != policy.fields().size() || !governed.equals(mutation.fieldNames().keySet())) {
            throw new ConfigurationException(
                    "ServiceNow mutation fields must exactly match INFRANEXUM-governed fields: "
                            + policy.connectorKey().value());
        }
    }

    private static JdbcDatabaseDialect dialect(PersistenceRuntimeProperties persistence) {
        return switch (persistence.mode()) {
            case POSTGRESQL -> JdbcDatabaseDialect.POSTGRESQL;
            case ORACLE -> JdbcDatabaseDialect.ORACLE;
            case MEMORY -> throw new ConfigurationException(
                    "ServiceNow mutation requires PostgreSQL or Oracle persistence");
        };
    }
}
