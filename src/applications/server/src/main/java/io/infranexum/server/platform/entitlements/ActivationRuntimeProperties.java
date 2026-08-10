package io.infranexum.server.platform.entitlements;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Externalized, secret-free paths and limits for the authoritative entitlement runtime. */
@Validated
@ConfigurationProperties(prefix = "infranexum.entitlements")
public record ActivationRuntimeProperties(
        boolean enabled,
        @NotBlank String customerId,
        Path trustStorePath,
        @NotNull Path integrityKeyPath,
        @NotNull Path proofDirectory,
        @Min(1024) @Max(4_194_304) int maxManifestBytes,
        @NotNull Duration refreshInterval) {

    public ActivationRuntimeProperties {
        customerId = Objects.requireNonNull(customerId, "customerId").strip();
        if (trustStorePath != null && trustStorePath.toString().isBlank()) {
            trustStorePath = null;
        }
        if (customerId.isEmpty()) {
            throw new IllegalArgumentException("customerId must not be blank");
        }
        Objects.requireNonNull(integrityKeyPath, "integrityKeyPath");
        Objects.requireNonNull(proofDirectory, "proofDirectory");
        Objects.requireNonNull(refreshInterval, "refreshInterval");
        if (maxManifestBytes < 1024 || maxManifestBytes > 4_194_304) {
            throw new IllegalArgumentException("maxManifestBytes must be between 1 KiB and 4 MiB");
        }
        if (refreshInterval.compareTo(Duration.ofMinutes(1)) < 0
                || refreshInterval.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("refreshInterval must be between one minute and 24 hours");
        }
    }
}
