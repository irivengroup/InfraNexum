package io.infranexum.integrations;

import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.RetryPolicy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone PGM-10-E05 smoke that does not require Maven or an external database. */
public final class ConnectorRuntimeSmoke {
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private ConnectorRuntimeSmoke() {}

    public static void main(String[] args) {
        ConnectorKey key = new ConnectorKey("Acme.API");
        assert "acme.api".equals(key.value());
        ConnectorWebhookEndpoint endpoint = new ConnectorWebhookEndpoint(key, "certified-handler", "env:TEST_SECRET", Duration.ofMinutes(5), true);
        ConnectorEndpointRegistry endpoints = new SingleEndpointRegistry(endpoint);
        ConnectorSecretProvider secrets = ignored -> SECRET.clone();
        HmacSha256WebhookAuthenticator authenticator = new HmacSha256WebhookAuthenticator(secrets, CLOCK);
        InMemoryConnectorInboxRepository inbox = new InMemoryConnectorInboxRepository();
        RecordingObserver observer = new RecordingObserver();
        UuidV7Generator ids = new UuidV7Generator(CLOCK, new SecureRandom(new byte[] {1, 0, 0, 1}));
        ConnectorWebhookService webhook = new ConnectorWebhookService(endpoints, authenticator, inbox, observer, ids, CLOCK, 1_048_576);

        byte[] payload = "  {\"event\":\"created\"}  ".getBytes(StandardCharsets.UTF_8);
        String signature = HmacSha256WebhookAuthenticator.signature(SECRET, NOW.getEpochSecond(), payload);
        WebhookAdmissionOutcome admitted = webhook.admit(key.value(), "delivery-001", NOW.getEpochSecond(), signature, payload);
        assert !admitted.duplicate();
        assert admitted.delivery().payload().equals(new String(payload, StandardCharsets.UTF_8)) : "payload bytes must be preserved as UTF-8 text";
        WebhookAdmissionOutcome duplicate = webhook.admit(key.value(), "delivery-001", NOW.getEpochSecond(), signature, payload);
        assert duplicate.duplicate();
        assert observer.admissions.get() == 2;

        AtomicInteger handled = new AtomicInteger();
        ConnectorDeliveryHandler handler = new ConnectorDeliveryHandler() {
            @Override public String name() { return "certified-handler"; }
            @Override public void handle(ConnectorDelivery delivery) { handled.incrementAndGet(); }
        };
        ConnectorInboxDispatcher dispatcher = dispatcher(inbox, endpoints, ignored -> handler, observer, 2);
        ConnectorDispatchReport success = dispatcher.dispatchOnce();
        assert success.claimed() == 1 && success.processed() == 1;
        assert handled.get() == 1;
        assert inbox.backlogSize(key, NOW) == 0;

        byte[] failingPayload = "{\"event\":\"fail\"}".getBytes(StandardCharsets.UTF_8);
        String failingSignature = HmacSha256WebhookAuthenticator.signature(SECRET, NOW.getEpochSecond(), failingPayload);
        WebhookAdmissionOutcome failing = webhook.admit(key.value(), "delivery-002", NOW.getEpochSecond(), failingSignature, failingPayload);
        ConnectorDeliveryHandler failureHandler = new ConnectorDeliveryHandler() {
            @Override public String name() { return "certified-handler"; }
            @Override public void handle(ConnectorDelivery delivery) { throw new IllegalStateException("provider unavailable"); }
        };
        ConnectorInboxDispatcher failingDispatcher = dispatcher(inbox, endpoints, ignored -> failureHandler, observer, 1);
        ConnectorDispatchReport dead = failingDispatcher.dispatchOnce();
        assert dead.deadLettered() == 1;
        assert inbox.deadLetterCount(key) == 1;
        assert inbox.runtimeState(key).suspendedAt(NOW);

        ConnectorDelivery replayed = inbox.replay(failing.delivery().deliveryId(), NOW.plusSeconds(1));
        assert replayed.status() == ConnectorDeliveryStatus.PENDING;
        assert replayed.replayCount() == 1;
        assert inbox.runtimeState(key).suspendedAt(NOW) : "replay must not silently resume a suspended connector";
        inbox.resume(key, NOW.plusSeconds(2));
        assert !inbox.runtimeState(key).suspendedAt(NOW.plusSeconds(2));

        expect(WebhookAuthenticationException.class,
                () -> webhook.admit(key.value(), "delivery-003", NOW.minusSeconds(301).getEpochSecond(), signature, payload));
        expect(DuplicateDeliveryConflictException.class,
                () -> webhook.admit(key.value(), "delivery-001", NOW.getEpochSecond(),
                        HmacSha256WebhookAuthenticator.signature(SECRET, NOW.getEpochSecond(), failingPayload), failingPayload));
        expect(ConnectorDeliveryStateConflictException.class, () -> inbox.replay(admitted.delivery().deliveryId(), NOW));
        System.out.println("integrations-smoke: PASS admission=2 processed=1 dlq=1 replay=1 suspension=bounded");
    }

    private static ConnectorInboxDispatcher dispatcher(
            ConnectorInboxRepository inbox, ConnectorEndpointRegistry endpoints, ConnectorHandlerRegistry handlers,
            ConnectorRuntimeObserver observer, int maximumAttempts) {
        RetryPolicy policy = new RetryPolicy() {
            @Override public int maximumAttempts() { return maximumAttempts; }
            @Override public Duration delayAfterFailure(int attempts) { return Duration.ofSeconds(attempts); }
        };
        return new ConnectorInboxDispatcher(inbox, endpoints, handlers, observer, policy, CLOCK,
                "integration-smoke-worker", 50, Duration.ofSeconds(30), 1, Duration.ofMinutes(15));
    }

    private static void expect(Class<? extends Throwable> type, ThrowingRunnable action) {
        try { action.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("unexpected exception " + failure, failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private record SingleEndpointRegistry(ConnectorWebhookEndpoint endpoint) implements ConnectorEndpointRegistry {
        @Override public Optional<ConnectorWebhookEndpoint> find(ConnectorKey connectorKey) {
            return endpoint.connectorKey().equals(connectorKey) ? Optional.of(endpoint) : Optional.empty();
        }
        @Override public Collection<ConnectorWebhookEndpoint> endpoints() { return java.util.List.of(endpoint); }
    }

    private static final class RecordingObserver implements ConnectorRuntimeObserver {
        private final AtomicInteger admissions = new AtomicInteger();
        @Override public void admitted(ConnectorKey key, boolean duplicate) { admissions.incrementAndGet(); }
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Throwable; }
}
