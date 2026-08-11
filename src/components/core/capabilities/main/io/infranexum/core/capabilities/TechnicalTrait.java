package io.infranexum.core.capabilities;

/** Supported technical traits used by the Capability Registry. */
public enum TechnicalTrait {
    AIR_GAPPED("air-gapped"),
    HARDENED("hardened"),
    ORACLE_BACKEND("oracle-backend"),
    EXTERNAL_DATABASE("external-database");

    private final String code;

    TechnicalTrait(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static TechnicalTrait parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("trait must not be blank");
        }
        String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
        for (TechnicalTrait trait : values()) {
            if (trait.code.equals(normalized)) {
                return trait;
            }
        }
        throw new IllegalArgumentException("unknown trait: " + value);
    }
}
