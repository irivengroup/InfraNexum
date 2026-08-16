package io.infranexum.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Behavioral and branch coverage for the generic connector webhook/inbox runtime. */
final class ConnectorRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    @Test
    void valueObjectsEnforceCanonicalKeysEndpointBoundsAndDeliveryInvariants() {
        assertEquals("jira.prod", new ConnectorKey(" JIRA.Prod ").value());
        for (String invalid : List.of("ab", "bad key", "_bad", "a".repeat(81))) {
            assertThrows(IllegalArgumentException.class, () -> new ConnectorKey(invalid));
        }
        assertThrows(NullPointerException.class, () -> new ConnectorKey(null));
        ConnectorKey key = new ConnectorKey("jira.prod");
        assertThrows(NullPointerException.class, () -> new ConnectorWebhookEndpoint(null, "h", "env:S", Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(key, " ", "env:S", Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(key, "h", "\n", Duration.ofSeconds(1), true));
        assertThrows(NullPointerException.class, () -> new ConnectorWebhookEndpoint(key, "h", "env:S", null, true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(key, "h", "env:S", Duration.ZERO, true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(key, "h", "env:S", Duration.ofMinutes(16), true));

        DomainIdentifier id = ids().next();
        String hash = "a".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> new WebhookAdmission(id, key, "bad id", "{}", hash, NOW));
        assertThrows(IllegalArgumentException.class, () -> new WebhookAdmission(id, key, "delivery-1", "{}", "bad", NOW));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDispatchReport(1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDispatchReport(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> delivery(id, key, ConnectorDeliveryStatus.IN_FLIGHT, 0, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> delivery(id, key, ConnectorDeliveryStatus.PROCESSED, 0, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> delivery(id, key, ConnectorDeliveryStatus.PENDING, 0, null, null, NOW, 0, null));
        assertThrows(IllegalArgumentException.class, () -> delivery(id, key, ConnectorDeliveryStatus.PENDING, -1, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> delivery(id, key, ConnectorDeliveryStatus.PENDING, 0, null, null, null, 0, NOW));
        ConnectorRuntimeState state = new ConnectorRuntimeState(key, 0, NOW.plusSeconds(1), null, null);
        assertTrue(state.suspendedAt(NOW));
        assertFalse(state.suspendedAt(NOW.plusSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorRuntimeState(key, -1, null, null, null));
    }

    @Test
    void hmacAuthenticationIsTimestampBoundConstantTimeAndSecretIsWiped() {
        ConnectorWebhookEndpoint endpoint = endpoint(true);
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        byte[] sourceSecret = SECRET.clone();
        ConnectorSecretProvider provider = ignored -> sourceSecret;
        HmacSha256WebhookAuthenticator authenticator = new HmacSha256WebhookAuthenticator(provider, CLOCK);
        String signature = HmacSha256WebhookAuthenticator.signature(SECRET, NOW.getEpochSecond(), payload);
        authenticator.verify(endpoint, NOW.getEpochSecond(), signature, payload);
        assertTrue(allZero(sourceSecret), "resolved secret must be wiped after HMAC computation");

        assertThrows(WebhookAuthenticationException.class,
                () -> new HmacSha256WebhookAuthenticator(ignored -> SECRET.clone(), CLOCK)
                        .verify(endpoint, NOW.minusSeconds(301).getEpochSecond(), signature, payload));
        assertThrows(WebhookAuthenticationException.class,
                () -> new HmacSha256WebhookAuthenticator(ignored -> SECRET.clone(), CLOCK)
                        .verify(endpoint, NOW.getEpochSecond(), "broken", payload));
        assertThrows(WebhookAuthenticationException.class,
                () -> new HmacSha256WebhookAuthenticator(ignored -> SECRET.clone(), CLOCK)
                        .verify(endpoint, NOW.getEpochSecond(), "sha256=" + "z".repeat(64), payload));
        assertThrows(WebhookAuthenticationException.class,
                () -> new HmacSha256WebhookAuthenticator(ignored -> new byte[12], CLOCK)
                        .verify(endpoint, NOW.getEpochSecond(), signature, payload));
        assertThrows(WebhookAuthenticationException.class,
                () -> new HmacSha256WebhookAuthenticator(ignored -> null, CLOCK)
                        .verify(endpoint, NOW.getEpochSecond(), signature, payload));
        String wrong = HmacSha256WebhookAuthenticator.signature(SECRET, NOW.getEpochSecond(), "[]".getBytes(StandardCharsets.UTF_8));
        assertThrows(WebhookAuthenticationException.class,
                () -> new HmacSha256WebhookAuthenticator(ignored -> SECRET.clone(), CLOCK)
                        .verify(endpoint, NOW.getEpochSecond(), wrong, payload));
        assertThrows(IllegalArgumentException.class, () -> HmacSha256WebhookAuthenticator.signature(new byte[2], 0, payload));
        assertThrows(NullPointerException.class, () -> HmacSha256WebhookAuthenticator.signature(null, 0, payload));
    }

    @Test
    void webhookAdmissionPreservesPayloadDeduplicatesAndRejectsAtBoundary() {
        InMemoryConnectorInboxRepository inbox = new InMemoryConnectorInboxRepository();
        RecordingObserver observer = new RecordingObserver();
        ConnectorWebhookService service = service(inbox, endpoint(true), observer, 128);
        byte[] payload = "  {\"a\":1}  ".getBytes(StandardCharsets.UTF_8);
        String signature = HmacSha256WebhookAuthenticator.signature(SECRET, NOW.getEpochSecond(), payload);
        WebhookAdmissionOutcome first = service.admit("jira.prod", "delivery-001", NOW.getEpochSecond(), signature, payload);
        WebhookAdmissionOutcome duplicate = service.admit("jira.prod", "delivery-001", NOW.getEpochSecond(), signature, payload);
        assertFalse(first.duplicate());
        assertTrue(duplicate.duplicate());
        assertEquals(new String(payload, StandardCharsets.UTF_8), first.delivery().payload());
        assertEquals(2, observer.admitted.get());

        byte[] changed = "{\"a\":2}".getBytes(StandardCharsets.UTF_8);
        String changedSignature = HmacSha256WebhookAuthenticator.signature(SECRET, NOW.getEpochSecond(), changed);
        assertThrows(DuplicateDeliveryConflictException.class,
                () -> service.admit("jira.prod", "delivery-001", NOW.getEpochSecond(), changedSignature, changed));
        assertThrows(IllegalArgumentException.class,
                () -> service.admit("jira.prod", "bad delivery", NOW.getEpochSecond(), signature, payload));
        assertThrows(IllegalArgumentException.class,
                () -> service.admit("jira.prod", "delivery-002", NOW.getEpochSecond(), signature, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> service.admit("jira.prod", "delivery-003", NOW.getEpochSecond(), signature, "true".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> service.admit("jira.prod", "delivery-004", NOW.getEpochSecond(), signature, new byte[129]));
        assertThrows(ConnectorEndpointUnavailableException.class,
                () -> service(inbox, endpoint(false), observer, 128).admit("jira.prod", "delivery-005", NOW.getEpochSecond(), signature, payload));
        assertThrows(ConnectorEndpointUnavailableException.class,
                () -> service(inbox, endpoint(true), observer, 128).admit("other.prod", "delivery-006", NOW.getEpochSecond(), signature, payload));
        assertTrue(observer.rejected.get() >= 4);
        assertThrows(IllegalArgumentException.class,
                () -> new ConnectorWebhookService(registry(endpoint(true)), authenticator(), inbox, observer, ids(), CLOCK, 0));
    }

    @Test
    void dispatcherProcessesRetriesDeadLettersSuspendsAndRequiresExplicitResume() {
        InMemoryConnectorInboxRepository inbox = new InMemoryConnectorInboxRepository();
        RecordingObserver observer = new RecordingObserver();
        ConnectorWebhookEndpoint endpoint = endpoint(true);
        ConnectorWebhookService service = service(inbox, endpoint, observer, 1024);
        WebhookAdmissionOutcome success = admit(service, "delivery-success", "{\"ok\":true}");
        AtomicInteger successCalls = new AtomicInteger();
        ConnectorInboxDispatcher successDispatcher = dispatcher(inbox, endpoint, delivery -> successCalls.incrementAndGet(), observer, 2, 2);
        ConnectorDispatchReport successReport = successDispatcher.dispatchOnce();
        assertEquals(new ConnectorDispatchReport(1, 1, 0, 0), successReport);
        assertEquals(1, successCalls.get());
        assertEquals(ConnectorDeliveryStatus.PROCESSED, inbox.require(success.delivery().deliveryId()).status());
        assertEquals(1, observer.processed.get());

        WebhookAdmissionOutcome failed = admit(service, "delivery-fail", "{\"ok\":false}");
        ConnectorInboxDispatcher retryDispatcher = dispatcher(inbox, endpoint, delivery -> { throw new IllegalStateException("unavailable"); }, observer, 2, 1);
        ConnectorDispatchReport retry = retryDispatcher.dispatchOnce();
        assertEquals(new ConnectorDispatchReport(1, 0, 1, 0), retry);
        assertEquals(1, observer.retried.get());
        assertEquals(0, retryDispatcher.dispatchOnce().claimed());

        ConnectorDelivery leased = inbox.claimBatch("manual", 1, NOW.plusSeconds(1), Duration.ofSeconds(30)).getFirst();
        ConnectorDeliveryStatus dead = inbox.markFailed(leased.deliveryId(), "manual", NOW.plusSeconds(1), policy(2),
                new IllegalArgumentException("safe detail is not persisted"), 1, Duration.ofMinutes(15));
        assertEquals(ConnectorDeliveryStatus.DEAD_LETTER, dead);
        assertTrue(inbox.runtimeState(endpoint.connectorKey()).suspendedAt(NOW.plusSeconds(1)));
        assertEquals(1, inbox.deadLetterCount(endpoint.connectorKey()));
        ConnectorDelivery replayed = inbox.replay(failed.delivery().deliveryId(), NOW.plusSeconds(2));
        assertEquals(1, replayed.replayCount());
        assertTrue(inbox.runtimeState(endpoint.connectorKey()).suspendedAt(NOW.plusSeconds(2)));
        inbox.resume(endpoint.connectorKey(), NOW.plusSeconds(3));
        assertFalse(inbox.runtimeState(endpoint.connectorKey()).suspendedAt(NOW.plusSeconds(3)));
        assertThrows(ConnectorDeliveryStateConflictException.class, () -> inbox.replay(success.delivery().deliveryId(), NOW));
        assertThrows(ConnectorDeliveryNotFoundException.class, () -> inbox.require(ids().next()));
    }

    @Test
    void dispatcherHandlesMissingEndpointAndConstructorBoundsFailClosed() {
        InMemoryConnectorInboxRepository inbox = new InMemoryConnectorInboxRepository();
        RecordingObserver observer = new RecordingObserver();
        ConnectorWebhookService service = service(inbox, endpoint(true), observer, 1024);
        admit(service, "delivery-missing", "{}");
        ConnectorEndpointRegistry none = new ConnectorEndpointRegistry() {
            @Override public Optional<ConnectorWebhookEndpoint> find(ConnectorKey key) { return Optional.empty(); }
            @Override public Collection<ConnectorWebhookEndpoint> endpoints() { return List.of(); }
        };
        ConnectorInboxDispatcher dispatcher = new ConnectorInboxDispatcher(inbox, none, ignored -> { throw new AssertionError(); }, observer,
                policy(1), CLOCK, "worker", 1, Duration.ofSeconds(1), 1, Duration.ofSeconds(1));
        assertEquals(1, dispatcher.dispatchOnce().deadLettered());
        assertEquals(1, observer.deadLettered.get());
        assertEquals(1, observer.suspended.get());

        assertThrows(IllegalArgumentException.class, () -> new ConnectorInboxDispatcher(inbox, none, ignored -> null, observer,
                policy(1), CLOCK, " ", 1, Duration.ofSeconds(1), 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorInboxDispatcher(inbox, none, ignored -> null, observer,
                policy(1), CLOCK, "w", 0, Duration.ofSeconds(1), 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorInboxDispatcher(inbox, none, ignored -> null, observer,
                policy(1), CLOCK, "w", 1, Duration.ZERO, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorInboxDispatcher(inbox, none, ignored -> null, observer,
                policy(1), CLOCK, "w", 1, Duration.ofSeconds(1), 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorInboxDispatcher(inbox, none, ignored -> null, observer,
                policy(1), CLOCK, "w", 1, Duration.ofSeconds(1), 1, Duration.ZERO));
    }

    @Test
    void observerNoopAndAdmissionOutcomeAreSafeDefaults() {
        ConnectorKey key = new ConnectorKey("noop.test");
        ConnectorRuntimeObserver.NOOP.admitted(key, false);
        ConnectorRuntimeObserver.NOOP.rejected(key, "reason");
        ConnectorRuntimeObserver.NOOP.processed(key, Duration.ZERO);
        ConnectorRuntimeObserver.NOOP.retried(key);
        ConnectorRuntimeObserver.NOOP.deadLettered(key);
        ConnectorRuntimeObserver.NOOP.replayed(key);
        ConnectorRuntimeObserver.NOOP.suspended(key);
        assertThrows(NullPointerException.class, () -> new WebhookAdmissionOutcome(null, false));
        ConnectorRuntimeState state = new ConnectorRuntimeState(key, 0, null, null, null);
        assertNull(state.suspendedUntil());
    }

    @Test
    void remainingValidationBranchesAreCoveredIndependently() {
        ConnectorKey key = new ConnectorKey("jira.prod");
        DomainIdentifier id = ids().next();
        String hash = "a".repeat(64);

        assertThrows(IllegalArgumentException.class, () -> new ConnectorKey("a"));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorKey("abc$"));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorKey("jira.prod\n"));
        assertEquals("jira.prod", key.toString());

        assertThrows(NullPointerException.class, () -> new ConnectorWebhookEndpoint(key, null, "env:S", Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(key, "x".repeat(161), "env:S", Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(key, "bad\nname", "env:S", Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(key, "handler\n", "env:S", Duration.ofSeconds(1), true));
        assertThrows(NullPointerException.class, () -> new ConnectorWebhookEndpoint(key, "handler", null, Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(key, "handler", "x".repeat(161), Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(key, "handler", "bad\nsecret", Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(key, "handler", "env:S", Duration.ofSeconds(-1), true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(key, "handler", "env:S", Duration.ofMinutes(16), true));

        assertThrows(NullPointerException.class, () -> new WebhookAdmission(null, key, "delivery-1", "{}", hash, NOW));
        assertThrows(NullPointerException.class, () -> new WebhookAdmission(id, null, "delivery-1", "{}", hash, NOW));
        assertThrows(NullPointerException.class, () -> new WebhookAdmission(id, key, null, "{}", hash, NOW));
        assertThrows(IllegalArgumentException.class, () -> new WebhookAdmission(id, key, " ", "{}", hash, NOW));
        assertThrows(IllegalArgumentException.class, () -> new WebhookAdmission(id, key, "x".repeat(201), "{}", hash, NOW));
        assertThrows(IllegalArgumentException.class, () -> new WebhookAdmission(id, key, "delivery-1\n", "{}", hash, NOW));
        assertThrows(NullPointerException.class, () -> new WebhookAdmission(id, key, "delivery-1", null, hash, NOW));
        assertThrows(IllegalArgumentException.class, () -> new WebhookAdmission(id, key, "delivery-1", " ", hash, NOW));
        assertThrows(IllegalArgumentException.class, () -> new WebhookAdmission(id, key, "delivery-1", "x".repeat(1_048_577), hash, NOW));
        assertThrows(NullPointerException.class, () -> new WebhookAdmission(id, key, "delivery-1", "{}", null, NOW));
        assertThrows(NullPointerException.class, () -> new WebhookAdmission(id, key, "delivery-1", "{}", hash, null));

        assertThrows(NullPointerException.class, () -> new ConnectorDelivery(null, key, "delivery-1", "{}", hash, ConnectorDeliveryStatus.PENDING, 0, NOW, NOW, null, null, null, null, 0, null));
        assertThrows(NullPointerException.class, () -> new ConnectorDelivery(id, null, "delivery-1", "{}", hash, ConnectorDeliveryStatus.PENDING, 0, NOW, NOW, null, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDelivery(id, key, " ", "{}", hash, ConnectorDeliveryStatus.PENDING, 0, NOW, NOW, null, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDelivery(id, key, "x".repeat(201), "{}", hash, ConnectorDeliveryStatus.PENDING, 0, NOW, NOW, null, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDelivery(id, key, "delivery-1\n", "{}", hash, ConnectorDeliveryStatus.PENDING, 0, NOW, NOW, null, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDelivery(id, key, "bad id", "{}", hash, ConnectorDeliveryStatus.PENDING, 0, NOW, NOW, null, null, null, null, 0, null));
        assertThrows(NullPointerException.class, () -> new ConnectorDelivery(id, key, "delivery-1", null, hash, ConnectorDeliveryStatus.PENDING, 0, NOW, NOW, null, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDelivery(id, key, "delivery-1", " ", hash, ConnectorDeliveryStatus.PENDING, 0, NOW, NOW, null, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDelivery(id, key, "delivery-1", "x".repeat(1_048_577), hash, ConnectorDeliveryStatus.PENDING, 0, NOW, NOW, null, null, null, null, 0, null));
        assertThrows(NullPointerException.class, () -> new ConnectorDelivery(id, key, "delivery-1", "{}", null, ConnectorDeliveryStatus.PENDING, 0, NOW, NOW, null, null, null, null, 0, null));
        assertThrows(NullPointerException.class, () -> new ConnectorDelivery(id, key, "delivery-1", "{}", hash, null, 0, NOW, NOW, null, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDelivery(id, key, "delivery-1", "{}", hash, ConnectorDeliveryStatus.PENDING, 0, NOW, NOW, null, null, null, null, -1, null));
        assertThrows(NullPointerException.class, () -> new ConnectorDelivery(id, key, "delivery-1", "{}", hash, ConnectorDeliveryStatus.PENDING, 0, null, NOW, null, null, null, null, 0, null));
        assertThrows(NullPointerException.class, () -> new ConnectorDelivery(id, key, "delivery-1", "{}", hash, ConnectorDeliveryStatus.PENDING, 0, NOW, null, null, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDelivery(id, key, "delivery-1", "{}", hash, ConnectorDeliveryStatus.IN_FLIGHT, 0, NOW, NOW, "worker", null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDelivery(id, key, "delivery-1", "{}", hash, ConnectorDeliveryStatus.PENDING, 0, NOW, NOW, "worker", NOW, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDelivery(id, key, "delivery-1", "{}", hash, ConnectorDeliveryStatus.PENDING, 0, NOW, NOW, null, null, null, "x".repeat(1_025), 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDelivery(id, key, "delivery-1", "{}", hash, ConnectorDeliveryStatus.DEAD_LETTER, 1, NOW, NOW, null, null, null, "failed", 0, NOW));
        assertEquals(ConnectorDeliveryStatus.IN_FLIGHT, new ConnectorDelivery(id, key, "delivery-1", "{}", hash, ConnectorDeliveryStatus.IN_FLIGHT, 1, NOW, NOW, "worker", NOW.plusSeconds(5), null, null, 0, null).status());
        assertEquals(ConnectorDeliveryStatus.PROCESSED, new ConnectorDelivery(id, key, "delivery-1", "{}", hash, ConnectorDeliveryStatus.PROCESSED, 1, NOW, NOW, null, null, NOW, null, 1, NOW).status());

        assertThrows(IllegalArgumentException.class, () -> new ConnectorDispatchReport(0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDispatchReport(0, 0, 0, -1));
        assertEquals(new ConnectorDispatchReport(3, 1, 1, 1), new ConnectorDispatchReport(3, 1, 1, 1));

        assertThrows(NullPointerException.class, () -> new ConnectorRuntimeState(null, 0, null, null, null));
        ConnectorRuntimeState suspended = new ConnectorRuntimeState(key, 1, NOW.plusSeconds(5), null, NOW);
        assertThrows(NullPointerException.class, () -> suspended.suspendedAt(null));

        ConnectorEndpointRegistry emptyRegistry = new ConnectorEndpointRegistry() {
            @Override public Optional<ConnectorWebhookEndpoint> find(ConnectorKey connectorKey) { return Optional.empty(); }
            @Override public Collection<ConnectorWebhookEndpoint> endpoints() { return List.of(); }
        };
        RetryPolicy retry = policy(1);
        ConnectorRuntimeObserver observer = ConnectorRuntimeObserver.NOOP;
        ConnectorInboxRepository inbox = new InMemoryConnectorInboxRepository();
        assertThrows(NullPointerException.class, () -> new ConnectorInboxDispatcher(null, emptyRegistry, ignored -> null, observer, retry, CLOCK, "w", 1, Duration.ofSeconds(1), 1, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new ConnectorInboxDispatcher(inbox, null, ignored -> null, observer, retry, CLOCK, "w", 1, Duration.ofSeconds(1), 1, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new ConnectorInboxDispatcher(inbox, emptyRegistry, null, observer, retry, CLOCK, "w", 1, Duration.ofSeconds(1), 1, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new ConnectorInboxDispatcher(inbox, emptyRegistry, ignored -> null, null, retry, CLOCK, "w", 1, Duration.ofSeconds(1), 1, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new ConnectorInboxDispatcher(inbox, emptyRegistry, ignored -> null, observer, null, CLOCK, "w", 1, Duration.ofSeconds(1), 1, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new ConnectorInboxDispatcher(inbox, emptyRegistry, ignored -> null, observer, retry, null, "w", 1, Duration.ofSeconds(1), 1, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new ConnectorInboxDispatcher(inbox, emptyRegistry, ignored -> null, observer, retry, CLOCK, null, 1, Duration.ofSeconds(1), 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorInboxDispatcher(inbox, emptyRegistry, ignored -> null, observer, retry, CLOCK, "w", 1001, Duration.ofSeconds(1), 1, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new ConnectorInboxDispatcher(inbox, emptyRegistry, ignored -> null, observer, retry, CLOCK, "w", 1, null, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorInboxDispatcher(inbox, emptyRegistry, ignored -> null, observer, retry, CLOCK, "w", 1, Duration.ofSeconds(1), 101, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new ConnectorInboxDispatcher(inbox, emptyRegistry, ignored -> null, observer, retry, CLOCK, "w", 1, Duration.ofSeconds(1), 1, null));

        assertThrows(NullPointerException.class, () -> HmacSha256WebhookAuthenticator.signature(SECRET, NOW.getEpochSecond(), null));
        assertThrows(WebhookAuthenticationException.class, () -> authenticator().verify(endpoint(true), Long.MAX_VALUE, "sha256=" + "0".repeat(64), new byte[] {1}));
    }

    private static WebhookAdmissionOutcome admit(ConnectorWebhookService service, String externalId, String json) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        return service.admit("jira.prod", externalId, NOW.getEpochSecond(),
                HmacSha256WebhookAuthenticator.signature(SECRET, NOW.getEpochSecond(), payload), payload);
    }

    private static ConnectorWebhookService service(
            ConnectorInboxRepository inbox, ConnectorWebhookEndpoint endpoint, ConnectorRuntimeObserver observer, int maximumBytes) {
        return new ConnectorWebhookService(registry(endpoint), authenticator(), inbox, observer, ids(), CLOCK, maximumBytes);
    }

    private static HmacSha256WebhookAuthenticator authenticator() {
        return new HmacSha256WebhookAuthenticator(ignored -> SECRET.clone(), CLOCK);
    }

    private static ConnectorEndpointRegistry registry(ConnectorWebhookEndpoint endpoint) {
        return new ConnectorEndpointRegistry() {
            @Override public Optional<ConnectorWebhookEndpoint> find(ConnectorKey key) {
                return endpoint.connectorKey().equals(key) ? Optional.of(endpoint) : Optional.empty();
            }
            @Override public Collection<ConnectorWebhookEndpoint> endpoints() { return List.of(endpoint); }
        };
    }

    private static ConnectorWebhookEndpoint endpoint(boolean enabled) {
        return new ConnectorWebhookEndpoint(new ConnectorKey("jira.prod"), "jira-handler", "env:JIRA_SECRET", Duration.ofMinutes(5), enabled);
    }

    private static ConnectorInboxDispatcher dispatcher(
            ConnectorInboxRepository inbox, ConnectorWebhookEndpoint endpoint, ThrowingHandler action,
            ConnectorRuntimeObserver observer, int maxAttempts, int suspendAfter) {
        ConnectorDeliveryHandler handler = new ConnectorDeliveryHandler() {
            @Override public String name() { return endpoint.handlerName(); }
            @Override public void handle(ConnectorDelivery delivery) throws Exception { action.handle(delivery); }
        };
        return new ConnectorInboxDispatcher(inbox, registry(endpoint), ignored -> handler, observer, policy(maxAttempts), CLOCK,
                "worker", 50, Duration.ofSeconds(30), suspendAfter, Duration.ofMinutes(15));
    }

    private static RetryPolicy policy(int maximumAttempts) {
        return new RetryPolicy() {
            @Override public int maximumAttempts() { return maximumAttempts; }
            @Override public Duration delayAfterFailure(int attempts) { return Duration.ofSeconds(attempts); }
        };
    }

    private static UuidV7Generator ids() {
        return new UuidV7Generator(CLOCK, new SecureRandom(new byte[] {9, 1, 0, 5}));
    }

    private static ConnectorDelivery delivery(
            DomainIdentifier id, ConnectorKey key, ConnectorDeliveryStatus status, int attempts,
            String leaseOwner, Instant leaseUntil, Instant processedAt, int replayCount, Instant replayedAt) {
        return new ConnectorDelivery(id, key, "delivery-1", "{}", "a".repeat(64), status, attempts, NOW, NOW,
                leaseOwner, leaseUntil, processedAt, null, replayCount, replayedAt);
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) if (value != 0) return false;
        return true;
    }

    @FunctionalInterface
    private interface ThrowingHandler { void handle(ConnectorDelivery delivery) throws Exception; }

    private static final class RecordingObserver implements ConnectorRuntimeObserver {
        final AtomicInteger admitted = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final AtomicInteger processed = new AtomicInteger();
        final AtomicInteger retried = new AtomicInteger();
        final AtomicInteger deadLettered = new AtomicInteger();
        final AtomicInteger suspended = new AtomicInteger();
        @Override public void admitted(ConnectorKey key, boolean duplicate) { admitted.incrementAndGet(); }
        @Override public void rejected(ConnectorKey key, String reason) { rejected.incrementAndGet(); }
        @Override public void processed(ConnectorKey key, Duration latency) { processed.incrementAndGet(); }
        @Override public void retried(ConnectorKey key) { retried.incrementAndGet(); }
        @Override public void deadLettered(ConnectorKey key) { deadLettered.incrementAndGet(); }
        @Override public void suspended(ConnectorKey key) { suspended.incrementAndGet(); }
    }
}
