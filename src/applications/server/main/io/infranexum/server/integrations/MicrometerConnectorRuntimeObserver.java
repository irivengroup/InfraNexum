package io.infranexum.server.integrations;

import io.infranexum.integrations.ConnectorEndpointRegistry;
import io.infranexum.integrations.ConnectorInboxRepository;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorRuntimeObserver;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/** Low-cardinality metrics keyed only by configured connector keys. */
final class MicrometerConnectorRuntimeObserver implements ConnectorRuntimeObserver {
    private final MeterRegistry registry;
    MicrometerConnectorRuntimeObserver(MeterRegistry registry, ConnectorInboxRepository inbox, ConnectorEndpointRegistry endpoints, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(inbox, "inbox"); Objects.requireNonNull(clock, "clock");
        for (var endpoint : endpoints.endpoints()) {
            ConnectorKey key = endpoint.connectorKey();
            Gauge.builder("infranexum.integrations.backlog", inbox, repository -> repository.backlogSize(key, clock.instant())).tag("connector", key.value()).register(registry);
            Gauge.builder("infranexum.integrations.dead_letters", inbox, repository -> repository.deadLetterCount(key)).tag("connector", key.value()).register(registry);
        }
    }
    @Override public void admitted(ConnectorKey key, boolean duplicate) { counter("webhook.admissions", key, duplicate ? "duplicate" : "accepted"); }
    @Override public void rejected(ConnectorKey key, String reason) { registry.counter("infranexum.integrations.webhook.rejections", "connector", key.value(), "reason", safe(reason)).increment(); }
    @Override public void processed(ConnectorKey key, Duration latency) { counter("processing", key, "processed"); Timer.builder("infranexum.integrations.processing.latency").tag("connector", key.value()).register(registry).record(latency); }
    @Override public void retried(ConnectorKey key) { counter("processing", key, "retry"); }
    @Override public void deadLettered(ConnectorKey key) { counter("processing", key, "dead_letter"); }
    @Override public void replayed(ConnectorKey key) { counter("replays", key, "requested"); }
    @Override public void suspended(ConnectorKey key) { counter("suspensions", key, "automatic"); }
    private void counter(String metric, ConnectorKey key, String outcome) { registry.counter("infranexum.integrations." + metric, "connector", key.value(), "outcome", outcome).increment(); }
    private static String safe(String value) { return value == null || !value.matches("[A-Za-z][A-Za-z0-9]{0,63}") ? "runtime" : value; }
}
