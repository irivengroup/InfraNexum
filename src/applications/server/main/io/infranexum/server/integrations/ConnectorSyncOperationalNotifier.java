package io.infranexum.server.integrations;

import io.infranexum.integrations.ConnectorSyncRun;

/** Non-blocking operational notification boundary for durable connector synchronization results. */
@FunctionalInterface
interface ConnectorSyncOperationalNotifier {
    ConnectorSyncOperationalNotifier NOOP = run -> { };

    void publish(ConnectorSyncRun run);
}
