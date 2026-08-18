package io.infranexum.server.integrations;

import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorSyncHandler;
import io.infranexum.integrations.ConnectorSyncHandlerRegistry;
import io.infranexum.integrations.ConnectorSyncHandlerUnavailableException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable registry of explicitly approved mutating synchronization handlers. */
final class ImmutableConnectorSyncHandlerRegistry implements ConnectorSyncHandlerRegistry {
    private final Map<ConnectorKey, ConnectorSyncHandler> handlers;

    ImmutableConnectorSyncHandlerRegistry(List<ConnectorSyncHandler> handlers) {
        Map<ConnectorKey, ConnectorSyncHandler> indexed = new LinkedHashMap<>();
        for (ConnectorSyncHandler handler : Objects.requireNonNullElse(handlers, List.<ConnectorSyncHandler>of())) {
            ConnectorSyncHandler nonNull = Objects.requireNonNull(handler, "handler");
            ConnectorKey key = Objects.requireNonNull(nonNull.connectorKey(), "handler connectorKey");
            if (indexed.putIfAbsent(key, nonNull) != null) throw new ConfigurationException("duplicate connector synchronization handler: " + key.value());
        }
        this.handlers = Map.copyOf(indexed);
    }

    @Override
    public ConnectorSyncHandler require(ConnectorKey connectorKey) {
        ConnectorSyncHandler handler = handlers.get(Objects.requireNonNull(connectorKey, "connectorKey"));
        if (handler == null) throw new ConnectorSyncHandlerUnavailableException(connectorKey);
        return handler;
    }

    List<ConnectorKey> keys() { return handlers.keySet().stream().sorted(java.util.Comparator.comparing(ConnectorKey::value)).toList(); }
}
