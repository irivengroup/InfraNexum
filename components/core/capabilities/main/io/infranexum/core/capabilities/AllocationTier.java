package io.infranexum.core.capabilities;

/** Commercial allocation tier; this never changes the installed functional surface. */
public enum AllocationTier {
    STANDARD,
    ADVANCED,
    ULTIMATE;

    public static AllocationTier parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("allocation tier must not be blank");
        }
        return valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
    }
}
