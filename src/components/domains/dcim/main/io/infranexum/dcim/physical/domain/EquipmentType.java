package io.infranexum.dcim.physical.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Controlled equipment types. Each type belongs to exactly one category and declares rack-placement eligibility. */
public enum EquipmentType {
    PHYSICAL_SERVER(EquipmentCategory.SERVER, true), BLADE_SERVER(EquipmentCategory.SERVER, true), BLADE_CHASSIS(EquipmentCategory.SERVER, true),
    HYPERCONVERGED_NODE(EquipmentCategory.SERVER, true), GPU_SERVER(EquipmentCategory.SERVER, true), EDGE_SERVER(EquipmentCategory.SERVER, true),
    MAINFRAME(EquipmentCategory.SERVER, false), VIRTUAL_MACHINE(EquipmentCategory.SERVER, false),

    ROUTER(EquipmentCategory.NETWORK, true), CORE_SWITCH(EquipmentCategory.NETWORK, true), DISTRIBUTION_SWITCH(EquipmentCategory.NETWORK, true),
    ACCESS_SWITCH(EquipmentCategory.NETWORK, true), FIREWALL(EquipmentCategory.NETWORK, true), LOAD_BALANCER(EquipmentCategory.NETWORK, true),
    WIRELESS_CONTROLLER(EquipmentCategory.NETWORK, true), ACCESS_POINT(EquipmentCategory.NETWORK, false), MODEM(EquipmentCategory.NETWORK, false),
    SDWAN_APPLIANCE(EquipmentCategory.NETWORK, true), NETWORK_APPLIANCE(EquipmentCategory.NETWORK, true), PATCH_PANEL(EquipmentCategory.NETWORK, true),
    TRANSCEIVER(EquipmentCategory.NETWORK, false),

    STORAGE_ARRAY(EquipmentCategory.STORAGE, true), SAN_ARRAY(EquipmentCategory.STORAGE, true), NAS_APPLIANCE(EquipmentCategory.STORAGE, true),
    OBJECT_STORAGE_NODE(EquipmentCategory.STORAGE, true), HYPERCONVERGED_STORAGE(EquipmentCategory.STORAGE, true), TAPE_LIBRARY(EquipmentCategory.STORAGE, true),
    BACKUP_APPLIANCE(EquipmentCategory.STORAGE, true), JBOD(EquipmentCategory.STORAGE, true), DISK_SHELF(EquipmentCategory.STORAGE, true),

    DESKTOP(EquipmentCategory.COMPUTER, false), WORKSTATION(EquipmentCategory.COMPUTER, false), LAPTOP(EquipmentCategory.COMPUTER, false),
    THIN_CLIENT(EquipmentCategory.COMPUTER, false), MINI_PC(EquipmentCategory.COMPUTER, false), KIOSK(EquipmentCategory.COMPUTER, false),

    LASER_PRINTER(EquipmentCategory.PRINTING, false), INKJET_PRINTER(EquipmentCategory.PRINTING, false), MULTIFUNCTION_PRINTER(EquipmentCategory.PRINTING, false),
    LABEL_PRINTER(EquipmentCategory.PRINTING, false), PLOTTER(EquipmentCategory.PRINTING, false), DOCUMENT_SCANNER(EquipmentCategory.PRINTING, false),

    UPS(EquipmentCategory.POWER, true), RACK_PDU(EquipmentCategory.POWER, true), FLOOR_PDU(EquipmentCategory.POWER, false), ATS(EquipmentCategory.POWER, true),
    STS(EquipmentCategory.POWER, true), RECTIFIER(EquipmentCategory.POWER, true), INVERTER(EquipmentCategory.POWER, true), POWER_SUPPLY(EquipmentCategory.POWER, false),
    POWER_STRIP(EquipmentCategory.POWER, false),

    HDD(EquipmentCategory.DISK, false), SSD_SATA(EquipmentCategory.DISK, false), SSD_SAS(EquipmentCategory.DISK, false), SSD_NVME(EquipmentCategory.DISK, false),
    TAPE_DRIVE(EquipmentCategory.DISK, false),

    RAM_DIMM(EquipmentCategory.MEMORY, false), RAM_RDIMM(EquipmentCategory.MEMORY, false), RAM_LRDIMM(EquipmentCategory.MEMORY, false),
    RAM_SODIMM(EquipmentCategory.MEMORY, false), NVRAM(EquipmentCategory.MEMORY, false),

    RACK_CABINET(EquipmentCategory.RACK_INFRASTRUCTURE, false), OPEN_FRAME_RACK(EquipmentCategory.RACK_INFRASTRUCTURE, false),
    WALL_CABINET(EquipmentCategory.RACK_INFRASTRUCTURE, false), KVM_CONSOLE(EquipmentCategory.RACK_INFRASTRUCTURE, true),
    RACK_CONSOLE(EquipmentCategory.RACK_INFRASTRUCTURE, true), CABLE_MANAGER(EquipmentCategory.RACK_INFRASTRUCTURE, true),

    CCTV_CAMERA(EquipmentCategory.SECURITY, false), VIDEO_RECORDER(EquipmentCategory.SECURITY, false), ACCESS_CONTROL_PANEL(EquipmentCategory.SECURITY, false),
    BADGE_READER(EquipmentCategory.SECURITY, false), BIOMETRIC_READER(EquipmentCategory.SECURITY, false),

    IP_PHONE(EquipmentCategory.TELECOM, false), PBX(EquipmentCategory.TELECOM, true), SESSION_BORDER_CONTROLLER(EquipmentCategory.TELECOM, true),
    VOIP_GATEWAY(EquipmentCategory.TELECOM, true), DECT_BASE(EquipmentCategory.TELECOM, false),

    DISPLAY(EquipmentCategory.AUDIO_VIDEO, false), PROJECTOR(EquipmentCategory.AUDIO_VIDEO, false), CONFERENCE_SYSTEM(EquipmentCategory.AUDIO_VIDEO, false),
    VIDEOCONFERENCE_CODEC(EquipmentCategory.AUDIO_VIDEO, false), DIGITAL_SIGNAGE_PLAYER(EquipmentCategory.AUDIO_VIDEO, false),

    ENVIRONMENT_SENSOR(EquipmentCategory.ENVIRONMENT, false), TEMPERATURE_SENSOR(EquipmentCategory.ENVIRONMENT, false),
    HUMIDITY_SENSOR(EquipmentCategory.ENVIRONMENT, false), LEAK_SENSOR(EquipmentCategory.ENVIRONMENT, false), AIRFLOW_SENSOR(EquipmentCategory.ENVIRONMENT, false),
    CRAC(EquipmentCategory.ENVIRONMENT, false), CRAH(EquipmentCategory.ENVIRONMENT, false), HVAC_CONTROLLER(EquipmentCategory.ENVIRONMENT, false),

    MONITOR(EquipmentCategory.PERIPHERAL, false), KEYBOARD(EquipmentCategory.PERIPHERAL, false), MOUSE(EquipmentCategory.PERIPHERAL, false),
    DOCKING_STATION(EquipmentCategory.PERIPHERAL, false), USB_HUB(EquipmentCategory.PERIPHERAL, false), EXTERNAL_DRIVE(EquipmentCategory.PERIPHERAL, false),

    OTHER_EQUIPMENT(EquipmentCategory.OTHER, true);

    private final EquipmentCategory category;
    private final boolean rackMountable;

    EquipmentType(EquipmentCategory category, boolean rackMountable) {
        this.category = category;
        this.rackMountable = rackMountable;
    }

    public EquipmentCategory category() { return category; }
    public boolean rackMountable() { return rackMountable; }
    public String wireValue() { return name().toLowerCase(Locale.ROOT); }

    public static EquipmentType parse(String value) {
        if (value == null || value.isBlank()) return OTHER_EQUIPMENT;
        return valueOf(value.strip().replace('-', '_').toUpperCase(Locale.ROOT));
    }

    public static List<EquipmentType> forCategory(EquipmentCategory category) {
        return Arrays.stream(values()).filter(type -> type.category == category).toList();
    }
}
