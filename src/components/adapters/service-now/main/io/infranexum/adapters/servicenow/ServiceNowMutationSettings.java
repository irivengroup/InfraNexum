package io.infranexum.adapters.servicenow;

import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.integrations.ConnectorDataAuthority;
import io.infranexum.integrations.ConnectorFieldAuthority;
import io.infranexum.integrations.ConnectorKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Explicit ServiceNow CMDB field mapping required before governed outbound mutation is admitted. */
public record ServiceNowMutationSettings(
        ConnectorKey connectorKey,
        String identitySourceField,
        Map<String, String> fieldNames,
        int batchSize,
        ServiceNowTombstoneSettings tombstone) {
    /** Compatibility constructor for mutation mappings created before controlled tombstones existed. */
    public ServiceNowMutationSettings(
            ConnectorKey connectorKey,
            String identitySourceField,
            Map<String, String> fieldNames,
            int batchSize) {
        this(connectorKey, identitySourceField, fieldNames, batchSize, null);
    }

    private static final Pattern COLUMN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final Pattern CUSTOM_IDENTITY_COLUMN = Pattern.compile("^u_[a-z0-9_]{1,61}$");
    private static final Set<String> RESERVED_COLUMNS = Set.of(
            "sys_id", "sys_class_name", "sys_created_by", "sys_created_on",
            "sys_updated_by", "sys_updated_on", "sys_mod_count", "sys_tags");

    public ServiceNowMutationSettings {
        Objects.requireNonNull(connectorKey, "connectorKey");
        identitySourceField = new ConnectorFieldAuthority(
                Objects.requireNonNull(identitySourceField, "identitySourceField"),
                ConnectorDataAuthority.INFRANEXUM).field();
        if (!"id".equals(identitySourceField)) {
            throw new ConfigurationException("ServiceNow outbound identitySourceField must be id");
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        java.util.Set<String> providerColumns = new java.util.HashSet<>();
        for (Map.Entry<String, String> entry : Objects.requireNonNull(fieldNames, "fieldNames").entrySet()) {
            String field = new ConnectorFieldAuthority(entry.getKey(), ConnectorDataAuthority.INFRANEXUM).field();
            String column = providerColumn(entry.getValue());
            if (normalized.putIfAbsent(field, column) != null) {
                throw new ConfigurationException("duplicate ServiceNow mutation field: " + field);
            }
            if (!providerColumns.add(column)) {
                throw new ConfigurationException("duplicate ServiceNow mutation column: " + column);
            }
        }
        if (normalized.isEmpty() || normalized.size() > 64) {
            throw new ConfigurationException("ServiceNow mutation fields must contain 1..64 entries");
        }
        String identityColumn = normalized.get(identitySourceField);
        if (identityColumn == null) {
            throw new ConfigurationException("ServiceNow mutation fields must include identitySourceField");
        }
        if (!CUSTOM_IDENTITY_COLUMN.matcher(identityColumn).matches()) {
            throw new ConfigurationException("ServiceNow outbound identity must map to a custom u_* column");
        }
        if (tombstone != null && tombstone.fieldName().equals(identityColumn)) {
            throw new ConfigurationException("ServiceNow tombstone field cannot overwrite the immutable identity column");
        }
        fieldNames = Map.copyOf(normalized);
        if (batchSize < 1 || batchSize > 200) {
            throw new ConfigurationException("ServiceNow mutation batchSize must be between 1 and 200");
        }
    }

    /** Provider column containing the immutable canonical InfraNexum asset UUID. */
    public String identityField() {
        return fieldNames.get(identitySourceField);
    }

    static String providerColumn(String value) {
        if (value == null || !value.equals(value.strip()) || !COLUMN.matcher(value).matches()
                || RESERVED_COLUMNS.contains(value) || value.startsWith("sys_")) {
            throw new ConfigurationException("ServiceNow mutation column is invalid or reserved");
        }
        return value;
    }
}
