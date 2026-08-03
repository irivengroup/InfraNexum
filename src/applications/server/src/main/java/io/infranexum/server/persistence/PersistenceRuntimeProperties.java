package io.infranexum.server.persistence;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated persistence selection; invalid values fail Server startup. */
@Validated
@ConfigurationProperties(prefix = "infranexum.persistence")
public record PersistenceRuntimeProperties(
        @NotNull PersistenceMode mode,
        @NotNull JdbcIsolation isolation) {

    public PersistenceRuntimeProperties {
        if (mode == null) {
            throw new IllegalArgumentException("persistence mode must not be null");
        }
        if (isolation == null) {
            throw new IllegalArgumentException("persistence isolation must not be null");
        }
    }
}
