package io.infranexum.core.capabilities;

/** Normative quota classes from CDC 13. */
public enum QuotaClass {
    COMMERCIAL_SCALABLE,
    ARCHITECTURAL_FIXED;

    static QuotaClass parse(String value) {
        return valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
    }
}
