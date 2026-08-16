package io.infranexum.server.integrations;

import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.integrations.ConnectorDeliveryHandler;
import io.infranexum.integrations.ConnectorHandlerRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable handler registry rejecting duplicate or missing certified connector handlers. */
final class ImmutableConnectorHandlerRegistry implements ConnectorHandlerRegistry {
    private final Map<String, ConnectorDeliveryHandler> handlers;
    ImmutableConnectorHandlerRegistry(List<ConnectorDeliveryHandler> handlers) {
        Map<String, ConnectorDeliveryHandler> indexed = new LinkedHashMap<>();
        for (ConnectorDeliveryHandler handler : Objects.requireNonNull(handlers, "handlers")) {
            String name = Objects.requireNonNull(handler, "handler").name().strip();
            if (name.isEmpty() || name.length() > 160) throw new ConfigurationException("connector handler name is invalid");
            if (indexed.putIfAbsent(name, handler) != null) throw new ConfigurationException("duplicate connector handler: " + name);
        }
        this.handlers = Map.copyOf(indexed);
    }
    @Override public ConnectorDeliveryHandler require(String handlerName) {
        ConnectorDeliveryHandler handler = handlers.get(Objects.requireNonNull(handlerName, "handlerName"));
        if (handler == null) throw new IllegalStateException("connector handler is not registered: " + handlerName);
        return handler;
    }
    boolean contains(String handlerName) { return handlers.containsKey(handlerName); }
}
