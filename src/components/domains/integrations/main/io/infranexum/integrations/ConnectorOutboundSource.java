package io.infranexum.integrations;

/** Provider-neutral source port for bounded outbound synchronization batches. */
@FunctionalInterface
public interface ConnectorOutboundSource {
    ConnectorOutboundPage read(ConnectorSyncBatchContext context);
}
