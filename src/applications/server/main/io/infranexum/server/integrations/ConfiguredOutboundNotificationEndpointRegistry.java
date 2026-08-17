package io.infranexum.server.integrations;

import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.OutboundNotificationEndpoint;
import io.infranexum.integrations.OutboundNotificationEndpointRegistry;
import io.infranexum.integrations.OutboundNotificationNotFoundException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable notification endpoint registry built only from validated Server configuration. */
final class ConfiguredOutboundNotificationEndpointRegistry implements OutboundNotificationEndpointRegistry {
    private final Map<ConnectorKey, OutboundNotificationEndpoint> endpoints;

    ConfiguredOutboundNotificationEndpointRegistry(Map<ConnectorKey, OutboundNotificationEndpoint> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        this.endpoints = Map.copyOf(new LinkedHashMap<>(definitions));
    }

    @Override
    public OutboundNotificationEndpoint require(ConnectorKey endpointKey) {
        Objects.requireNonNull(endpointKey, "endpointKey");
        OutboundNotificationEndpoint endpoint = endpoints.get(endpointKey);
        if (endpoint == null) throw new OutboundNotificationNotFoundException("unknown notification endpoint: " + endpointKey.value());
        return endpoint;
    }

    @Override
    public Collection<OutboundNotificationEndpoint> endpoints() {
        return endpoints.values();
    }
}
