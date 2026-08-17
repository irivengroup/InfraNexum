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

    ConfiguredConnectorGovernanceRegistry(
            Map<ConnectorKey, JiraAssetsSettings> jiraAssets,
            Map<ConnectorKey, ServiceNowSettings> serviceNow) {
        Map<ConnectorKey, ConnectorGovernancePolicy> normalized = new LinkedHashMap<>();
        Objects.requireNonNullElse(jiraAssets, Map.<ConnectorKey, JiraAssetsSettings>of()).forEach((key, settings) ->
                add(normalized, key, ConnectorGovernancePolicy.externalFederatedRead(key, JiraAssetsSettings.PROVIDER)));
        Objects.requireNonNullElse(serviceNow, Map.<ConnectorKey, ServiceNowSettings>of()).forEach((key, settings) ->
                add(normalized, key, ConnectorGovernancePolicy.externalFederatedRead(key, ServiceNowSettings.PROVIDER)));
        this.policies = Map.copyOf(normalized);
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
