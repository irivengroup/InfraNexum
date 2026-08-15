package io.infranexum.itam.asset.domain;

/** Governed ITAM physical/patrimonial lifecycle states for PGM-07-E02. */
public enum AssetLifecycleStatus {
    ACQUIRED("acquired"),
    RECEIVED("received"),
    IN_STOCK("in_stock"),
    ASSIGNED("assigned"),
    DEPLOYED("deployed"),
    MAINTENANCE("maintenance"),
    RETURNED("returned"),
    RETIRED("retired"),
    DISPOSED("disposed");

    private final String wireValue;

    AssetLifecycleStatus(String wireValue) { this.wireValue = wireValue; }

    public String wireValue() { return wireValue; }

    public boolean operationalReadinessRequired() {
        return this == IN_STOCK || this == ASSIGNED || this == DEPLOYED;
    }

    public boolean terminal() { return this == DISPOSED; }
}
