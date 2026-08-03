package io.infranexum.core.capabilities;

/** Deployment topology, deliberately independent from the installation profile. */
public enum InstallationTopology {
    SINGLE_NODE("single-node"),
    SPLIT_WEB("split-web"),
    HIGH_AVAILABILITY("high-availability"),
    DISTRIBUTED_SITES("distributed-sites"),
    REGIONAL("regional"),
    MULTI_REGION("multi-region");

    private final String code;

    InstallationTopology(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static InstallationTopology parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("topology must not be blank");
        }
        String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
        for (InstallationTopology topology : values()) {
            if (topology.code.equals(normalized)) {
                return topology;
            }
        }
        throw new IllegalArgumentException("unknown topology: " + value);
    }
}
