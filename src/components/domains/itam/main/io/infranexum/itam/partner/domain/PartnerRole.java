package io.infranexum.itam.partner.domain;

import java.util.Locale;

/** Normative roles carried by the single ITAM Partner aggregate. */
public enum PartnerRole {
    MANUFACTURER("manufacturer"),
    SOFTWARE_PUBLISHER("software_publisher"),
    SUPPLIER("supplier"),
    THIRD_PARTY_SUPPORT_PROVIDER("third_party_support_provider"),
    INTEGRATOR("integrator"),
    RECYCLER("recycler");

    private final String wireValue;

    PartnerRole(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }

    public static PartnerRole parse(String value) {
        if (value == null) throw new IllegalArgumentException("partner role is required");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (PartnerRole role : values()) if (role.wireValue.equals(normalized)) return role;
        throw new IllegalArgumentException("unsupported partner role");
    }
}
