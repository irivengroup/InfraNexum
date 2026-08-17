package io.infranexum.integrations;

import io.infranexum.core.events.RetryPolicy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Bounded at-least-once dispatcher with retry, DLQ and endpoint suspension. */
public final class OutboundNotificationDispatcher {
    private final OutboundNotificationRepository repository;
    private final OutboundNotificationEndpointRegistry endpoints;
    private final OutboundNotificationTransport transport;
    private final OutboundNotificationRuntimeObserver observer;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final String workerId;
    private final int batchSize;
    private final Duration leaseDuration;
    private final int suspendAfterDeadLetters;
    private final Duration suspensionDuration;

    public OutboundNotificationDispatcher(
            OutboundNotificationRepository repository,
            OutboundNotificationEndpointRegistry endpoints,
            OutboundNotificationTransport transport,
            OutboundNotificationRuntimeObserver observer,
            RetryPolicy retryPolicy,
            Clock clock,
            String workerId,
            int batchSize,
            Duration leaseDuration,
            int suspendAfterDeadLetters,
            Duration suspensionDuration) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.workerId = require(workerId, "workerId", 160);
        if (batchSize < 1 || batchSize > 1_000) throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        this.batchSize = batchSize;
        this.leaseDuration = positive(leaseDuration, "leaseDuration");
        if (suspendAfterDeadLetters < 1 || suspendAfterDeadLetters > 100) throw new IllegalArgumentException("suspendAfterDeadLetters must be between 1 and 100");
        this.suspendAfterDeadLetters = suspendAfterDeadLetters;
        this.suspensionDuration = positive(suspensionDuration, "suspensionDuration");
    }

    public int dispatchAvailable() {
        List<OutboundNotificationDelivery> batch = repository.claimBatch(workerId, batchSize, clock.instant(), leaseDuration);
        for (OutboundNotificationDelivery delivery : batch) dispatch(delivery);
        return batch.size();
    }

    private void dispatch(OutboundNotificationDelivery delivery) {
        try {
            OutboundNotificationEndpoint endpoint = endpoints.require(delivery.endpointKey());
            if (!endpoint.enabled()) throw new OutboundNotificationTransportException("NOTIFICATION_ENDPOINT_DISABLED", false);
            transport.deliver(endpoint, delivery);
            repository.markDelivered(delivery.deliveryId(), workerId, clock.instant());
            observer.delivered(delivery.endpointKey());
        } catch (RuntimeException failure) {
            boolean retryable = !(failure instanceof OutboundNotificationTransportException transportFailure) || transportFailure.retryable();
            OutboundNotificationStatus status = repository.markFailed(
                    delivery.deliveryId(), workerId, clock.instant(), retryPolicy, failure, retryable,
                    suspendAfterDeadLetters, suspensionDuration);
            observer.failed(delivery.endpointKey(), status == OutboundNotificationStatus.DEAD_LETTER);
        }
    }

    private static Duration positive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }
    private static String require(String value, String field, int maximum) {
        Objects.requireNonNull(value, field); String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximum) throw new IllegalArgumentException("invalid " + field);
        return normalized;
    }
}
