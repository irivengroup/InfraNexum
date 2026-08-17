package io.infranexum.integrations;

import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.RetryPolicy;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free smoke for PGM-10-E06 signed outbound notification orchestration. */
public final class OutboundNotificationSmoke {
    private static final Instant NOW = Instant.parse("2026-08-17T16:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ConnectorKey KEY = new ConnectorKey("ops-webhook");

    private OutboundNotificationSmoke() {}

    public static void main(String[] args) {
        OutboundNotificationEndpoint endpoint = new OutboundNotificationEndpoint(
                KEY, URI.create("https://notifications.example.invalid/infranexum"), "env:INX_NOTIFICATION_SECRET",
                Duration.ofSeconds(5), true);
        Registry registry = new Registry(endpoint);
        InMemoryOutboundNotificationRepository repository = new InMemoryOutboundNotificationRepository();
        Observer observer = new Observer();
        OutboundNotificationPublisher publisher = new OutboundNotificationPublisher(
                registry, repository, observer, new UuidV7Generator(CLOCK, new SecureRandom(new byte[] {1, 1, 0, 6})), CLOCK, 4096);

        byte[] body = "{\"kind\":\"health\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OutboundNotificationAdmissionOutcome first = publisher.publish(
                "evt-20260817-1001", "infrastructure.health.changed", body, List.of(KEY)).getFirst();
        OutboundNotificationAdmissionOutcome duplicate = publisher.publish(
                "evt-20260817-1001", "infrastructure.health.changed", body, List.of(KEY)).getFirst();
        assert !first.duplicate() && duplicate.duplicate();
        assert first.delivery().deliveryId().equals(duplicate.delivery().deliveryId());
        assert observer.admissions.get() == 2;

        AtomicInteger sends = new AtomicInteger();
        OutboundNotificationTransport transientOnce = (target, delivery) -> {
            if (sends.getAndIncrement() == 0) throw new OutboundNotificationTransportException("NOTIFICATION_REMOTE_TRANSIENT", true);
        };
        OutboundNotificationDispatcher retrying = dispatcher(repository, registry, transientOnce, observer, 3, 2);
        assert retrying.dispatchAvailable() == 1;
        assert repository.backlogSize(KEY, NOW) == 1;
        assert retrying.dispatchAvailable() == 1;
        assert repository.backlogSize(KEY, NOW) == 0;
        assert observer.delivered.get() == 1;

        OutboundNotificationAdmissionOutcome rejected = publisher.publish(
                "evt-20260817-1002", "security.audit.changed", body, List.of(KEY)).getFirst();
        OutboundNotificationTransport permanent = (target, delivery) -> {
            throw new OutboundNotificationTransportException("NOTIFICATION_REMOTE_REJECTED", false);
        };
        OutboundNotificationDispatcher deadLettering = dispatcher(repository, registry, permanent, observer, 5, 1);
        assert deadLettering.dispatchAvailable() == 1;
        assert repository.deadLetterCount(KEY) == 1;
        assert repository.runtimeState(KEY).suspendedAt(NOW.plusSeconds(1));
        OutboundNotificationDelivery replayed = repository.replay(rejected.delivery().deliveryId(), NOW.plusSeconds(2));
        observer.replayed(KEY);
        assert replayed.status() == OutboundNotificationStatus.PENDING && replayed.replayCount() == 1;
        assert repository.claimBatch("blocked", 10, NOW.plusSeconds(3), Duration.ofSeconds(1)).isEmpty();
        repository.resume(KEY, NOW.plusSeconds(3));
        assert repository.claimBatch("resumed", 10, NOW.plusSeconds(3), Duration.ofSeconds(1)).size() == 1;

        expect(DuplicateDeliveryConflictException.class, () -> publisher.publish(
                "evt-20260817-1001", "infrastructure.health.changed", "[]".getBytes(), List.of(KEY)));
        System.out.println("outbound-notification-smoke: PASS idempotence=1 retry=1 delivered=1 dlq=1 replay=1 suspension=bounded");
    }

    private static OutboundNotificationDispatcher dispatcher(InMemoryOutboundNotificationRepository repository,
            Registry registry, OutboundNotificationTransport transport, Observer observer, int attempts, int suspensionThreshold) {
        RetryPolicy retryPolicy = new RetryPolicy() {
            @Override public int maximumAttempts() { return attempts; }
            @Override public Duration delayAfterFailure(int currentAttempts) { return Duration.ZERO; }
        };
        return new OutboundNotificationDispatcher(repository, registry, transport, observer, retryPolicy, CLOCK,
                "notification-smoke-worker", 50, Duration.ofSeconds(30), suspensionThreshold, Duration.ofMinutes(15));
    }

    private static void expect(Class<? extends Throwable> type, ThrowingRunnable runnable) {
        try { runnable.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("unexpected exception", failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private record Registry(OutboundNotificationEndpoint endpoint) implements OutboundNotificationEndpointRegistry {
        @Override public OutboundNotificationEndpoint require(ConnectorKey endpointKey) {
            if (!endpoint.endpointKey().equals(endpointKey)) throw new IllegalArgumentException("unknown endpoint");
            return endpoint;
        }
        @Override public Collection<OutboundNotificationEndpoint> endpoints() { return List.of(endpoint); }
    }

    private static final class Observer implements OutboundNotificationRuntimeObserver {
        private final AtomicInteger admissions = new AtomicInteger();
        private final AtomicInteger delivered = new AtomicInteger();
        @Override public void admitted(ConnectorKey endpointKey, boolean duplicate) { admissions.incrementAndGet(); }
        @Override public void delivered(ConnectorKey endpointKey) { delivered.incrementAndGet(); }
        @Override public void failed(ConnectorKey endpointKey, boolean deadLetter) {}
        @Override public void replayed(ConnectorKey endpointKey) {}
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Throwable; }
}
