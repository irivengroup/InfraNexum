package io.infranexum.integrations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable fail-closed governance policy for one configured connector. */
public record ConnectorGovernancePolicy(
        ConnectorKey connectorKey,
        String provider,
        ConnectorSyncDirection direction,
        ConnectorDataAuthority authority,
        ConnectorConflictStrategy conflictStrategy,
        ConnectorDeletionPolicy deletionPolicy,
        ConnectorRollbackStrategy rollbackStrategy,
        boolean executionEnabled,
        List<ConnectorFieldAuthority> fields) {

    /**
     * Compatibility constructor for policies created before explicit execution admission existed.
     * Existing mutating policies keep their historical executable semantics; federated reads remain non-executable.
     */
    public ConnectorGovernancePolicy(
            ConnectorKey connectorKey,
            String provider,
            ConnectorSyncDirection direction,
            ConnectorDataAuthority authority,
            ConnectorConflictStrategy conflictStrategy,
            ConnectorDeletionPolicy deletionPolicy,
            ConnectorRollbackStrategy rollbackStrategy,
            List<ConnectorFieldAuthority> fields) {
        this(connectorKey, provider, direction, authority, conflictStrategy, deletionPolicy, rollbackStrategy,
                direction != null && direction.mutating(), fields);
    }

    public ConnectorGovernancePolicy {
        Objects.requireNonNull(connectorKey, "connectorKey");
        if (provider == null || !provider.matches("^[a-z][a-z0-9-]{0,79}$")) {
            throw new IllegalArgumentException("connector governance provider is invalid");
        }
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(conflictStrategy, "conflictStrategy");
        Objects.requireNonNull(deletionPolicy, "deletionPolicy");
        Objects.requireNonNull(rollbackStrategy, "rollbackStrategy");
        List<ConnectorFieldAuthority> requested = List.copyOf(
                Objects.requireNonNullElse(fields, List.<ConnectorFieldAuthority>of()));
        Map<String, ConnectorFieldAuthority> unique = new LinkedHashMap<>();
        for (ConnectorFieldAuthority field : requested) {
            ConnectorFieldAuthority nonNull = Objects.requireNonNull(field, "connector governance field");
            ConnectorFieldAuthority previous = unique.putIfAbsent(nonNull.field(), nonNull);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate connector governance field: " + nonNull.field());
            }
        }
        fields = List.copyOf(unique.values());

        if (direction == ConnectorSyncDirection.FEDERATED_READ) {
            if (executionEnabled) {
                throw new IllegalArgumentException("federated-read cannot enable synchronization execution");
            }
            if (authority != ConnectorDataAuthority.EXTERNAL) {
                throw new IllegalArgumentException("federated-read requires EXTERNAL authority");
            }
            if (rollbackStrategy != ConnectorRollbackStrategy.NONE_REQUIRED) {
                throw new IllegalArgumentException("federated-read requires NONE_REQUIRED rollback");
            }
            if (deletionPolicy != ConnectorDeletionPolicy.IGNORE) {
                throw new IllegalArgumentException("federated-read cannot propagate deletions");
            }
            if (!fields.isEmpty()) {
                throw new IllegalArgumentException("federated-read must not declare local field mappings");
            }
        } else {
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("mutating connector direction requires field-level authority mappings");
            }
            if (rollbackStrategy == ConnectorRollbackStrategy.NONE_REQUIRED) {
                throw new IllegalArgumentException("mutating connector direction requires an explicit rollback strategy");
            }
            if (direction.mutatesLocal()
                    && rollbackStrategy != ConnectorRollbackStrategy.LOCAL_CHECKPOINT
                    && rollbackStrategy != ConnectorRollbackStrategy.DUAL_COMPENSATION
                    && rollbackStrategy != ConnectorRollbackStrategy.MANUAL) {
                throw new IllegalArgumentException(
                        "local mutation requires local checkpoint, dual compensation or manual rollback");
            }
            if (direction.mutatesRemote()
                    && rollbackStrategy != ConnectorRollbackStrategy.REMOTE_COMPENSATION
                    && rollbackStrategy != ConnectorRollbackStrategy.DUAL_COMPENSATION
                    && rollbackStrategy != ConnectorRollbackStrategy.MANUAL) {
                throw new IllegalArgumentException(
                        "remote mutation requires remote compensation, dual compensation or manual rollback");
            }
        }
    }

    /** Default provider contract: external authority, federated read and no synchronization execution. */
    public static ConnectorGovernancePolicy externalFederatedRead(ConnectorKey connectorKey, String provider) {
        return new ConnectorGovernancePolicy(
                connectorKey,
                provider,
                ConnectorSyncDirection.FEDERATED_READ,
                ConnectorDataAuthority.EXTERNAL,
                ConnectorConflictStrategy.REJECT,
                ConnectorDeletionPolicy.IGNORE,
                ConnectorRollbackStrategy.NONE_REQUIRED,
                false,
                List.of());
    }
}
