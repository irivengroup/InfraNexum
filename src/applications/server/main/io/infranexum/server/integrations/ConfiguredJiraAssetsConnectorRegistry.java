package io.infranexum.server.integrations;

import io.infranexum.adapters.jiraassets.JiraAssetsConnector;
import io.infranexum.adapters.jiraassets.JiraAssetsSettings;
import io.infranexum.integrations.ConnectorEndpointUnavailableException;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorSecretProvider;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/** Immutable registry for configured Jira Assets connector instances. */
final class ConfiguredJiraAssetsConnectorRegistry {
    private final Map<ConnectorKey, JiraAssetsConnector> connectors;

    ConfiguredJiraAssetsConnectorRegistry(
            Map<ConnectorKey, JiraAssetsSettings> definitions,
            io.infranexum.adapters.jiraassets.JiraAssetsTransport transport,
            ConnectorSecretProvider secrets,
            ObjectMapper json) {
        Objects.requireNonNull(definitions, "definitions");
        Map<ConnectorKey, JiraAssetsConnector> values = new LinkedHashMap<>();
        definitions.forEach((key, settings) -> values.put(key, new JiraAssetsConnector(settings, transport, secrets, json)));
        this.connectors = Map.copyOf(values);
    }

    JiraAssetsConnector require(String connectorKey) {
        return require(new ConnectorKey(connectorKey));
    }

    JiraAssetsConnector require(ConnectorKey key) {
        JiraAssetsConnector connector = connectors.get(Objects.requireNonNull(key, "connectorKey"));
        if (connector == null) throw new ConnectorEndpointUnavailableException("Jira Assets connector is not configured");
        return connector;
    }

    Collection<JiraAssetsConnector> connectors() {
        return connectors.values();
    }

    List<JiraAssetsSettings> settings() {
        return connectors.values().stream()
                .map(JiraAssetsConnector::settings)
                .sorted(Comparator.comparing(value -> value.connectorKey().value()))
                .toList();
    }
}
