package io.infranexum.server.integrations;

import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorSyncBatchContext;
import io.infranexum.integrations.ConnectorSyncBatchResult;
import io.infranexum.integrations.ConnectorSyncCompensationContext;
import io.infranexum.integrations.ConnectorSyncCompensationResult;
import io.infranexum.integrations.ConnectorSyncHandler;
import io.infranexum.integrations.ConnectorSyncHandlerUnavailableException;
import java.util.Arrays;
import java.util.List;

/** Dependency-free regression proof for the immutable Server synchronization-handler registry. */
public final class ConnectorSyncHandlerRegistrySmoke {
    private ConnectorSyncHandlerRegistrySmoke() {}

    public static void main(String[] args) {
        ConnectorSyncHandler alpha = handler("alpha-sync");
        ConnectorSyncHandler beta = handler("beta-sync");

        ImmutableConnectorSyncHandlerRegistry registry = new ImmutableConnectorSyncHandlerRegistry(List.of(beta, alpha));
        assert registry.keys().equals(List.of(new ConnectorKey("alpha-sync"), new ConnectorKey("beta-sync")));
        assert registry.require(new ConnectorKey("alpha-sync")) == alpha;

        assert new ImmutableConnectorSyncHandlerRegistry(null).keys().isEmpty();
        expect(ConfigurationException.class, () -> new ImmutableConnectorSyncHandlerRegistry(List.of(alpha, alpha)));
        expect(NullPointerException.class, () -> new ImmutableConnectorSyncHandlerRegistry(Arrays.asList(alpha, null)));
        expect(ConnectorSyncHandlerUnavailableException.class, () -> registry.require(new ConnectorKey("missing-sync")));

        System.out.println("connector-sync-handler-registry-smoke: PASS typed-empty-list duplicate/null/missing fail-closed");
    }

    private static ConnectorSyncHandler handler(String key) {
        ConnectorKey connectorKey = new ConnectorKey(key);
        return new ConnectorSyncHandler() {
            @Override public ConnectorKey connectorKey() { return connectorKey; }
            @Override public ConnectorSyncBatchResult synchronize(ConnectorSyncBatchContext context) {
                throw new UnsupportedOperationException("not used by registry smoke");
            }
            @Override public ConnectorSyncCompensationResult compensate(ConnectorSyncCompensationContext context) {
                throw new UnsupportedOperationException("not used by registry smoke");
            }
        };
    }

    private static void expect(Class<? extends Throwable> expected, Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected " + expected.getSimpleName());
        } catch (Throwable actual) {
            if (!expected.isInstance(actual)) throw new AssertionError("unexpected exception: " + actual, actual);
        }
    }
}
