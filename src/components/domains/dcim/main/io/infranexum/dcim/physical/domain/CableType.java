package io.infranexum.dcim.physical.domain;

import java.util.Locale;

/** Physical cable families independent of endpoint connector/media compatibility checks. */
public enum CableType {
    COPPER_ETHERNET, FIBER_SINGLE_MODE, FIBER_MULTI_MODE, DAC, AOC, COAXIAL,
    POWER_AC, POWER_DC, SERIAL, USB, HDMI, DISPLAYPORT, TELEPHONE, OTHER;

    public String wireValue() { return name().toLowerCase(Locale.ROOT); }
    public static CableType parse(String value) {
        if (value == null || value.isBlank()) return OTHER;
        return valueOf(value.strip().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
