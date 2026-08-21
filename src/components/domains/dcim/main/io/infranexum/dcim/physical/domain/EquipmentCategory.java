package io.infranexum.dcim.physical.domain;

import java.util.Locale;

/** Governed equipment families spanning datacenter and enterprise-office infrastructure. */
public enum EquipmentCategory {
    SERVER, NETWORK, STORAGE, COMPUTER, PRINTING, POWER, DISK, MEMORY, RACK_INFRASTRUCTURE,
    SECURITY, TELECOM, AUDIO_VIDEO, ENVIRONMENT, PERIPHERAL, OTHER;

    public String wireValue() { return name().toLowerCase(Locale.ROOT); }

    public static EquipmentCategory parse(String value) {
        if (value == null || value.isBlank()) return OTHER;
        return valueOf(value.strip().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
