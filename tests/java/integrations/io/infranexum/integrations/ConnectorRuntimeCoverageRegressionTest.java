package io.infranexum.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.RetryPolicy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Exercises fail-closed connector runtime branches not covered by nominal workflow tests. */
class ConnectorRuntimeCoverageRegressionTest {
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ConnectorKey KEY = new ConnectorKey("jira-main");
    private static final String HASH = "a".repeat(64);

    @Test
    void immutableContractsRejectEveryInvalidLeaseReplayAndCounterCombination() {
        assertEquals("jira-main", KEY.toString());
        assertThrows(NullPointerException.class, () -> new ConnectorKey(null));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorKey("ab"));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorKey("ABC\nDEF"));
        assertEquals("jira-main", new ConnectorKey(" JIRA-MAIN ").value());

        assertThrows(IllegalArgumentException.class, () -> delivery("bad id!", "{}", HASH, ConnectorDeliveryStatus.PENDING, 0, null, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> delivery("evt-1", " ", HASH, ConnectorDeliveryStatus.PENDING, 0, null, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> delivery("evt-1", "{}", "x", ConnectorDeliveryStatus.PENDING, 0, null, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> delivery("evt-1", "{}", HASH, ConnectorDeliveryStatus.PENDING, -1, null, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> delivery("evt-1", "{}", HASH, ConnectorDeliveryStatus.PENDING, 0, "worker", NOW, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> delivery("evt-1", "{}", HASH, ConnectorDeliveryStatus.IN_FLIGHT, 1, "worker", null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> delivery("evt-1", "{}", HASH, ConnectorDeliveryStatus.PROCESSED, 1, null, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> delivery("evt-1", "{}", HASH, ConnectorDeliveryStatus.PENDING, 0, null, null, NOW, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> delivery("evt-1", "{}", HASH, ConnectorDeliveryStatus.PENDING, 0, null, null, null, "x".repeat(1025), 0, null));
        assertThrows(IllegalArgumentException.class, () -> delivery("evt-1", "{}", HASH, ConnectorDeliveryStatus.PENDING, 0, null, null, null, null, 0, NOW));
        ConnectorDelivery replayed = delivery("evt-1", "  {\"a\":1}  ", HASH, ConnectorDeliveryStatus.DEAD_LETTER, 2, null, null, null, "boom", 1, NOW);
        assertEquals("  {\"a\":1}  ", replayed.payload());

        assertThrows(IllegalArgumentException.class, () -> new ConnectorRuntimeState(KEY, -1, null, null, null));
        assertFalse(new ConnectorRuntimeState(KEY, 0, null, null, null).suspendedAt(NOW));
        assertTrue(new ConnectorRuntimeState(KEY, 1, NOW.plusSeconds(1), null, NOW).suspendedAt(NOW));
        assertThrows(NullPointerException.class, () -> new ConnectorRuntimeState(KEY, 1, NOW.plusSeconds(1), null, NOW).suspendedAt(null));

        assertThrows(IllegalArgumentException.class, () -> new ConnectorDispatchReport(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDispatchReport(1, 0, 0, 0));
        assertEquals(1, new ConnectorDispatchReport(1, 1, 0, 0).claimed());
    }

    @Test
    void endpointAdmissionAndHmacValidationCoverBoundsAndAuthenticationFailures() {
        ConnectorWebhookEndpoint endpoint = new ConnectorWebhookEndpoint(KEY, " handler ", " secret-ref ", Duration.ofMinutes(5), true);
        assertEquals("handler", endpoint.handlerName());
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(KEY, " ", "secret", Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(KEY, "handler", "x\n", Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(KEY, "handler", "secret", Duration.ZERO, true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(KEY, "handler", "secret", Duration.ofMinutes(16), true));

        assertThrows(IllegalArgumentException.class, () -> admission("bad id!", "{}", HASH));
        assertThrows(IllegalArgumentException.class, () -> admission("evt-1", " ", HASH));
        assertThrows(IllegalArgumentException.class, () -> admission("evt-1", "{}", "bad"));
        assertEquals("  {\"asset\":1}  ", admission("evt-1", "  {\"asset\":1}  ", HASH).payload());

        byte[] secret = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        String valid = HmacSha256WebhookAuthenticator.signature(secret.clone(), NOW.getEpochSecond(), payload);
        HmacSha256WebhookAuthenticator auth = new HmacSha256WebhookAuthenticator(ref -> secret.clone(), CLOCK);
        auth.verify(endpoint, NOW.getEpochSecond(), valid, payload);
        assertThrows(WebhookAuthenticationException.class, () -> auth.verify(endpoint, NOW.minusSeconds(301).getEpochSecond(), valid, payload));
        assertThrows(WebhookAuthenticationException.class, () -> auth.verify(endpoint, NOW.getEpochSecond(), "bad", payload));
        assertThrows(WebhookAuthenticationException.class, () -> auth.verify(endpoint, NOW.getEpochSecond(), "sha256=" + "z".repeat(64), payload));
        assertThrows(WebhookAuthenticationException.class, () -> new HmacSha256WebhookAuthenticator(ref -> null, CLOCK).verify(endpoint, NOW.getEpochSecond(), valid, payload));
        assertThrows(WebhookAuthenticationException.class, () -> new HmacSha256WebhookAuthenticator(ref -> new byte[31], CLOCK).verify(endpoint, NOW.getEpochSecond(), valid, payload));
        assertThrows(WebhookAuthenticationException.class, () -> auth.verify(endpoint, NOW.getEpochSecond(), "sha256=" + "0".repeat(64), payload));
        assertThrows(IllegalArgumentException.class, () -> HmacSha256WebhookAuthenticator.signature(new byte[31], NOW.getEpochSecond(), payload));
        assertThrows(NullPointerException.class, () -> HmacSha256WebhookAuthenticator.signature(null, NOW.getEpochSecond(), payload));
        assertThrows(NullPointerException.class, () -> HmacSha256WebhookAuthenticator.signature(secret, NOW.getEpochSecond(), null));
    }

    @Test
    void webhookServiceReportsRejectedMissingDisabledMalformedOversizedAndDuplicateAdmissions() {
        byte[] secret = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        RecordingObserver observer = new RecordingObserver();
        InMemoryConnectorInboxRepository inbox = new InMemoryConnectorInboxRepository();
        ConnectorWebhookEndpoint endpoint = new ConnectorWebhookEndpoint(KEY, "handler", "ref", Duration.ofMinutes(5), true);
        Map<ConnectorKey, ConnectorWebhookEndpoint> registry = Map.of(KEY, endpoint);
        ConnectorWebhookService service = service(registry, inbox, observer, secret, 32);
        String signature = HmacSha256WebhookAuthenticator.signature(secret.clone(), NOW.getEpochSecond(), "{}".getBytes(StandardCharsets.UTF_8));

        WebhookAdmissionOutcome first = service.admit(KEY.value(), "evt-1", NOW.getEpochSecond(), signature, "{}".getBytes(StandardCharsets.UTF_8));
        WebhookAdmissionOutcome duplicate = service.admit(KEY.value(), "evt-1", NOW.getEpochSecond(), signature, "{}".getBytes(StandardCharsets.UTF_8));
        assertFalse(first.duplicate());
        assertTrue(duplicate.duplicate());
        assertEquals(2, observer.admitted.size());

        assertThrows(ConnectorEndpointUnavailableException.class, () -> service(Map.of(), inbox, observer, secret, 32)
                .admit(KEY.value(), "evt-2", NOW.getEpochSecond(), signature, "{}".getBytes(StandardCharsets.UTF_8)));
        ConnectorWebhookEndpoint disabled = new ConnectorWebhookEndpoint(KEY, "handler", "ref", Duration.ofMinutes(5), false);
        assertThrows(ConnectorEndpointUnavailableException.class, () -> service(Map.of(KEY, disabled), inbox, observer, secret, 32)
                .admit(KEY.value(), "evt-2", NOW.getEpochSecond(), signature, "{}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> service.admit(KEY.value(), "evt-empty", NOW.getEpochSecond(), signature, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> service.admit(KEY.value(), "evt-shape", NOW.getEpochSecond(), signature, "text".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> service.admit(KEY.value(), "evt-big", NOW.getEpochSecond(), signature, "{".repeat(33).getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> service(registry, inbox, observer, secret, 0));
        assertThrows(IllegalArgumentException.class, () -> service(registry, inbox, observer, secret, 1_048_577));
        assertTrue(observer.rejected.size() >= 3);
    }

    @Test
    void webhookShapeValidationTraversesObjectAndArrayShortCircuitOperandsThroughTheRealService() {
        byte[] secret = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        ConnectorWebhookEndpoint endpoint = new ConnectorWebhookEndpoint(KEY, "handler", "secret-ref", Duration.ofMinutes(5), true);
        InMemoryConnectorInboxRepository inbox = new InMemoryConnectorInboxRepository();
        RecordingObserver observer = new RecordingObserver();
        ConnectorWebhookService service = service(Map.of(KEY, endpoint), inbox, observer, secret, 128);

        byte[] array = "[]".getBytes(StandardCharsets.UTF_8);
        String arraySignature = HmacSha256WebhookAuthenticator.signature(secret.clone(), NOW.getEpochSecond(), array);
        assertFalse(service.admit(KEY.value(), "evt-array", NOW.getEpochSecond(), arraySignature, array).duplicate());

        for (String malformed : List.of("{", "[", "{]", "[}")) {
            byte[] payload = malformed.getBytes(StandardCharsets.UTF_8);
            String signature = HmacSha256WebhookAuthenticator.signature(secret.clone(), NOW.getEpochSecond(), payload);
            assertThrows(IllegalArgumentException.class,
                    () -> service.admit(KEY.value(), "evt-shape-" + malformed.charAt(0) + malformed.length(),
                            NOW.getEpochSecond(), signature, payload));
        }
    }

    @Test
    void dispatcherConstructorAndOutcomeBranchesRemainBounded() {
        InMemoryConnectorInboxRepository inbox = new InMemoryConnectorInboxRepository();
        ConnectorEndpointRegistry endpoints = registry(Map.of());
        ConnectorDeliveryHandler handler = new ConnectorDeliveryHandler() {
            @Override public String name() { return "handler"; }
            @Override public void handle(ConnectorDelivery delivery) {}
        };
        ConnectorHandlerRegistry handlers = name -> handler;
        RecordingObserver observer = new RecordingObserver();
        RetryPolicy retry = new RetryPolicy() {
            @Override public int maximumAttempts() { return 3; }
            @Override public Duration delayAfterFailure(int attempts) { return Duration.ofSeconds(attempts); }
        };
        assertThrows(IllegalArgumentException.class, () -> new ConnectorInboxDispatcher(inbox, endpoints, handlers, observer, retry, CLOCK, " ", 1, Duration.ofSeconds(1), 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorInboxDispatcher(inbox, endpoints, handlers, observer, retry, CLOCK, "worker", 0, Duration.ofSeconds(1), 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorInboxDispatcher(inbox, endpoints, handlers, observer, retry, CLOCK, "worker", 1001, Duration.ofSeconds(1), 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorInboxDispatcher(inbox, endpoints, handlers, observer, retry, CLOCK, "worker", 1, Duration.ZERO, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorInboxDispatcher(inbox, endpoints, handlers, observer, retry, CLOCK, "worker", 1, Duration.ofSeconds(1), 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorInboxDispatcher(inbox, endpoints, handlers, observer, retry, CLOCK, "worker", 1, Duration.ofSeconds(1), 101, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorInboxDispatcher(inbox, endpoints, handlers, observer, retry, CLOCK, "worker", 1, Duration.ofSeconds(1), 1, Duration.ofSeconds(-1)));
        assertEquals(0, new ConnectorInboxDispatcher(inbox, endpoints, handlers, observer, retry, CLOCK, "worker", 1, Duration.ofSeconds(1), 1, Duration.ofSeconds(1)).dispatchOnce().claimed());
    }

    private static ConnectorWebhookService service(Map<ConnectorKey, ConnectorWebhookEndpoint> endpoints,
            ConnectorInboxRepository inbox, RecordingObserver observer, byte[] secret, int max) {
        ConnectorEndpointRegistry registry = registry(endpoints);
        var auth = new HmacSha256WebhookAuthenticator(ref -> secret.clone(), CLOCK);
        var ids = new UuidV7Generator(CLOCK, new SecureRandom(new byte[] {1, 2, 3}));
        return new ConnectorWebhookService(registry, auth, inbox, observer, ids, CLOCK, max);
    }

    private static ConnectorEndpointRegistry registry(Map<ConnectorKey, ConnectorWebhookEndpoint> endpoints) {
        return new ConnectorEndpointRegistry() {
            @Override public Optional<ConnectorWebhookEndpoint> find(ConnectorKey key) { return Optional.ofNullable(endpoints.get(key)); }
            @Override public Collection<ConnectorWebhookEndpoint> endpoints() { return List.copyOf(endpoints.values()); }
        };
    }

    private static WebhookAdmission admission(String externalId, String payload, String hash) {
        return new WebhookAdmission(id(1), KEY, externalId, payload, hash, NOW);
    }

    private static ConnectorDelivery delivery(String externalId, String payload, String hash,
            ConnectorDeliveryStatus status, int attempts, String leaseOwner, Instant leaseUntil,
            Instant processedAt, String failure, int replayCount, Instant replayedAt) {
        return new ConnectorDelivery(id(1), KEY, externalId, payload, hash, status, attempts, NOW, NOW,
                leaseOwner, leaseUntil, processedAt, failure, replayCount, replayedAt);
    }

    private static DomainIdentifier id(long value) {
        return new DomainIdentifier(new UUID(0x0198_0000_0000_7000L + value, 0x8000_0000_0000_0000L + value));
    }

    private static final class RecordingObserver implements ConnectorRuntimeObserver {
        final List<Boolean> admitted = new ArrayList<>();
        final List<String> rejected = new ArrayList<>();
        @Override public void admitted(ConnectorKey connectorKey, boolean duplicate) { admitted.add(duplicate); }
        @Override public void rejected(ConnectorKey connectorKey, String reason) { rejected.add(reason); }
        @Override public void processed(ConnectorKey connectorKey, Duration latency) {}
        @Override public void retried(ConnectorKey connectorKey) {}
        @Override public void deadLettered(ConnectorKey connectorKey) {}
        @Override public void suspended(ConnectorKey connectorKey) {}
    }
}
