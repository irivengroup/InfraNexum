package io.infranexum.server.integrations;

import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.adapters.jiraassets.JiraAssetsSettings;
import io.infranexum.core.events.ExponentialBackoffPolicy;
import io.infranexum.core.events.RetryPolicy;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorWebhookEndpoint;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated Server settings for the PGM-10-E05 runtime and governed PGM-10-E06 providers. */
@Validated
@ConfigurationProperties(prefix = "infranexum.integrations")
public record IntegrationRuntimeProperties(
        boolean enabled,
        int webhookMaxPayloadBytes,
        int claimBatchSize,
        Duration pollInterval,
        Duration leaseDuration,
        int maximumAttempts,
        Duration initialRetryDelay,
        Duration maximumRetryDelay,
        double jitterRatio,
        int suspendAfterDeadLetters,
        Duration suspensionDuration,
        Map<String, EndpointProperties> endpoints,
        JiraAssetsProperties jiraAssets) {

    public IntegrationRuntimeProperties {
        if (webhookMaxPayloadBytes < 1 || webhookMaxPayloadBytes > 1_048_576) throw new ConfigurationException("integrations.webhookMaxPayloadBytes must be between 1 and 1048576");
        if (claimBatchSize < 1 || claimBatchSize > 1_000) throw new ConfigurationException("integrations.claimBatchSize must be between 1 and 1000");
        positive(pollInterval, "integrations.pollInterval"); positive(leaseDuration, "integrations.leaseDuration"); positive(suspensionDuration, "integrations.suspensionDuration");
        if (suspendAfterDeadLetters < 1 || suspendAfterDeadLetters > 100) throw new ConfigurationException("integrations.suspendAfterDeadLetters must be between 1 and 100");
        try { new ExponentialBackoffPolicy(maximumAttempts, initialRetryDelay, maximumRetryDelay, jitterRatio, () -> 0.0d); }
        catch (IllegalArgumentException invalid) { throw new ConfigurationException("invalid integration retry policy: " + invalid.getMessage()); }
        Map<String, EndpointProperties> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, EndpointProperties> entry : Objects.requireNonNullElse(endpoints, Map.<String, EndpointProperties>of()).entrySet()) {
            String key = new ConnectorKey(entry.getKey()).value();
            if (normalized.putIfAbsent(key, Objects.requireNonNull(entry.getValue(), "integration endpoint")) != null) throw new ConfigurationException("duplicate normalized integration endpoint: " + key);
        }
        endpoints = Map.copyOf(normalized);
        jiraAssets = Objects.requireNonNullElseGet(jiraAssets, () -> new JiraAssetsProperties(2_097_152, Map.of()));
    }

    RetryPolicy retryPolicy() {
        return new ExponentialBackoffPolicy(maximumAttempts, initialRetryDelay, maximumRetryDelay, jitterRatio, () -> ThreadLocalRandom.current().nextDouble());
    }

    Map<ConnectorKey, ConnectorWebhookEndpoint> endpointDefinitions() {
        Map<ConnectorKey, ConnectorWebhookEndpoint> result = new LinkedHashMap<>();
        endpoints.forEach((key, value) -> {
            ConnectorKey connectorKey = new ConnectorKey(key);
            result.put(connectorKey, new ConnectorWebhookEndpoint(connectorKey, value.handlerName(), value.secretReference(), value.maximumClockSkew(), value.enabled()));
        });
        return Map.copyOf(result);
    }


    Map<ConnectorKey, JiraAssetsSettings> jiraAssetsDefinitions() {
        Map<ConnectorKey, JiraAssetsSettings> result = new LinkedHashMap<>();
        jiraAssets.connectors().forEach((key, value) -> {
            ConnectorKey connectorKey = new ConnectorKey(key);
            result.put(connectorKey, new JiraAssetsSettings(
                    connectorKey, value.cloudId(), value.workspaceId(), value.bearerTokenReference(), value.requestTimeout(), value.enabled()));
        });
        return Map.copyOf(result);
    }

    private static void positive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) throw new ConfigurationException(field + " must be positive");
    }

    /** One secret-free endpoint definition; secretReference accepts env:NAME or file:/absolute/path. */
    public record EndpointProperties(String handlerName, String secretReference, Duration maximumClockSkew, boolean enabled) {
        public EndpointProperties {
            if (handlerName == null || handlerName.isBlank()) throw new ConfigurationException("integration endpoint handlerName must not be blank");
            if (secretReference == null || !(secretReference.startsWith("env:") || secretReference.startsWith("file:"))) throw new ConfigurationException("integration endpoint secretReference must use env: or file:");
            if (maximumClockSkew == null) throw new ConfigurationException("integration endpoint maximumClockSkew must be configured");
        }
    }

    /** Jira Assets provider configuration. Connector count is bounded to keep metric cardinality controlled. */
    public record JiraAssetsProperties(int maximumResponseBytes, Map<String, JiraAssetsConnectorProperties> connectors) {
        public JiraAssetsProperties {
            if (maximumResponseBytes < 1 || maximumResponseBytes > 8_388_608) {
                throw new ConfigurationException("integrations.jiraAssets.maximumResponseBytes must be between 1 and 8388608");
            }
            Map<String, JiraAssetsConnectorProperties> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, JiraAssetsConnectorProperties> entry : Objects.requireNonNullElse(connectors, Map.<String, JiraAssetsConnectorProperties>of()).entrySet()) {
                String key = new ConnectorKey(entry.getKey()).value();
                if (normalized.putIfAbsent(key, Objects.requireNonNull(entry.getValue(), "Jira Assets connector")) != null) {
                    throw new ConfigurationException("duplicate normalized Jira Assets connector: " + key);
                }
            }
            if (normalized.size() > 64) throw new ConfigurationException("at most 64 Jira Assets connectors may be configured per Server runtime");
            connectors = Map.copyOf(normalized);
        }
    }

    /** One Jira Assets Cloud instance. Secrets remain external through a reference only. */
    public record JiraAssetsConnectorProperties(
            String cloudId,
            String workspaceId,
            String bearerTokenReference,
            Duration requestTimeout,
            boolean enabled) {
        public JiraAssetsConnectorProperties {
            new JiraAssetsSettings(new ConnectorKey("validation.connector"), cloudId, workspaceId, bearerTokenReference, requestTimeout, enabled);
        }
    }

}
