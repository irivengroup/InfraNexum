package io.infranexum.server.integrations;

import io.infranexum.integrations.ConnectorEndpointRegistry;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorWebhookEndpoint;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable endpoint registry materialized from validated startup configuration. */
final class ConfiguredConnectorEndpointRegistry implements ConnectorEndpointRegistry {
    private final Map<ConnectorKey, ConnectorWebhookEndpoint> endpoints;
    ConfiguredConnectorEndpointRegistry(Map<ConnectorKey, ConnectorWebhookEndpoint> endpoints) { this.endpoints = Map.copyOf(Objects.requireNonNull(endpoints, "endpoints")); }
    @Override public Optional<ConnectorWebhookEndpoint> find(ConnectorKey connectorKey) { return Optional.ofNullable(endpoints.get(Objects.requireNonNull(connectorKey, "connectorKey"))); }
    @Override public Collection<ConnectorWebhookEndpoint> endpoints() { return endpoints.values(); }
}
