package io.infranexum.adapters.jiraassets;

import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.integrations.ConnectorFieldAuthority;
import io.infranexum.integrations.ConnectorDataAuthority;
import io.infranexum.integrations.ConnectorKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Explicit Jira Assets write mapping for one connector.
 *
 * <p>The local UUID field is deliberately the only supported identity source in this phase. This
 * keeps retry-after-create deterministic and prevents an operator-selected free-text field from
 * becoming an AQL injection boundary.</p>
 */
public record JiraAssetsMutationSettings(
        ConnectorKey connectorKey,
        String objectTypeId,
        String identityAttributeName,
        String identitySourceField,
        Map<String, String> attributeIds,
        int batchSize,
        JiraAssetsTombstoneSettings tombstone) {
    /** Compatibility constructor for mutation mappings created before controlled tombstones existed. */
    public JiraAssetsMutationSettings(
            ConnectorKey connectorKey,
            String objectTypeId,
            String identityAttributeName,
            String identitySourceField,
            Map<String, String> attributeIds,
            int batchSize) {
        this(connectorKey, objectTypeId, identityAttributeName, identitySourceField, attributeIds, batchSize, null);
    }

    private static final Pattern PROVIDER_ID = Pattern.compile("^[A-Za-z0-9-]{1,128}$");
    private static final Pattern AQL_ATTRIBUTE = Pattern.compile("^[A-Za-z][A-Za-z0-9 _.:-]{0,127}$");

    public JiraAssetsMutationSettings {
        Objects.requireNonNull(connectorKey, "connectorKey");
        objectTypeId = providerId(objectTypeId, "objectTypeId");
        if (identityAttributeName == null || !identityAttributeName.equals(identityAttributeName.strip())
                || !AQL_ATTRIBUTE.matcher(identityAttributeName).matches()) {
            throw new ConfigurationException("Jira Assets identityAttributeName is invalid");
        }
        identitySourceField = new ConnectorFieldAuthority(
                Objects.requireNonNull(identitySourceField, "identitySourceField"), ConnectorDataAuthority.INFRANEXUM).field();
        if (!"id".equals(identitySourceField)) {
            throw new ConfigurationException("Jira Assets outbound identitySourceField must be id");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        java.util.Set<String> providerAttributes = new java.util.HashSet<>();
        for (Map.Entry<String, String> entry : Objects.requireNonNull(attributeIds, "attributeIds").entrySet()) {
            String field = new ConnectorFieldAuthority(entry.getKey(), ConnectorDataAuthority.INFRANEXUM).field();
            String attributeId = providerId(entry.getValue(), "attribute id");
            if (normalized.putIfAbsent(field, attributeId) != null) {
                throw new ConfigurationException("duplicate Jira Assets mutation field: " + field);
            }
            if (!providerAttributes.add(attributeId)) {
                throw new ConfigurationException("duplicate Jira Assets mutation attribute id: " + attributeId);
            }
        }
        if (!normalized.containsKey(identitySourceField)) {
            throw new ConfigurationException("Jira Assets mutation attributes must include identitySourceField");
        }
        if (normalized.isEmpty() || normalized.size() > 64) {
            throw new ConfigurationException("Jira Assets mutation attributes must contain 1..64 entries");
        }
        attributeIds = Map.copyOf(normalized);
        if (tombstone != null && tombstone.attributeId().equals(attributeIds.get(identitySourceField))) {
            throw new ConfigurationException("Jira Assets tombstone attribute cannot overwrite the immutable identity attribute");
        }
        if (batchSize < 1 || batchSize > 200) {
            throw new ConfigurationException("Jira Assets mutation batchSize must be between 1 and 200");
        }
    }

    private static String providerId(String value, String field) {
        if (value == null || !value.equals(value.strip()) || !PROVIDER_ID.matcher(value).matches()) {
            throw new ConfigurationException("Jira Assets " + field + " is invalid");
        }
        return value;
    }
}
