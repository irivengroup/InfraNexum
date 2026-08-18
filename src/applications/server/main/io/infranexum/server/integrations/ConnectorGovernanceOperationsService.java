package io.infranexum.server.integrations;

import io.infranexum.core.audit.AuditEntry;
import io.infranexum.core.audit.AuditJournal;
import io.infranexum.core.audit.AuditScope;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.integrations.ConnectorGovernancePlanner;
import io.infranexum.integrations.ConnectorGovernancePolicy;
import io.infranexum.integrations.ConnectorGovernanceRegistry;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorSyncPlan;
import io.infranexum.integrations.ConnectorSyncPlanRequest;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;

/** Audited application service for connector authority/sync/rollback governance dry-runs. */
final class ConnectorGovernanceOperationsService {
    private final ConnectorGovernanceRegistry registry;
    private final ConnectorGovernancePlanner planner;
    private final AuditJournal audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    ConnectorGovernanceOperationsService(
            ConnectorGovernanceRegistry registry,
            ConnectorGovernancePlanner planner,
            AuditJournal audit,
            @Qualifier("integrationIdentifiers") UuidV7Generator ids,
            @Qualifier("platformClock") Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    List<ConnectorGovernancePolicy> policies() { return registry.policies(); }

    ConnectorGovernancePolicy require(String connectorKey) {
        return registry.require(new ConnectorKey(connectorKey));
    }

    ConnectorSyncPlan plan(
            String connectorKey,
            ConnectorSyncPlanRequest request,
            DomainIdentifier actor,
            DomainIdentifier correlation) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        ConnectorGovernancePolicy policy = require(connectorKey);
        ConnectorSyncPlan result = planner.plan(policy, Objects.requireNonNull(request, "request"));
        audit.append(new AuditEntry(
                ids.next(), AuditScope.platform(), actor.toString(), "USER",
                "integrations.connector-governance.plan", "integration_connector", policy.connectorKey().value(),
                result.decision() == ConnectorSyncPlan.Decision.ALLOW ? "ALLOW" : "DENY",
                clock.instant(), correlation, result.decision().name(), "HTTP", null, null, null,
                Map.of(
                        "provider", policy.provider(),
                        "configured_direction", policy.direction().name(),
                        "requested_direction", result.requestedDirection().name(),
                        "authority", policy.authority().name(),
                        "rollback", policy.rollbackStrategy().name(),
                        "execution_enabled", Boolean.toString(policy.executionEnabled())),
                "ELEVATED"));
        return result;
    }
}
