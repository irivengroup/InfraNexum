package io.infranexum.itam.compliance.domain;

import java.util.Locale;
import java.util.Objects;

/** Provenance of contractual evidence; values are deliberately finite and auditable. */
public enum EvidenceSource {
    MANUAL("manual"), IMPORT("import"), INTEGRATION("integration"), MIGRATION("migration");
    private final String wireValue;
    EvidenceSource(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }
    public static EvidenceSource parse(String value) {
        Objects.requireNonNull(value, "value");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (EvidenceSource candidate : values()) if (candidate.wireValue.equals(normalized)) return candidate;
        throw new IllegalArgumentException("unsupported evidence source");
    }
}
