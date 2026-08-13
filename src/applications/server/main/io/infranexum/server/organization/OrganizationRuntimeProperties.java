package io.infranexum.server.organization;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Exposure switch for the Organization API; authorization is enforced by the Server RBAC PEP. */
@ConfigurationProperties(prefix = "infranexum.organization")
public record OrganizationRuntimeProperties(boolean apiEnabled, String environment) {
    public OrganizationRuntimeProperties {
        environment = environment == null
                ? "production"
                : environment.strip().toLowerCase(Locale.ROOT);
        if (environment.isBlank()) {
            throw new IllegalArgumentException("organization environment must not be blank");
        }
    }

    public boolean localDevelopment() {
        return "local".equals(environment);
    }
}
