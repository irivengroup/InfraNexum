package io.infranexum.itam.partner.domain;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Governed partner authorization lifecycle from draft to retirement. */
public enum PartnerAuthorizationStatus {
    DRAFT("draft"), PENDING_APPROVAL("pending_approval"), ACTIVE("active"),
    SUSPENDED("suspended"), RETIRED("retired");

    private final String wireValue;
    PartnerAuthorizationStatus(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }

    public boolean canTransitionTo(PartnerAuthorizationStatus target) {
        return switch (this) {
            case DRAFT -> target == PENDING_APPROVAL;
            case PENDING_APPROVAL -> target == ACTIVE || target == DRAFT;
            case ACTIVE -> target == SUSPENDED || target == RETIRED;
            case SUSPENDED -> target == ACTIVE || target == RETIRED;
            case RETIRED -> false;
        };
    }

    public static PartnerAuthorizationStatus parse(String value) {
        if (value == null) throw new IllegalArgumentException("authorization status is required");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return EnumSet.allOf(PartnerAuthorizationStatus.class).stream()
                .filter(status -> status.wireValue.equals(normalized))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unsupported authorization status"));
    }
}
