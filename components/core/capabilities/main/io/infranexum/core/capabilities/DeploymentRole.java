package io.infranexum.core.capabilities;

/** Deployable process roles. */
public enum DeploymentRole {
    SERVER,
    WEB,
    AGENT;

    public static DeploymentRole parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        return valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
    }
}
