package io.infranexum.itam.compliance.domain;

import java.util.Locale;
import java.util.Objects;

/** Versioned compliance-record lifecycle shared by warranty, support and license evidence. */
public enum ComplianceStatus {
    DRAFT("draft"), ACTIVE("active"), EXPIRED("expired"), CANCELLED("cancelled"), SUPERSEDED("superseded"), REVIEW_REQUIRED("review_required");

    private final String wireValue;
    ComplianceStatus(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }

    public static ComplianceStatus parse(String value) {
        Objects.requireNonNull(value, "value");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (ComplianceStatus candidate : values()) if (candidate.wireValue.equals(normalized)) return candidate;
        throw new IllegalArgumentException("unsupported compliance status");
    }

    public boolean verifiedState() { return this == ACTIVE || this == EXPIRED || this == REVIEW_REQUIRED; }
}
