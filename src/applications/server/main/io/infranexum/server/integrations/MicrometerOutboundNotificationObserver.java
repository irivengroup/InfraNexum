package io.infranexum.server.integrations;

import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.OutboundNotificationEndpointRegistry;
import io.infranexum.integrations.OutboundNotificationRepository;
import io.infranexum.integrations.OutboundNotificationRuntimeObserver;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;

/** Low-cardinality metrics for the durable notification outbox and DLQ. */
final class MicrometerOutboundNotificationObserver implements OutboundNotificationRuntimeObserver {
    private static final String PREFIX = "infranexum.integrations.notifications.";
    private final MeterRegistry registry;

    MicrometerOutboundNotificationObserver(
            MeterRegistry registry,
            OutboundNotificationRepository repository,
            OutboundNotificationEndpointRegistry endpoints,
            @Qualifier("platformClock") Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(endpoints, "endpoints");
        Objects.requireNonNull(clock, "clock");
        for (var endpoint : endpoints.endpoints()) {
            ConnectorKey key = endpoint.endpointKey();
            Gauge.builder(PREFIX + "backlog", repository, value -> value.backlogSize(key, clock.instant()))
                    .tag("endpoint", key.value()).register(registry);
            Gauge.builder(PREFIX + "dead_letters", repository, value -> value.deadLetterCount(key))
                    .tag("endpoint", key.value()).register(registry);
        }
    }

    @Override public void admitted(ConnectorKey key, boolean duplicate) { counter("admissions", key, duplicate ? "duplicate" : "accepted"); }
    @Override public void delivered(ConnectorKey key) { counter("deliveries", key, "delivered"); }
    @Override public void failed(ConnectorKey key, boolean deadLetter) { counter("deliveries", key, deadLetter ? "dead_letter" : "retry"); }
    @Override public void replayed(ConnectorKey key) { counter("replays", key, "requested"); }

    private void counter(String metric, ConnectorKey key, String outcome) {
        registry.counter(PREFIX + metric, "endpoint", key.value(), "outcome", outcome).increment();
    }
}
