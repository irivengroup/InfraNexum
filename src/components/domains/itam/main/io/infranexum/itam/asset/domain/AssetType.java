package io.infranexum.itam.asset.domain;

import java.util.Locale;
import java.util.Objects;

/** Patrimonial asset categories owned by ITAM in PGM-07-E02. */
public enum AssetType {
    HARDWARE("hardware"),
    SOFTWARE("software");

    private final String wireValue;

    AssetType(String wireValue) { this.wireValue = wireValue; }

    public String wireValue() { return wireValue; }

    public static AssetType parse(String value) {
        Objects.requireNonNull(value, "value");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (AssetType candidate : values()) {
            if (candidate.wireValue.equals(normalized)) return candidate;
        }
        throw new IllegalArgumentException("unsupported assetType");
    }
}
