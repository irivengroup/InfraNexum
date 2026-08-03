package io.infranexum.core.capabilities;

/** Canonical installation profiles; allocation tiers are modeled separately. */
public enum InstallationProfile {
    LITE,
    PRO,
    ENTERPRISE;

    public static InstallationProfile parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("profile must not be blank");
        }
        return valueOf(value.strip().replace('-', '_').toUpperCase(java.util.Locale.ROOT));
    }
}
