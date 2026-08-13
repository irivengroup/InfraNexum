package io.infranexum.rsot.domain;

/** Normative RSOT canonical object lifecycle states. */
public enum CanonicalObjectStatus {
    PROPOSED,
    VALIDATED,
    RECONCILED,
    DEPRECATED,
    ARCHIVED;

    /** Consumer reads are restricted to certified canonical states. */
    public boolean consumerReadable() {
        return this == VALIDATED || this == RECONCILED;
    }
}
