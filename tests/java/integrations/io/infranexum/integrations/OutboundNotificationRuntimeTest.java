package io.infranexum.integrations;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.RetryPolicy;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** End-to-end domain tests for idempotent outbound notifications, retries, DLQ and suspension. */
final class OutboundNotificationRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-08-17T16:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ConnectorKey KEY = new ConnectorKey("ops-webhook");
    private static final OutboundNotificationEndpoint ENDPOINT = new OutboundNotificationEndpoint(
            KEY, URI.create("https://events.example.invalid/infranexum"), "env:INX_NOTIFICATION_SECRET", Duration.ofSeconds(5), true);

    @Test
    void publisherIsDurableIdempotentAndRejectsSemanticConflicts() {
        var repository = new InMemoryOutboundNotificationRepository();
        var observer = new Observer();
        var publisher = publisher(repository, registry(ENDPOINT), observer, 1024);
        byte[] payload = "{\"state\":\"DOWN\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        var first = publisher.publish("evt-20260817-0001", "infrastructure.health.changed", payload, List.of(KEY));
        var duplicate = publisher.publish("evt-20260817-0001", "infrastructure.health.changed", payload, List.of(KEY));

        assertFalse(first.getFirst().duplicate());
        assertTrue(duplicate.getFirst().duplicate());
        assertEquals(first.getFirst().delivery().deliveryId(), duplicate.getFirst().delivery().deliveryId());
        assertEquals(List.of(false, true), observer.admissions);
        assertThrows(DuplicateDeliveryConflictException.class, () ->
                publisher.publish("evt-20260817-0001", "infrastructure.health.changed", "[]".getBytes(), List.of(KEY)));
        assertEquals(1, repository.backlogSize(KEY, NOW));
    }

    @Test
    void dispatcherRetriesThenDeliversAndResetsEndpointFailureState() {
        var repository = new InMemoryOutboundNotificationRepository();
        var observer = new Observer();
        var publisher = publisher(repository, registry(ENDPOINT), observer, 1024);
        publisher.publish("evt-20260817-0002", "infrastructure.health.changed", "{}".getBytes(), List.of(KEY));
        var calls = new int[1];
        OutboundNotificationTransport transport = (endpoint, delivery) -> {
            if (calls[0]++ == 0) throw new OutboundNotificationTransportException("NOTIFICATION_REMOTE_TRANSIENT", true);
        };
        var dispatcher = dispatcher(repository, transport, observer, retryPolicy(3, Duration.ZERO));

        assertEquals(1, dispatcher.dispatchAvailable());
        DomainIdentifier deliveryId = repository.listDeadLetters(KEY, 0, 10).stream().findFirst().map(OutboundNotificationDelivery::deliveryId).orElse(null);
        assertNull(deliveryId);
        OutboundNotificationDelivery pending = repository.claimBatch("inspect", 1, NOW, Duration.ofNanos(1)).getFirst();
        assertEquals(OutboundNotificationStatus.IN_FLIGHT, pending.status());
        assertEquals(1, dispatcherAt(repository, transport, observer, retryPolicy(3, Duration.ZERO), NOW.plusNanos(2)).dispatchAvailable());
        assertEquals(0, repository.backlogSize(KEY, NOW.plusNanos(2)));
        assertEquals(0, repository.runtimeState(KEY).consecutiveDeadLetters());
        assertEquals(1, observer.delivered);
        assertEquals(List.of(false), observer.failures);
    }

    @Test
    void permanentFailuresDeadLetterSuspendReplayAndRequireExplicitResume() {
        var repository = new InMemoryOutboundNotificationRepository();
        var observer = new Observer();
        var publisher = publisher(repository, registry(ENDPOINT), observer, 1024);
        publisher.publish("evt-20260817-0003", "infrastructure.health.changed", "{}".getBytes(), List.of(KEY));
        OutboundNotificationTransport rejected = (endpoint, delivery) -> {
            throw new OutboundNotificationTransportException("NOTIFICATION_REMOTE_REJECTED", false);
        };
        var dispatcher = new OutboundNotificationDispatcher(repository, registry(ENDPOINT), rejected, observer,
                retryPolicy(5, Duration.ofSeconds(1)), CLOCK, "worker-a", 10, Duration.ofSeconds(30), 1, Duration.ofMinutes(5));

        assertEquals(1, dispatcher.dispatchAvailable());
        assertEquals(1, repository.deadLetterCount(KEY));
        OutboundNotificationRuntimeState suspended = repository.runtimeState(KEY);
        assertTrue(suspended.suspendedAt(NOW.plusSeconds(1)));
        OutboundNotificationDelivery dead = repository.listDeadLetters(KEY, 0, 10).getFirst();
        OutboundNotificationDelivery replayed = repository.replay(dead.deliveryId(), NOW.plusSeconds(2));
        observer.replayed(KEY);
        assertEquals(1, replayed.replayCount());
        assertEquals(OutboundNotificationStatus.PENDING, replayed.status());
        assertEquals(0, repository.claimBatch("worker-b", 10, NOW.plusSeconds(3), Duration.ofSeconds(1)).size());
        assertFalse(repository.resume(KEY, NOW.plusSeconds(3)).suspendedAt(NOW.plusSeconds(3)));
        assertEquals(1, repository.claimBatch("worker-b", 10, NOW.plusSeconds(3), Duration.ofSeconds(1)).size());
        assertEquals(1, observer.replayed);
        assertEquals(List.of(true), observer.failures);
    }

    @Test
    void boundariesFailClosedAndPayloadsAreDefensivelyCopied() {
        assertThrows(IllegalArgumentException.class, () -> new OutboundNotificationEndpoint(KEY, URI.create("http://example.test"), "env:X", Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class, () -> new OutboundNotificationEndpoint(KEY, URI.create("https://u:p@example.test"), "env:X", Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class, () -> new OutboundNotificationEndpoint(KEY, URI.create("https://example.test/#x"), "env:X", Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class, () -> new OutboundNotificationEndpoint(KEY, URI.create("https://example.test:444/x"), "env:X", Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class, () -> new OutboundNotificationEndpoint(KEY, URI.create("https://example.test"), "literal-secret", Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class, () -> new OutboundNotificationEndpoint(KEY, URI.create("https://example.test"), "env:X", Duration.ZERO, true));
        assertThrows(IllegalArgumentException.class, () -> new OutboundNotificationEndpoint(KEY, URI.create("https://example.test"), "env:X", Duration.ofSeconds(61), true));

        var repo = new InMemoryOutboundNotificationRepository();
        var publisher = publisher(repo, registry(ENDPOINT), new Observer(), 2);
        assertThrows(IllegalArgumentException.class, () -> publisher.publish("short", "a.b", "{}".getBytes(), List.of(KEY)));
        assertThrows(IllegalArgumentException.class, () -> publisher.publish("evt-20260817-0004", "INVALID", "{}".getBytes(), List.of(KEY)));
        assertThrows(IllegalArgumentException.class, () -> publisher.publish("evt-20260817-0004", "a.b", new byte[0], List.of(KEY)));
        assertThrows(IllegalArgumentException.class, () -> publisher.publish("evt-20260817-0004", "a.b", "123".getBytes(), List.of(KEY)));
        assertThrows(IllegalArgumentException.class, () -> publisher.publish("evt-20260817-0004", "a.b", "{}".getBytes(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> publisher.publish("evt-20260817-0004", "a.b", "{}".getBytes(), List.of(KEY, KEY)));
        var disabled = new OutboundNotificationEndpoint(KEY, URI.create("https://example.test"), "env:X", Duration.ofSeconds(1), false);
        assertThrows(IllegalArgumentException.class, () -> publisher.publish("evt-20260817-0004", "a.b", "{}".getBytes(), List.of(disabled.endpointKey())));

        byte[] original = "{}".getBytes();
        DomainIdentifier id = new DomainIdentifier(new UUID(0x0198000000007001L, 0x8000000000000001L));
        var admission = new OutboundNotificationAdmission(id, KEY, "evt-20260817-0005", "a.b", original, "a".repeat(64), NOW);
        original[0] = 'x';
        assertEquals('{', admission.payload()[0]);
        byte[] returned = admission.payload(); returned[0] = 'x';
        assertEquals('{', admission.payload()[0]);
        assertThrows(IllegalArgumentException.class, () -> new OutboundNotificationRuntimeState(KEY, -1, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new OutboundNotificationTransportException("bad", true));
    }

    private static OutboundNotificationPublisher publisher(InMemoryOutboundNotificationRepository repository,
            OutboundNotificationEndpointRegistry registry, Observer observer, int maximumPayloadBytes) {
        return new OutboundNotificationPublisher(registry, repository, observer,
                new UuidV7Generator(CLOCK, new SecureRandom(new byte[] {1, 1, 0, 9})), CLOCK, maximumPayloadBytes);
    }

    private static OutboundNotificationDispatcher dispatcher(InMemoryOutboundNotificationRepository repository,
            OutboundNotificationTransport transport, Observer observer, RetryPolicy retryPolicy) {
        return dispatcherAt(repository, transport, observer, retryPolicy, NOW);
    }

    private static OutboundNotificationDispatcher dispatcherAt(InMemoryOutboundNotificationRepository repository,
            OutboundNotificationTransport transport, Observer observer, RetryPolicy retryPolicy, Instant instant) {
        return new OutboundNotificationDispatcher(repository, registry(ENDPOINT), transport, observer, retryPolicy,
                Clock.fixed(instant, ZoneOffset.UTC), "worker-a", 10, Duration.ofSeconds(1), 2, Duration.ofMinutes(5));
    }

    private static RetryPolicy retryPolicy(int attempts, Duration delay) {
        return new RetryPolicy() {
            @Override public int maximumAttempts() { return attempts; }
            @Override public Duration delayAfterFailure(int currentAttempts) { return delay; }
        };
    }

    private static OutboundNotificationEndpointRegistry registry(OutboundNotificationEndpoint endpoint) {
        Map<ConnectorKey, OutboundNotificationEndpoint> values = Map.of(endpoint.endpointKey(), endpoint);
        return new OutboundNotificationEndpointRegistry() {
            @Override public OutboundNotificationEndpoint require(ConnectorKey endpointKey) {
                OutboundNotificationEndpoint value = values.get(endpointKey);
                if (value == null) throw new IllegalArgumentException("unknown endpoint");
                return value;
            }
            @Override public Collection<OutboundNotificationEndpoint> endpoints() { return values.values(); }
        };
    }

    private static final class Observer implements OutboundNotificationRuntimeObserver {
        private final List<Boolean> admissions = new ArrayList<>();
        private final List<Boolean> failures = new ArrayList<>();
        private int delivered;
        private int replayed;
        @Override public void admitted(ConnectorKey endpointKey, boolean duplicate) { admissions.add(duplicate); }
        @Override public void delivered(ConnectorKey endpointKey) { delivered++; }
        @Override public void failed(ConnectorKey endpointKey, boolean deadLetter) { failures.add(deadLetter); }
        @Override public void replayed(ConnectorKey endpointKey) { replayed++; }
    }
}
