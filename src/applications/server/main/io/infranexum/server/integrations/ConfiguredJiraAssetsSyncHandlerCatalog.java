package io.infranexum.server.integrations;

import io.infranexum.adapters.jiraassets.JiraAssetsMutationSettings;
import io.infranexum.adapters.jiraassets.JiraAssetsSyncHandler;
import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.adapters.persistence.jdbc.JdbcItamAssetOutboundSource;
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

/** Builds Jira mutating handlers only when provider mapping and authority policy match exactly. */
final class ConfiguredJiraAssetsSyncHandlerCatalog {
    private final List<ConnectorSyncHandler> handlers;

    ConfiguredJiraAssetsSyncHandlerCatalog(
            Map<ConnectorKey, JiraAssetsMutationSettings> mutations,
            ConfiguredJiraAssetsConnectorRegistry connectors,
            ConnectorGovernanceRegistry governance,
            DataSource dataSource,
            PersistenceRuntimeProperties persistence) {
        List<ConnectorSyncHandler> values = new ArrayList<>();
        JdbcDatabaseDialect dialect = null;
        for (Map.Entry<ConnectorKey, JiraAssetsMutationSettings> entry : mutations.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ConnectorKey::value))).toList()) {
            ConnectorKey key = entry.getKey();
            JiraAssetsMutationSettings mutation = entry.getValue();
            ConnectorGovernancePolicy policy = governance.require(key);
            validateAdmission(policy, mutation);
            if (dialect == null) dialect = dialect(persistence);
            values.add(new JiraAssetsSyncHandler(
                    mutation,
                    new JdbcItamAssetOutboundSource(dataSource, dialect, mutation.batchSize()),
                    connectors.require(key)));
        }
        handlers = List.copyOf(values);
    }

    List<ConnectorSyncHandler> handlers() { return handlers; }

    private static void validateAdmission(ConnectorGovernancePolicy policy, JiraAssetsMutationSettings mutation) {
        if (!policy.executionEnabled()
                || policy.direction() != ConnectorSyncDirection.OUTBOUND
                || policy.authority() != ConnectorDataAuthority.INFRANEXUM
                || policy.conflictStrategy() != ConnectorConflictStrategy.PREFER_AUTHORITY
                || policy.deletionPolicy() != ConnectorDeletionPolicy.IGNORE
                || policy.rollbackStrategy() != ConnectorRollbackStrategy.MANUAL) {
            throw new ConfigurationException(
                    "Jira Assets mutation requires OUTBOUND/INFRANEXUM/PREFER_AUTHORITY/IGNORE/MANUAL governance: "
                            + policy.connectorKey().value());
        }
        Set<String> governed = policy.fields().stream()
                .filter(field -> field.authority() == ConnectorDataAuthority.INFRANEXUM)
                .map(field -> field.field()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (governed.size() != policy.fields().size() || !governed.equals(mutation.attributeIds().keySet())) {
            throw new ConfigurationException(
                    "Jira Assets mutation fields must exactly match INFRANEXUM-governed fields: "
                            + policy.connectorKey().value());
        }
    }

    private static JdbcDatabaseDialect dialect(PersistenceRuntimeProperties persistence) {
        return switch (persistence.mode()) {
            case POSTGRESQL -> JdbcDatabaseDialect.POSTGRESQL;
            case ORACLE -> JdbcDatabaseDialect.ORACLE;
            case MEMORY -> throw new ConfigurationException("Jira Assets mutation requires PostgreSQL or Oracle persistence");
        };
    }
}
