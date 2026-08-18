package io.infranexum.integrations;

/** Immutable lookup boundary for approved synchronization handlers. */
public interface ConnectorSyncHandlerRegistry {
    ConnectorSyncHandler require(ConnectorKey connectorKey);
}
