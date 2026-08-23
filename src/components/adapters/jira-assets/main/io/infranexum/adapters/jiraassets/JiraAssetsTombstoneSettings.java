package io.infranexum.adapters.jiraassets;

import io.infranexum.core.contracts.ConfigurationException;
import java.util.Objects;
import java.util.regex.Pattern;

/** Explicit remote tombstone attribute/value used for controlled Jira Assets deletion propagation. */
public record JiraAssetsTombstoneSettings(String attributeId, String value) {
    private static final Pattern PROVIDER_ID = Pattern.compile("^[A-Za-z0-9-]{1,128}$");

    public JiraAssetsTombstoneSettings {
        if (attributeId == null || !PROVIDER_ID.matcher(attributeId).matches()) {
            throw new ConfigurationException("Jira Assets tombstone attributeId is invalid");
        }
        value = strictValue(value);
    }

    private static String strictValue(String value) {
        Objects.requireNonNull(value, "value");
        if (!value.equals(value.strip()) || value.isEmpty() || value.length() > 4096
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new ConfigurationException("Jira Assets tombstone value is invalid");
        }
        return value;
    }
}
