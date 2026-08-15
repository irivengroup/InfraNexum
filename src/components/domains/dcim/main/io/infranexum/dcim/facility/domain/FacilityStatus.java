package io.infranexum.dcim.facility.domain;

import java.util.Locale;

/** Superset of lifecycle states used by sites, buildings, floors, rooms and technical zones. */
public enum FacilityStatus {
    DRAFT, ACTIVE, SUSPENDED, MAINTENANCE, LOCKED, INACTIVE, ARCHIVED, DELETED;
    public String wireValue() { return name().toLowerCase(Locale.ROOT); }
    public static FacilityStatus parse(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("facility status is required");
        try { return valueOf(value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("invalid facility status", failure); }
    }
}
