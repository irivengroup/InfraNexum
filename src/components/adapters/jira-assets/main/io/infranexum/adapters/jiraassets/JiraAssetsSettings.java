package io.infranexum.adapters.jiraassets;

import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.integrations.ConnectorKey;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable, secret-free configuration for one Jira Assets Cloud connector instance. */
public record JiraAssetsSettings(
        ConnectorKey connectorKey,
        String cloudId,
        String workspaceId,
        String bearerTokenReference,
        Duration requestTimeout,
        boolean enabled) {
    private static final Pattern ATLASSIAN_ID = Pattern.compile("[A-Za-z0-9-]{1,128}");
    public static final String PROVIDER = "jira-assets";
    public static final String DIRECTION = "FEDERATED_READ";
    public static final String AUTHORITY = "EXTERNAL";

    public JiraAssetsSettings {
        Objects.requireNonNull(connectorKey, "connectorKey");
        cloudId = requireProviderId(cloudId, "cloudId");
        workspaceId = requireProviderId(workspaceId, "workspaceId");
        if (bearerTokenReference == null
                || !(bearerTokenReference.startsWith("env:") || bearerTokenReference.startsWith("file:"))) {
            throw new ConfigurationException("Jira Assets bearerTokenReference must use env: or file:");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                || requestTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new ConfigurationException("Jira Assets requestTimeout must be in (0s, 60s]");
        }
    }

    private static String requireProviderId(String value, String field) {
        if (value == null) throw new ConfigurationException("Jira Assets " + field + " is required");
        String normalized = value.strip();
        if (!normalized.equals(value) || !ATLASSIAN_ID.matcher(normalized).matches()) {
            throw new ConfigurationException("Jira Assets " + field + " is invalid");
        }
        return normalized;
    }
}
