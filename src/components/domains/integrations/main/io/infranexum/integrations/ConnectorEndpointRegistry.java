package io.infranexum.integrations;

import java.util.Collection;
import java.util.Optional;

/** Runtime lookup of configured webhook endpoints. */
public interface ConnectorEndpointRegistry {
    Optional<ConnectorWebhookEndpoint> find(ConnectorKey connectorKey);
    Collection<ConnectorWebhookEndpoint> endpoints();
}
