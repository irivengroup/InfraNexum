package io.infranexum.server.integrations;

import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.adapters.jiraassets.JiraAssetsSettings;
import io.infranexum.adapters.servicenow.ServiceNowSettings;
import io.infranexum.core.events.ExponentialBackoffPolicy;
import io.infranexum.core.events.RetryPolicy;
import io.infranexum.integrations.ConnectorConflictStrategy;
import io.infranexum.integrations.ConnectorDataAuthority;
import io.infranexum.integrations.ConnectorDeletionPolicy;
import io.infranexum.integrations.ConnectorFieldAuthority;
import io.infranexum.integrations.ConnectorGovernancePolicy;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorRollbackStrategy;
import io.infranexum.integrations.ConnectorSyncDirection;
import io.infranexum.integrations.ConnectorWebhookEndpoint;
import io.infranexum.integrations.OutboundNotificationEndpoint;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
        JiraAssetsProperties jiraAssets,
        ServiceNowProperties serviceNow,
        NotificationsProperties notifications,
        Map<String, GovernanceProperties> governance) {

    /** Compatibility constructor for callers created before outbound notifications became configurable. */
    public IntegrationRuntimeProperties(
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
            JiraAssetsProperties jiraAssets,
            ServiceNowProperties serviceNow) {
        this(enabled, webhookMaxPayloadBytes, claimBatchSize, pollInterval, leaseDuration, maximumAttempts,
                initialRetryDelay, maximumRetryDelay, jitterRatio, suspendAfterDeadLetters, suspensionDuration,
                endpoints, jiraAssets, serviceNow, new NotificationsProperties(1_048_576, Map.of()), Map.of());
    }

    /** Compatibility constructor for callers created before connector governance became configurable. */
    public IntegrationRuntimeProperties(
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
            JiraAssetsProperties jiraAssets,
            ServiceNowProperties serviceNow,
            NotificationsProperties notifications) {
        this(enabled, webhookMaxPayloadBytes, claimBatchSize, pollInterval, leaseDuration, maximumAttempts,
                initialRetryDelay, maximumRetryDelay, jitterRatio, suspendAfterDeadLetters, suspensionDuration,
                endpoints, jiraAssets, serviceNow, notifications, Map.of());
    }

    @ConstructorBinding
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
        serviceNow = Objects.requireNonNullElseGet(serviceNow, () -> new ServiceNowProperties(2_097_152, Map.of()));
        notifications = Objects.requireNonNullElseGet(notifications, () -> new NotificationsProperties(1_048_576, Map.of()));
        Map<String, GovernanceProperties> normalizedGovernance = new LinkedHashMap<>();
        for (Map.Entry<String, GovernanceProperties> entry : Objects.requireNonNullElse(
                governance, Map.<String, GovernanceProperties>of()).entrySet()) {
            String key = new ConnectorKey(entry.getKey()).value();
            if (normalizedGovernance.putIfAbsent(
                    key, Objects.requireNonNull(entry.getValue(), "connector governance")) != null) {
                throw new ConfigurationException("duplicate normalized connector governance policy: " + key);
            }
        }
        if (normalizedGovernance.size() > 128) {
            throw new ConfigurationException("at most 128 connector governance policies may be configured per Server runtime");
        }
        governance = Map.copyOf(normalizedGovernance);
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

    Map<ConnectorKey, io.infranexum.adapters.jiraassets.JiraAssetsMutationSettings> jiraAssetsMutationDefinitions() {
        Map<ConnectorKey, io.infranexum.adapters.jiraassets.JiraAssetsMutationSettings> result = new LinkedHashMap<>();
        jiraAssets.connectors().forEach((key, value) -> {
            if (value.mutation() == null) return;
            ConnectorKey connectorKey = new ConnectorKey(key);
            JiraAssetsMutationProperties mutation = value.mutation();
            result.put(connectorKey, new io.infranexum.adapters.jiraassets.JiraAssetsMutationSettings(
                    connectorKey, mutation.objectTypeId(), mutation.identityAttributeName(), mutation.identitySourceField(),
                    mutation.attributeIds(), mutation.batchSize(), mutation.tombstone() == null ? null
                            : new io.infranexum.adapters.jiraassets.JiraAssetsTombstoneSettings(
                                    mutation.tombstone().attributeId(), mutation.tombstone().value())));
        });
        return Map.copyOf(result);
    }


    Map<ConnectorKey, ServiceNowSettings> serviceNowDefinitions() {
        Map<ConnectorKey, ServiceNowSettings> result = new LinkedHashMap<>();
        serviceNow.connectors().forEach((key, value) -> {
            ConnectorKey connectorKey = new ConnectorKey(key);
            result.put(connectorKey, new ServiceNowSettings(
                    connectorKey, value.instanceHost(), value.tableName(), value.bearerTokenReference(), value.requestTimeout(), value.enabled()));
        });
        return Map.copyOf(result);
    }


    Map<ConnectorKey, io.infranexum.adapters.servicenow.ServiceNowMutationSettings> serviceNowMutationDefinitions() {
        Map<ConnectorKey, io.infranexum.adapters.servicenow.ServiceNowMutationSettings> result = new LinkedHashMap<>();
        serviceNow.connectors().forEach((key, value) -> {
            if (value.mutation() == null) return;
            ConnectorKey connectorKey = new ConnectorKey(key);
            ServiceNowMutationProperties mutation = value.mutation();
            result.put(connectorKey, new io.infranexum.adapters.servicenow.ServiceNowMutationSettings(
                    connectorKey, mutation.identitySourceField(), mutation.fieldNames(), mutation.batchSize(),
                    mutation.tombstone() == null ? null
                            : new io.infranexum.adapters.servicenow.ServiceNowTombstoneSettings(
                                    mutation.tombstone().fieldName(), mutation.tombstone().value())));
        });
        return Map.copyOf(result);
    }


    Map<ConnectorKey, GovernanceProperties> governanceDefinitions() {
        Map<ConnectorKey, GovernanceProperties> result = new LinkedHashMap<>();
        governance.forEach((key, value) -> result.put(new ConnectorKey(key), value));
        return Map.copyOf(result);
    }


    Map<ConnectorKey, OutboundNotificationEndpoint> notificationEndpointDefinitions() {
        Map<ConnectorKey, OutboundNotificationEndpoint> result = new LinkedHashMap<>();
        notifications.endpoints().forEach((key, value) -> {
            ConnectorKey endpointKey = new ConnectorKey(key);
            result.put(endpointKey, new OutboundNotificationEndpoint(
                    endpointKey, URI.create(value.destination()), value.secretReference(), value.requestTimeout(), value.enabled()));
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
            boolean enabled,
            JiraAssetsMutationProperties mutation) {
        /** Compatibility constructor for read-only connector definitions predating provider mutation admission. */
        public JiraAssetsConnectorProperties(
                String cloudId, String workspaceId, String bearerTokenReference, Duration requestTimeout, boolean enabled) {
            this(cloudId, workspaceId, bearerTokenReference, requestTimeout, enabled, null);
        }

        public JiraAssetsConnectorProperties {
            new JiraAssetsSettings(new ConnectorKey("validation.connector"), cloudId, workspaceId, bearerTokenReference, requestTimeout, enabled);
            if (mutation != null) mutation.validate();
        }
    }

    /** Explicit Jira object type and attribute mapping required before outbound mutation can be registered. */
    public record JiraAssetsMutationProperties(
            String objectTypeId,
            String identityAttributeName,
            String identitySourceField,
            Map<String, String> attributeIds,
            int batchSize,
            JiraAssetsTombstoneProperties tombstone) {
        /** Compatibility constructor for configurations predating controlled tombstones. */
        public JiraAssetsMutationProperties(
                String objectTypeId,
                String identityAttributeName,
                String identitySourceField,
                Map<String, String> attributeIds,
                int batchSize) {
            this(objectTypeId, identityAttributeName, identitySourceField, attributeIds, batchSize, null);
        }

        private void validate() {
            new io.infranexum.adapters.jiraassets.JiraAssetsMutationSettings(
                    new ConnectorKey("validation.connector"), objectTypeId, identityAttributeName, identitySourceField,
                    attributeIds, batchSize, tombstone == null ? null
                            : new io.infranexum.adapters.jiraassets.JiraAssetsTombstoneSettings(
                                    tombstone.attributeId(), tombstone.value()));
        }
    }

    /** Explicit Jira Assets provider tombstone marker; no physical delete endpoint is used. */
    public record JiraAssetsTombstoneProperties(String attributeId, String value) {}

    /** ServiceNow provider configuration. Connector count is bounded to keep metric cardinality controlled. */
    public record ServiceNowProperties(int maximumResponseBytes, Map<String, ServiceNowConnectorProperties> connectors) {
        public ServiceNowProperties {
            if (maximumResponseBytes < 1 || maximumResponseBytes > 8_388_608) {
                throw new ConfigurationException("integrations.serviceNow.maximumResponseBytes must be between 1 and 8388608");
            }
            Map<String, ServiceNowConnectorProperties> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, ServiceNowConnectorProperties> entry : Objects.requireNonNullElse(connectors, Map.<String, ServiceNowConnectorProperties>of()).entrySet()) {
                String key = new ConnectorKey(entry.getKey()).value();
                if (normalized.putIfAbsent(key, Objects.requireNonNull(entry.getValue(), "ServiceNow connector")) != null) {
                    throw new ConfigurationException("duplicate normalized ServiceNow connector: " + key);
                }
            }
            if (normalized.size() > 64) throw new ConfigurationException("at most 64 ServiceNow connectors may be configured per Server runtime");
            connectors = Map.copyOf(normalized);
        }
    }

    /** One ServiceNow SaaS CMDB instance. Secrets remain external through a reference only. */
    public record ServiceNowConnectorProperties(
            String instanceHost,
            String tableName,
            String bearerTokenReference,
            Duration requestTimeout,
            boolean enabled,
            ServiceNowMutationProperties mutation) {
        /** Compatibility constructor for read-only connector definitions predating provider mutation admission. */
        public ServiceNowConnectorProperties(
                String instanceHost, String tableName, String bearerTokenReference, Duration requestTimeout, boolean enabled) {
            this(instanceHost, tableName, bearerTokenReference, requestTimeout, enabled, null);
        }

        public ServiceNowConnectorProperties {
            new ServiceNowSettings(new ConnectorKey("validation.connector"), instanceHost, tableName, bearerTokenReference, requestTimeout, enabled);
            if (mutation != null) mutation.validate();
        }
    }

    /** Exact local-to-CMDB field mapping required before ServiceNow outbound mutation can be registered. */
    public record ServiceNowMutationProperties(
            String identitySourceField,
            Map<String, String> fieldNames,
            int batchSize,
            ServiceNowTombstoneProperties tombstone) {
        /** Compatibility constructor for configurations predating controlled tombstones. */
        public ServiceNowMutationProperties(
                String identitySourceField,
                Map<String, String> fieldNames,
                int batchSize) {
            this(identitySourceField, fieldNames, batchSize, null);
        }

        private void validate() {
            new io.infranexum.adapters.servicenow.ServiceNowMutationSettings(
                    new ConnectorKey("validation.connector"), identitySourceField, fieldNames, batchSize,
                    tombstone == null ? null
                            : new io.infranexum.adapters.servicenow.ServiceNowTombstoneSettings(
                                    tombstone.fieldName(), tombstone.value()));
        }
    }

    /** Explicit ServiceNow provider tombstone marker; no physical delete endpoint is used. */
    public record ServiceNowTombstoneProperties(String fieldName, String value) {}

    /**
     * Explicit provider-independent authority mapping. A mutating mapping may be prepared with execution disabled;
     * enabling execution is admitted only when the provider connector is enabled and an approved handler exists.
     */
    public record GovernanceProperties(
            ConnectorSyncDirection direction,
            ConnectorDataAuthority authority,
            ConnectorConflictStrategy conflictStrategy,
            ConnectorDeletionPolicy deletionPolicy,
            ConnectorRollbackStrategy rollbackStrategy,
            boolean executionEnabled,
            Map<String, ConnectorDataAuthority> fields) {
        public GovernanceProperties {
            if (direction == null) throw new ConfigurationException("connector governance direction must be configured");
            if (authority == null) throw new ConfigurationException("connector governance authority must be configured");
            if (conflictStrategy == null) throw new ConfigurationException("connector governance conflictStrategy must be configured");
            if (deletionPolicy == null) throw new ConfigurationException("connector governance deletionPolicy must be configured");
            if (rollbackStrategy == null) throw new ConfigurationException("connector governance rollbackStrategy must be configured");
            Map<String, ConnectorDataAuthority> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, ConnectorDataAuthority> entry : Objects.requireNonNullElse(
                    fields, Map.<String, ConnectorDataAuthority>of()).entrySet()) {
                ConnectorFieldAuthority field;
                try {
                    field = new ConnectorFieldAuthority(entry.getKey(), Objects.requireNonNull(entry.getValue(), "field authority"));
                } catch (RuntimeException invalid) {
                    throw new ConfigurationException("invalid connector governance field mapping: " + invalid.getMessage());
                }
                if (normalized.putIfAbsent(field.field(), field.authority()) != null) {
                    throw new ConfigurationException("duplicate connector governance field mapping: " + field.field());
                }
            }
            if (normalized.size() > 512) {
                throw new ConfigurationException("at most 512 field authority mappings may be configured per connector");
            }
            fields = Map.copyOf(normalized);
        }

        ConnectorGovernancePolicy toPolicy(ConnectorKey connectorKey, String provider) {
            List<ConnectorFieldAuthority> fieldAuthorities = fields.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new ConnectorFieldAuthority(entry.getKey(), entry.getValue()))
                    .toList();
            try {
                return new ConnectorGovernancePolicy(
                        connectorKey, provider, direction, authority, conflictStrategy, deletionPolicy, rollbackStrategy,
                        executionEnabled, fieldAuthorities);
            } catch (IllegalArgumentException invalid) {
                throw new ConfigurationException(
                        "invalid connector governance policy for " + connectorKey.value() + ": " + invalid.getMessage());
            }
        }
    }

    /** Outbound notification configuration with bounded endpoint cardinality. */
    public record NotificationsProperties(int maximumPayloadBytes, Map<String, NotificationEndpointProperties> endpoints) {
        public NotificationsProperties {
            if (maximumPayloadBytes < 1 || maximumPayloadBytes > 1_048_576) {
                throw new ConfigurationException("integrations.notifications.maximumPayloadBytes must be between 1 and 1048576");
            }
            Map<String, NotificationEndpointProperties> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, NotificationEndpointProperties> entry : Objects.requireNonNullElse(endpoints, Map.<String, NotificationEndpointProperties>of()).entrySet()) {
                String key = new ConnectorKey(entry.getKey()).value();
                if (normalized.putIfAbsent(key, Objects.requireNonNull(entry.getValue(), "notification endpoint")) != null) {
                    throw new ConfigurationException("duplicate normalized notification endpoint: " + key);
                }
            }
            if (normalized.size() > 64) throw new ConfigurationException("at most 64 notification endpoints may be configured per Server runtime");
            endpoints = Map.copyOf(normalized);
        }
    }

    /** One signed HTTPS notification destination; the secret itself is never configured here. */
    public record NotificationEndpointProperties(String destination, String secretReference, Duration requestTimeout, boolean enabled) {
        public NotificationEndpointProperties {
            try {
                new OutboundNotificationEndpoint(new ConnectorKey("validation.endpoint"), URI.create(destination), secretReference, requestTimeout, enabled);
            } catch (RuntimeException invalid) {
                throw new ConfigurationException("invalid notification endpoint: " + invalid.getMessage());
            }
        }
    }


}
