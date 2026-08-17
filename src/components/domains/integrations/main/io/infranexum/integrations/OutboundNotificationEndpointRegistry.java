package io.infranexum.integrations;

import java.util.Collection;

/** Registry of statically configured outbound notification endpoints. */
public interface OutboundNotificationEndpointRegistry {
    OutboundNotificationEndpoint require(ConnectorKey endpointKey);
    Collection<OutboundNotificationEndpoint> endpoints();
}
