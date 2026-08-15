package io.infranexum.itam.asset.domain;

/** Append-only chain-of-custody event kinds. */
public enum AssetCustodyEventType {
    ACQUIRED,
    RECEIVED,
    STOCKED,
    ASSIGNED,
    DEPLOYED,
    TRANSFERRED,
    MAINTENANCE_STARTED,
    RETURNED,
    RETIRED,
    DISPOSED
}
