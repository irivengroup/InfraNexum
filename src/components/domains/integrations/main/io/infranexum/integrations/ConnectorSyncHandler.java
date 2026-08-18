package io.infranexum.integrations;

/** Provider/domain synchronization implementation registered only after authority and rollback contracts are approved. */
public interface ConnectorSyncHandler {
    ConnectorKey connectorKey();
    ConnectorSyncBatchResult synchronize(ConnectorSyncBatchContext context);
    ConnectorSyncCompensationResult compensate(ConnectorSyncCompensationContext context);
}
