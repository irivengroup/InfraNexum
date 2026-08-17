package io.infranexum.adapters.servicenow;

import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.integrations.ConnectorKey;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable, secret-free configuration for one ServiceNow SaaS CMDB connector instance. */
public record ServiceNowSettings(
        ConnectorKey connectorKey,
        String instanceHost,
        String tableName,
        String bearerTokenReference,
        Duration requestTimeout,
        boolean enabled) {
    private static final Pattern INSTANCE_HOST = Pattern.compile(
            "(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+service-now\\.com");
    private static final Pattern CMDB_TABLE = Pattern.compile("cmdb_ci(?:_[a-z0-9_]{1,56})?");
    public static final String PROVIDER = "service-now";
    public static final String DIRECTION = "FEDERATED_READ";
    public static final String AUTHORITY = "EXTERNAL";

    public ServiceNowSettings {
        Objects.requireNonNull(connectorKey, "connectorKey");
        instanceHost = requireInstanceHost(instanceHost);
        tableName = requireTableName(tableName);
        if (bearerTokenReference == null
                || !(bearerTokenReference.startsWith("env:") || bearerTokenReference.startsWith("file:"))) {
            throw new ConfigurationException("ServiceNow bearerTokenReference must use env: or file:");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                || requestTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new ConfigurationException("ServiceNow requestTimeout must be in (0s, 60s]");
        }
    }

    private static String requireInstanceHost(String value) {
        if (value == null) throw new ConfigurationException("ServiceNow instanceHost is required");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!normalized.equals(value) || !INSTANCE_HOST.matcher(normalized).matches()) {
            throw new ConfigurationException("ServiceNow instanceHost must be a lowercase *.service-now.com hostname");
        }
        return normalized;
    }

    private static String requireTableName(String value) {
        if (value == null) throw new ConfigurationException("ServiceNow tableName is required");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!normalized.equals(value) || !CMDB_TABLE.matcher(normalized).matches()) {
            throw new ConfigurationException("ServiceNow tableName must be cmdb_ci or a cmdb_ci_* subclass");
        }
        return normalized;
    }
}
