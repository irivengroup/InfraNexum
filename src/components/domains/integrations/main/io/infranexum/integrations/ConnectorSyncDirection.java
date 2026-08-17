package io.infranexum.integrations;

/** Supported connector data-flow directions with explicit mutation semantics. */
public enum ConnectorSyncDirection {
    FEDERATED_READ(false, false),
    INBOUND(true, false),
    OUTBOUND(false, true),
    BIDIRECTIONAL(true, true);

    private final boolean mutatesLocal;
    private final boolean mutatesRemote;

    ConnectorSyncDirection(boolean mutatesLocal, boolean mutatesRemote) {
        this.mutatesLocal = mutatesLocal;
        this.mutatesRemote = mutatesRemote;
    }

    public boolean mutatesLocal() { return mutatesLocal; }
    public boolean mutatesRemote() { return mutatesRemote; }
    public boolean mutating() { return mutatesLocal || mutatesRemote; }
}
