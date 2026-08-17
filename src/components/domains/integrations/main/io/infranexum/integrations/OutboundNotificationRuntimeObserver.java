package io.infranexum.integrations;

/** Low-cardinality observability port for outbound notification processing. */
public interface OutboundNotificationRuntimeObserver {
    void admitted(ConnectorKey endpointKey, boolean duplicate);
    void delivered(ConnectorKey endpointKey);
    void failed(ConnectorKey endpointKey, boolean deadLetter);
    void replayed(ConnectorKey endpointKey);
}
