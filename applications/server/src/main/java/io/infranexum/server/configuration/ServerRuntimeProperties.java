package io.infranexum.server.configuration;

import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.core.contracts.RuntimeMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated startup configuration owned by the Server composition root. */
@Validated
@ConfigurationProperties(prefix = "infranexum.server")
public record ServerRuntimeProperties(
        @NotBlank String instanceId,
        @NotNull RuntimeMode mode,
        @NotBlank String region,
        @NotBlank String site,
        @NotBlank String version,
        @NotBlank String architectureBaseline) {

    public ServerRuntimeProperties {
        instanceId = requireText(instanceId, "instanceId");
        mode = requireMode(mode);
        region = requireText(region, "region");
        site = requireText(site, "site");
        version = requireText(version, "version");
        architectureBaseline = requireText(architectureBaseline, "architectureBaseline");
        if (mode == RuntimeMode.GLOBAL && !"global".equals(region.toLowerCase(Locale.ROOT))) {
            throw new ConfigurationException("GLOBAL mode requires region=global");
        }
    }

    private static RuntimeMode requireMode(RuntimeMode value) {
        if (value == null) {
            throw new ConfigurationException("mode must not be null");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ConfigurationException(field + " must not be blank");
        }
        return value.strip();
    }
}
