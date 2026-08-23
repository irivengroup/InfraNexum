package io.infranexum.adapters.servicenow;

import io.infranexum.core.contracts.ConfigurationException;
import java.util.Objects;

/** Explicit remote tombstone column/value used for controlled ServiceNow deletion propagation. */
public record ServiceNowTombstoneSettings(String fieldName, String value) {
    public ServiceNowTombstoneSettings {
        fieldName = ServiceNowMutationSettings.providerColumn(fieldName);
        value = strictValue(value);
    }

    private static String strictValue(String value) {
        Objects.requireNonNull(value, "value");
        if (!value.equals(value.strip()) || value.isEmpty() || value.length() > 4096
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new ConfigurationException("ServiceNow tombstone value is invalid");
        }
        return value;
    }
}
