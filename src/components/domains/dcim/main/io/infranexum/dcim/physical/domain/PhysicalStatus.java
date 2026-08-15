package io.infranexum.dcim.physical.domain;

import java.util.Locale;

/** Shared lifecycle vocabulary for racks, equipment and cables. */
public enum PhysicalStatus {
    DRAFT, ACTIVE, MAINTENANCE, DECOMMISSIONED, ARCHIVED;
    public String wireValue(){ return name().toLowerCase(Locale.ROOT); }
    public static PhysicalStatus parse(String value){ return valueOf(value.strip().toUpperCase(Locale.ROOT)); }
}
