package io.infranexum.server.integrations;

import io.infranexum.adapters.servicenow.ServiceNowAuthenticationException;
import io.infranexum.adapters.servicenow.ServiceNowConnector;
import io.infranexum.adapters.servicenow.ServiceNowProtocolException;
import io.infranexum.adapters.servicenow.ServiceNowRateLimitedException;
import io.infranexum.adapters.servicenow.ServiceNowSettings;
import io.infranexum.adapters.servicenow.ServiceNowUnavailableException;
import io.infranexum.core.audit.AuditEntry;
import io.infranexum.core.audit.AuditJournal;
import io.infranexum.core.audit.AuditScope;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;

/** Audited and observable application service for governed ServiceNow CMDB federated reads. */
final class ServiceNowOperationsService {
    private final ConfiguredServiceNowConnectorRegistry registry;
    private final AuditJournal audit;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final MeterRegistry meters;

    ServiceNowOperationsService(
            ConfiguredServiceNowConnectorRegistry registry,
            AuditJournal audit,
            UuidV7Generator ids,
            @Qualifier("platformClock") Clock clock,
            MeterRegistry meters) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    List<ConnectorDescriptor> connectors() {
        return registry.settings().stream().map(ConnectorDescriptor::from).toList();
    }

    ServiceNowConnector.Health health(String connectorKey, DomainIdentifier actor, DomainIdentifier correlation) {
        return invoke(connectorKey, "health", actor, correlation, () -> registry.require(connectorKey).health());
    }

    ServiceNowConnector.ConfigurationItemPage search(
            String connectorKey,
            String query,
            int offset,
            int limit,
            DomainIdentifier actor,
            DomainIdentifier correlation) {
        return invoke(connectorKey, "search", actor, correlation,
                () -> registry.require(connectorKey).search(query, offset, limit));
    }

    private <T> T invoke(
            String connectorKey,
            String operation,
            DomainIdentifier actor,
            DomainIdentifier correlation,
            java.util.function.Supplier<T> call) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        Timer.Sample sample = Timer.start(meters);
        String outcome = "success";
        try {
            T result = call.get();
            audit(actor, correlation, operation, connectorKey, "SUCCESS", null);
            return result;
        } catch (RuntimeException failure) {
            outcome = outcome(failure);
            audit(actor, correlation, operation, connectorKey, "FAILURE", outcome);
            throw failure;
        } finally {
            sample.stop(Timer.builder("infranexum.integrations.provider.latency")
                    .description("Outbound integration provider request latency")
                    .tag("provider", ServiceNowSettings.PROVIDER)
                    .tag("connector", safeConnectorMetric(connectorKey))
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .register(meters));
        }
    }

    private void audit(
            DomainIdentifier actor,
            DomainIdentifier correlation,
            String operation,
            String connectorKey,
            String result,
            String failureClass) {
        Map<String, String> metadata = failureClass == null
                ? Map.of("provider", ServiceNowSettings.PROVIDER, "direction", ServiceNowSettings.DIRECTION, "authority", ServiceNowSettings.AUTHORITY)
                : Map.of("provider", ServiceNowSettings.PROVIDER, "direction", ServiceNowSettings.DIRECTION, "authority", ServiceNowSettings.AUTHORITY, "failure_class", failureClass);
        audit.append(new AuditEntry(
                ids.next(), AuditScope.platform(), actor.toString(), "USER",
                "integrations.service-now." + operation,
                "integration_connector", safeConnectorMetric(connectorKey),
                result.equals("SUCCESS") ? "ALLOW" : "ERROR",
                clock.instant(), correlation, result, "HTTP", null, null, null, metadata, "ELEVATED"));
    }

    private static String outcome(RuntimeException failure) {
        if (failure instanceof ServiceNowAuthenticationException) return "authentication";
        if (failure instanceof ServiceNowRateLimitedException) return "rate_limited";
        if (failure instanceof ServiceNowUnavailableException) return "unavailable";
        if (failure instanceof ServiceNowProtocolException) return "protocol";
        if (failure instanceof IllegalArgumentException) return "invalid_request";
        return "runtime";
    }

    private static String safeConnectorMetric(String connectorKey) {
        try { return new io.infranexum.integrations.ConnectorKey(connectorKey).value(); }
        catch (RuntimeException invalid) { return "invalid"; }
    }

    record ConnectorDescriptor(String connectorKey, String provider, String direction, String authority, boolean enabled) {
        static ConnectorDescriptor from(ServiceNowSettings settings) {
            return new ConnectorDescriptor(settings.connectorKey().value(), ServiceNowSettings.PROVIDER,
                    ServiceNowSettings.DIRECTION, ServiceNowSettings.AUTHORITY, settings.enabled());
        }
    }
}
