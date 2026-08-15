package io.infranexum.dcim.facility.domain;

import java.util.Locale;

/** Physical hierarchy node kinds owned by DCIM PGM-07-E04. */
public enum FacilityKind {
    SITE, BUILDING, FLOOR, ROOM, ZONE;
    public String wireValue() { return name().toLowerCase(Locale.ROOT); }
    public static FacilityKind parse(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("facility kind is required");
        try { return valueOf(value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("invalid facility kind", failure); }
    }
}
