package io.infranexum.server.integrations;

import io.infranexum.adapters.jiraassets.JiraAssetsSettings;
import io.infranexum.adapters.servicenow.ServiceNowSettings;
import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.integrations.ConnectorGovernanceNotFoundException;
import io.infranexum.integrations.ConnectorGovernancePolicy;
import io.infranexum.integrations.ConnectorGovernanceRegistry;
import io.infranexum.integrations.ConnectorKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Unified governance registry for every configured provider connector known by the Server. */
final class ConfiguredConnectorGovernanceRegistry implements ConnectorGovernanceRegistry {
    private final Map<ConnectorKey, ConnectorGovernancePolicy> policies;

    /** Compatibility constructor preserving the historical read-only defaults. */
    ConfiguredConnectorGovernanceRegistry(
            Map<ConnectorKey, JiraAssetsSettings> jiraAssets,
            Map<ConnectorKey, ServiceNowSettings> serviceNow) {
        this(jiraAssets, serviceNow, Map.of());
    }

    ConfiguredConnectorGovernanceRegistry(
            Map<ConnectorKey, JiraAssetsSettings> jiraAssets,
            Map<ConnectorKey, ServiceNowSettings> serviceNow,
            Map<ConnectorKey, IntegrationRuntimeProperties.GovernanceProperties> governance) {
        Map<ConnectorKey, IntegrationRuntimeProperties.GovernanceProperties> overrides =
                Map.copyOf(Objects.requireNonNullElse(
                        governance, Map.<ConnectorKey, IntegrationRuntimeProperties.GovernanceProperties>of()));
        Map<ConnectorKey, ConnectorGovernancePolicy> normalized = new LinkedHashMap<>();
        Map<ConnectorKey, String> configuredProviders = new LinkedHashMap<>();

        Objects.requireNonNullElse(jiraAssets, Map.<ConnectorKey, JiraAssetsSettings>of()).forEach((key, settings) -> {
            JiraAssetsSettings nonNull = Objects.requireNonNull(settings, "Jira Assets settings");
            addProvider(configuredProviders, key, JiraAssetsSettings.PROVIDER);
            add(normalized, key, policy(key, JiraAssetsSettings.PROVIDER, nonNull.enabled(), overrides.get(key)));
        });
        Objects.requireNonNullElse(serviceNow, Map.<ConnectorKey, ServiceNowSettings>of()).forEach((key, settings) -> {
            ServiceNowSettings nonNull = Objects.requireNonNull(settings, "ServiceNow settings");
            addProvider(configuredProviders, key, ServiceNowSettings.PROVIDER);
            add(normalized, key, policy(key, ServiceNowSettings.PROVIDER, nonNull.enabled(), overrides.get(key)));
        });

        for (ConnectorKey key : overrides.keySet()) {
            if (!configuredProviders.containsKey(key)) {
                throw new ConfigurationException(
                        "connector governance policy references an unknown provider connector: " + key.value());
            }
        }
        this.policies = Map.copyOf(normalized);
    }

    private static ConnectorGovernancePolicy policy(
            ConnectorKey key,
            String provider,
            boolean providerEnabled,
            IntegrationRuntimeProperties.GovernanceProperties override) {
        if (override == null) return ConnectorGovernancePolicy.externalFederatedRead(key, provider);
        if (override.executionEnabled() && !providerEnabled) {
            throw new ConfigurationException(
                    "connector governance execution cannot be enabled for a disabled provider connector: " + key.value());
        }
        return override.toPolicy(key, provider);
    }

    private static void addProvider(Map<ConnectorKey, String> target, ConnectorKey key, String provider) {
        ConnectorKey nonNullKey = Objects.requireNonNull(key, "connectorKey");
        if (target.putIfAbsent(nonNullKey, provider) != null) {
            throw new ConfigurationException(
                    "duplicate connector key across provider governance registry: " + nonNullKey.value());
        }
    }

    private static void add(
            Map<ConnectorKey, ConnectorGovernancePolicy> target,
            ConnectorKey key,
            ConnectorGovernancePolicy policy) {
        if (target.putIfAbsent(Objects.requireNonNull(key, "connectorKey"), Objects.requireNonNull(policy, "policy")) != null) {
            throw new ConfigurationException("duplicate connector key across provider governance registry: " + key.value());
        }
    }

    @Override
    public List<ConnectorGovernancePolicy> policies() {
        List<ConnectorGovernancePolicy> result = new ArrayList<>(policies.values());
        result.sort(Comparator.comparing(policy -> policy.connectorKey().value()));
        return List.copyOf(result);
    }

    @Override
    public ConnectorGovernancePolicy require(ConnectorKey connectorKey) {
        ConnectorGovernancePolicy policy = policies.get(Objects.requireNonNull(connectorKey, "connectorKey"));
        if (policy == null) throw new ConnectorGovernanceNotFoundException("connector governance policy was not found");
        return policy;
    }
}
