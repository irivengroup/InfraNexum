package io.infranexum.server.integrations;

import io.infranexum.adapters.servicenow.ServiceNowConnector;
import io.infranexum.adapters.servicenow.ServiceNowSettings;
import io.infranexum.adapters.servicenow.ServiceNowTransport;
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

/** Immutable registry for configured ServiceNow CMDB connector instances. */
final class ConfiguredServiceNowConnectorRegistry {
    private final Map<ConnectorKey, ServiceNowConnector> connectors;

    ConfiguredServiceNowConnectorRegistry(
            Map<ConnectorKey, ServiceNowSettings> definitions,
            ServiceNowTransport transport,
            ConnectorSecretProvider secrets,
            ObjectMapper json) {
        Objects.requireNonNull(definitions, "definitions");
        Map<ConnectorKey, ServiceNowConnector> values = new LinkedHashMap<>();
        definitions.forEach((key, settings) -> values.put(key, new ServiceNowConnector(settings, transport, secrets, json)));
        this.connectors = Map.copyOf(values);
    }

    ServiceNowConnector require(String connectorKey) {
        ConnectorKey key = new ConnectorKey(connectorKey);
        ServiceNowConnector connector = connectors.get(key);
        if (connector == null) throw new ConnectorEndpointUnavailableException("ServiceNow connector is not configured");
        return connector;
    }

    Collection<ServiceNowConnector> connectors() { return connectors.values(); }

    List<ServiceNowSettings> settings() {
        return connectors.values().stream()
                .map(ServiceNowConnector::settings)
                .sorted(Comparator.comparing(value -> value.connectorKey().value()))
                .toList();
    }
}
