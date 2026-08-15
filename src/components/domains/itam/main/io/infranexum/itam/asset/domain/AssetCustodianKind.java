package io.infranexum.itam.asset.domain;

import java.util.Locale;
import java.util.Objects;

/** Kinds of accountable custody holders without importing another bounded context model. */
public enum AssetCustodianKind {
    NONE("none"),
    ORGANIZATION("organization"),
    SUBDIVISION("subdivision"),
    ACTOR("actor"),
    PARTNER("partner");

    private final String wireValue;

    AssetCustodianKind(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }

    public static AssetCustodianKind parse(String value) {
        Objects.requireNonNull(value, "value");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (AssetCustodianKind candidate : values()) {
            if (candidate.wireValue.equals(normalized)) return candidate;
        }
        throw new IllegalArgumentException("unsupported custodianKind");
    }
}
