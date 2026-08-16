package io.infranexum.server.integrations;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.infranexum.core.audit.AuditEntry;
import io.infranexum.core.audit.AuditJournal;
import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.integrations.ConnectorDelivery;
import io.infranexum.integrations.ConnectorDeliveryHandler;
import io.infranexum.integrations.ConnectorDeliveryStatus;
import io.infranexum.integrations.ConnectorEndpointRegistry;
import io.infranexum.integrations.ConnectorInboxDispatcher;
import io.infranexum.integrations.ConnectorInboxRepository;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorRuntimeObserver;
import io.infranexum.integrations.ConnectorRuntimeState;
import io.infranexum.integrations.ConnectorWebhookEndpoint;
import io.infranexum.integrations.ConnectorWebhookService;
import io.infranexum.integrations.WebhookAdmissionOutcome;
import io.infranexum.server.http.ApiProblemTestFixtures;
import io.infranexum.server.identity.LocalAuthenticationFilter;
import io.infranexum.server.observability.CorrelationContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/** Branch-focused Server tests for the generic connector webhook/inbox/DLQ runtime. */
class IntegrationRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ConnectorKey KEY = new ConnectorKey("jira-assets.test");
    private static final DomainIdentifier ACTOR = id(900);
    private static final DomainIdentifier CORRELATION = id(901);

    @Test
    void propertiesNormalizeEndpointsAndRejectEveryUnsafeBound() {
        var endpoint = new IntegrationRuntimeProperties.EndpointProperties(
                "jira-assets-handler", "env:PATH", Duration.ofMinutes(5), true);
        var properties = properties(Map.of("JIRA-ASSETS.TEST", endpoint));

        assertEquals(1, properties.endpointDefinitions().size());
        assertEquals("jira-assets.test", properties.endpointDefinitions().keySet().iterator().next().value());
        assertEquals(5, properties.retryPolicy().maximumAttempts());

        assertThrows(ConfigurationException.class, () -> properties(0, 50, Duration.ofSeconds(1), Duration.ofSeconds(30), 5,
                Duration.ofSeconds(1), Duration.ofMinutes(1), 0.2, 3, Duration.ofMinutes(15), Map.of()));
        assertThrows(ConfigurationException.class, () -> properties(1_048_577, 50, Duration.ofSeconds(1), Duration.ofSeconds(30), 5,
                Duration.ofSeconds(1), Duration.ofMinutes(1), 0.2, 3, Duration.ofMinutes(15), Map.of()));
        assertThrows(ConfigurationException.class, () -> properties(1024, 0, Duration.ofSeconds(1), Duration.ofSeconds(30), 5,
                Duration.ofSeconds(1), Duration.ofMinutes(1), 0.2, 3, Duration.ofMinutes(15), Map.of()));
        assertThrows(ConfigurationException.class, () -> properties(1024, 1001, Duration.ofSeconds(1), Duration.ofSeconds(30), 5,
                Duration.ofSeconds(1), Duration.ofMinutes(1), 0.2, 3, Duration.ofMinutes(15), Map.of()));
        assertThrows(ConfigurationException.class, () -> properties(1024, 50, Duration.ZERO, Duration.ofSeconds(30), 5,
                Duration.ofSeconds(1), Duration.ofMinutes(1), 0.2, 3, Duration.ofMinutes(15), Map.of()));
        assertThrows(ConfigurationException.class, () -> properties(1024, 50, Duration.ofSeconds(1), null, 5,
                Duration.ofSeconds(1), Duration.ofMinutes(1), 0.2, 3, Duration.ofMinutes(15), Map.of()));
        assertThrows(ConfigurationException.class, () -> properties(1024, 50, Duration.ofSeconds(1), Duration.ofSeconds(30), 5,
                Duration.ofSeconds(1), Duration.ofMinutes(1), 0.2, 0, Duration.ofMinutes(15), Map.of()));
        assertThrows(ConfigurationException.class, () -> properties(1024, 50, Duration.ofSeconds(1), Duration.ofSeconds(30), 5,
                Duration.ofSeconds(1), Duration.ofMinutes(1), 0.2, 101, Duration.ofMinutes(15), Map.of()));
        assertThrows(ConfigurationException.class, () -> properties(1024, 50, Duration.ofSeconds(1), Duration.ofSeconds(30), 0,
                Duration.ofSeconds(1), Duration.ofMinutes(1), 0.2, 3, Duration.ofMinutes(15), Map.of()));
        assertThrows(ConfigurationException.class, () -> properties(1024, 50, Duration.ofSeconds(1), Duration.ofSeconds(30), 5,
                Duration.ofMinutes(2), Duration.ofMinutes(1), 0.2, 3, Duration.ofMinutes(15), Map.of()));
        assertThrows(ConfigurationException.class, () -> properties(1024, 50, Duration.ofSeconds(1), Duration.ofSeconds(30), 5,
                Duration.ofSeconds(1), Duration.ofMinutes(1), 1.1, 3, Duration.ofMinutes(15), Map.of()));
        assertThrows(ConfigurationException.class, () -> properties(1024, 50, Duration.ofSeconds(1), Duration.ofSeconds(30), 5,
                Duration.ofSeconds(1), Duration.ofMinutes(1), 0.2, 3, Duration.ZERO, Map.of()));

        assertThrows(ConfigurationException.class,
                () -> new IntegrationRuntimeProperties.EndpointProperties(" ", "env:PATH", Duration.ofSeconds(1), true));
        assertThrows(ConfigurationException.class,
                () -> new IntegrationRuntimeProperties.EndpointProperties("handler", "inline:secret", Duration.ofSeconds(1), true));
        assertThrows(ConfigurationException.class,
                () -> new IntegrationRuntimeProperties.EndpointProperties("handler", "env:PATH", null, true));
        assertThrows(NullPointerException.class, () -> properties(Map.of("jira-assets.test", null)));
    }

    @Test
    void endpointAndHandlerRegistriesAreImmutableAndFailClosed() {
        ConnectorWebhookEndpoint endpoint = endpoint(true);
        var registry = new ConfiguredConnectorEndpointRegistry(Map.of(KEY, endpoint));
        assertSame(endpoint, registry.find(KEY).orElseThrow());
        assertTrue(registry.find(new ConnectorKey("other.test")).isEmpty());
        assertEquals(1, registry.endpoints().size());
        assertThrows(NullPointerException.class, () -> registry.find(null));
        assertThrows(NullPointerException.class, () -> new ConfiguredConnectorEndpointRegistry(null));

        ConnectorDeliveryHandler handler = handler("jira-assets-handler");
        var handlers = new ImmutableConnectorHandlerRegistry(List.of(handler));
        assertSame(handler, handlers.require("jira-assets-handler"));
        assertTrue(handlers.contains("jira-assets-handler"));
        assertFalse(handlers.contains("missing"));
        assertThrows(IllegalStateException.class, () -> handlers.require("missing"));
        assertThrows(NullPointerException.class, () -> handlers.require(null));
        assertThrows(ConfigurationException.class, () -> new ImmutableConnectorHandlerRegistry(List.of(handler(" "))));
        assertThrows(ConfigurationException.class, () -> new ImmutableConnectorHandlerRegistry(List.of(handler, handler("jira-assets-handler"))));
        assertThrows(NullPointerException.class, () -> new ImmutableConnectorHandlerRegistry(null));
    }

    @Test
    void externalSecretProviderAllowsOnlyBoundedEnvironmentOrAbsoluteFiles(@TempDir Path directory) throws Exception {
        ExternalConnectorSecretProvider secrets = new ExternalConnectorSecretProvider();
        assertArrayEquals(System.getenv("PATH").getBytes(StandardCharsets.UTF_8), secrets.resolve("env:PATH"));
        assertThrows(ConfigurationException.class, () -> secrets.resolve(null));
        assertThrows(ConfigurationException.class, () -> secrets.resolve("env:lower"));
        assertThrows(ConfigurationException.class, () -> secrets.resolve("env:INFRANEXUM_VARIABLE_THAT_MUST_NOT_EXIST_9A74"));
        assertThrows(ConfigurationException.class, () -> secrets.resolve("inline:secret"));
        assertThrows(ConfigurationException.class, () -> secrets.resolve("file:relative-secret"));

        Path secret = directory.resolve("connector.secret");
        byte[] value = "a".repeat(32).getBytes(StandardCharsets.UTF_8);
        Files.write(secret, value);
        assertArrayEquals(value, secrets.resolve("file:" + secret));
        Files.write(secret, "short".getBytes(StandardCharsets.UTF_8));
        assertThrows(ConfigurationException.class, () -> secrets.resolve("file:" + secret));
        Files.write(secret, new byte[4097]);
        assertThrows(ConfigurationException.class, () -> secrets.resolve("file:" + secret));
        assertThrows(ConfigurationException.class, () -> secrets.resolve("file:" + directory.resolve("missing")));
    }

    @Test
    void operationsBoundPaginationAuditReplayAndResumeWithoutExposingPayload() {
        ConnectorInboxRepository inbox = mock(ConnectorInboxRepository.class);
        AuditJournal audit = mock(AuditJournal.class);
        ConnectorRuntimeObserver observer = mock(ConnectorRuntimeObserver.class);
        UuidV7Generator ids = new UuidV7Generator(CLOCK, new SecureRandom());
        IntegrationOperationsService service = new IntegrationOperationsService(inbox, audit, observer, ids, CLOCK);
        ConnectorDelivery dead1 = delivery(1, ConnectorDeliveryStatus.DEAD_LETTER, 3, "java.sql.SQLException", 0, null);
        ConnectorDelivery dead2 = delivery(2, ConnectorDeliveryStatus.DEAD_LETTER, 3, "java.io.IOException", 0, null);
        ConnectorDelivery dead3 = delivery(3, ConnectorDeliveryStatus.DEAD_LETTER, 3, "java.lang.IllegalStateException", 0, null);
        when(inbox.listDeadLetters(KEY, 10, 3)).thenReturn(List.of(dead1, dead2, dead3));

        var page = service.deadLetters(KEY, 10, 2);
        assertEquals(List.of(dead1, dead2), page.items());
        assertEquals(12, page.nextOffset());
        when(inbox.listDeadLetters(KEY, 0, 3)).thenReturn(List.of(dead1));
        assertNull(service.deadLetters(KEY, 0, 2).nextOffset());
        assertThrows(IllegalArgumentException.class, () -> service.deadLetters(KEY, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> service.deadLetters(KEY, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.deadLetters(KEY, 0, 201));

        ConnectorRuntimeState state = new ConnectorRuntimeState(KEY, 1, NOW.plusSeconds(30), NOW.minusSeconds(5), NOW);
        when(inbox.runtimeState(KEY)).thenReturn(state);
        when(inbox.backlogSize(KEY, NOW)).thenReturn(4L);
        when(inbox.deadLetterCount(KEY)).thenReturn(2L);
        var snapshot = service.runtime(KEY);
        assertEquals(4, snapshot.backlog());
        assertEquals(2, snapshot.deadLetters());
        assertThrows(NullPointerException.class, () -> service.runtime(null));

        ConnectorDelivery replayed = delivery(4, ConnectorDeliveryStatus.PENDING, 0, null, 1, NOW);
        when(inbox.replay(replayed.deliveryId(), NOW)).thenReturn(replayed);
        assertSame(replayed, service.replay(replayed.deliveryId(), ACTOR, CORRELATION, "operator request"));
        verify(observer).replayed(KEY);
        verify(audit).append(any(AuditEntry.class));
        assertThrows(NullPointerException.class, () -> service.replay(null, ACTOR, CORRELATION, null));
        assertThrows(NullPointerException.class, () -> service.replay(replayed.deliveryId(), null, CORRELATION, null));
        assertThrows(NullPointerException.class, () -> service.replay(replayed.deliveryId(), ACTOR, null, null));

        ConnectorRuntimeState resumed = new ConnectorRuntimeState(KEY, 0, null, NOW, NOW);
        when(inbox.resume(KEY, NOW)).thenReturn(resumed);
        var resumedSnapshot = service.resume(KEY, ACTOR, CORRELATION, null);
        assertSame(resumed, resumedSnapshot.state());
        verify(audit, times(2)).append(any(AuditEntry.class));
        assertThrows(NullPointerException.class, () -> service.resume(null, ACTOR, CORRELATION, null));
        assertThrows(IllegalArgumentException.class, () -> new IntegrationOperationsService.RuntimeSnapshot(resumed, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new IntegrationOperationsService.RuntimeSnapshot(resumed, 0, -1));
        assertThrows(NullPointerException.class, () -> new IntegrationOperationsService.RuntimeSnapshot(null, 0, 0));
        assertThrows(NullPointerException.class, () -> new IntegrationOperationsService.DeadLetterPage(null, null));
    }

    @Test
    void wireModelsNeverSerializeTheDeadLetterPayload() {
        ConnectorDelivery dead = delivery(5, ConnectorDeliveryStatus.DEAD_LETTER, 2, "java.io.IOException", 1, NOW);
        var admission = IntegrationApiModels.WebhookAdmissionResponse.from(dead, false);
        var duplicate = IntegrationApiModels.WebhookAdmissionResponse.from(dead, true);
        var dlq = IntegrationApiModels.DeadLetterResponse.from(dead);
        var state = new ConnectorRuntimeState(KEY, 3, NOW.plusSeconds(60), NOW.minusSeconds(1), NOW);
        var runtime = IntegrationApiModels.RuntimeStateResponse.from(
                new IntegrationOperationsService.RuntimeSnapshot(state, 9, 2), NOW);

        assertEquals("ADMITTED", admission.status());
        assertEquals("DUPLICATE", duplicate.status());
        assertEquals("DEAD_LETTER", dlq.status());
        assertEquals("java.io.IOException", dlq.failureClass());
        assertTrue(runtime.suspended());
        assertEquals(9, runtime.backlog());
        assertEquals(2, runtime.deadLetters());
        assertEquals("reason", new IntegrationApiModels.ReasonRequest("reason").reason());
    }

    @Test
    void observerPublishesOnlyConfiguredLowCardinalityMetrics() {
        ConnectorInboxRepository inbox = mock(ConnectorInboxRepository.class);
        when(inbox.backlogSize(KEY, NOW)).thenReturn(7L);
        when(inbox.deadLetterCount(KEY)).thenReturn(2L);
        ConnectorEndpointRegistry endpoints = new ConfiguredConnectorEndpointRegistry(Map.of(KEY, endpoint(true)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            MicrometerConnectorRuntimeObserver observer = new MicrometerConnectorRuntimeObserver(registry, inbox, endpoints, CLOCK);
            observer.admitted(KEY, false);
            observer.admitted(KEY, true);
            observer.rejected(KEY, "Bad-Sensitive-Value");
            observer.rejected(KEY, "Timeout");
            observer.processed(KEY, Duration.ofMillis(12));
            observer.retried(KEY);
            observer.deadLettered(KEY);
            observer.replayed(KEY);
            observer.suspended(KEY);

            assertEquals(7.0, registry.get("infranexum.integrations.backlog").tag("connector", KEY.value()).gauge().value());
            assertEquals(2.0, registry.get("infranexum.integrations.dead_letters").tag("connector", KEY.value()).gauge().value());
            assertEquals(1.0, registry.get("infranexum.integrations.webhook.admissions").tag("outcome", "accepted").counter().count());
            assertEquals(1.0, registry.get("infranexum.integrations.webhook.admissions").tag("outcome", "duplicate").counter().count());
            assertEquals(1.0, registry.get("infranexum.integrations.webhook.rejections").tag("reason", "runtime").counter().count());
            assertEquals(1.0, registry.get("infranexum.integrations.webhook.rejections").tag("reason", "Timeout").counter().count());
            assertEquals(1, registry.get("infranexum.integrations.processing.latency").timer().count());
            assertEquals(1.0, registry.get("infranexum.integrations.processing").tag("outcome", "retry").counter().count());
            assertEquals(1.0, registry.get("infranexum.integrations.processing").tag("outcome", "dead_letter").counter().count());
            assertEquals(1.0, registry.get("infranexum.integrations.replays").counter().count());
            assertEquals(1.0, registry.get("infranexum.integrations.suspensions").counter().count());
        } finally {
            registry.close();
        }
        assertThrows(NullPointerException.class, () -> new MicrometerConnectorRuntimeObserver(null, inbox, endpoints, CLOCK));
        assertThrows(NullPointerException.class, () -> new MicrometerConnectorRuntimeObserver(new SimpleMeterRegistry(), null, endpoints, CLOCK));
        assertThrows(NullPointerException.class, () -> new MicrometerConnectorRuntimeObserver(new SimpleMeterRegistry(), inbox, endpoints, null));
    }

    @Test
    void schedulePreventsOverlappingLocalDispatches() throws Exception {
        ConnectorInboxDispatcher dispatcher = mock(ConnectorInboxDispatcher.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(dispatcher).dispatchOnce();
        ConnectorInboxSchedule schedule = new ConnectorInboxSchedule(dispatcher);
        Thread first = Thread.ofPlatform().start(schedule::dispatch);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        schedule.dispatch();
        verify(dispatcher, times(1)).dispatchOnce();
        release.countDown();
        first.join(5_000);
        assertFalse(first.isAlive());
        schedule.dispatch();
        verify(dispatcher, times(2)).dispatchOnce();
        assertThrows(NullPointerException.class, () -> new ConnectorInboxSchedule(null));
    }

    @Test
    void controllerValidatesJsonTimestampPaginationAndOperatorContext() {
        ConnectorWebhookService webhooks = mock(ConnectorWebhookService.class);
        IntegrationOperationsService operations = mock(IntegrationOperationsService.class);
        IntegrationController controller = new IntegrationController(webhooks, operations, new ObjectMapper(), CLOCK, properties());
        ConnectorDelivery admitted = delivery(30, ConnectorDeliveryStatus.PENDING, 0, null, 0, null);
        when(webhooks.admit(eq(KEY.value()), eq("delivery-30"), eq(NOW.getEpochSecond()), eq("sha256=abc"), any(byte[].class)))
                .thenReturn(new WebhookAdmissionOutcome(admitted, false));

        var accepted = controller.admit(KEY.value(), "delivery-30", Long.toString(NOW.getEpochSecond()), "sha256=abc", "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        assertEquals(202, accepted.getStatusCode().value());
        assertEquals("no-store", accepted.getHeaders().getFirst("Cache-Control"));
        assertEquals("ADMITTED", accepted.getBody().status());
        assertThrows(IllegalArgumentException.class,
                () -> controller.admit(KEY.value(), "x", "bad", "sig", "{}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> controller.admit(KEY.value(), "x", "1", "sig", "1".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> controller.admit(KEY.value(), "x", "1", "sig", "{".getBytes(StandardCharsets.UTF_8)));

        ConnectorDelivery dead = delivery(31, ConnectorDeliveryStatus.DEAD_LETTER, 5, "java.io.IOException", 0, null);
        when(operations.deadLetters(KEY, 0, 50)).thenReturn(new IntegrationOperationsService.DeadLetterPage(List.of(dead), 50));
        var page = controller.deadLetters(KEY.value(), 0, 50);
        assertEquals(1, page.getBody().size());
        assertEquals("50", page.getHeaders().getFirst("X-Next-Offset"));
        when(operations.deadLetters(null, 0, 50)).thenReturn(new IntegrationOperationsService.DeadLetterPage(List.of(), null));
        assertTrue(controller.deadLetters(null, 0, 50).getBody().isEmpty());

        MockHttpServletRequest request = operatorRequest();
        when(operations.replay(dead.deliveryId(), ACTOR, CORRELATION, "manual"))
                .thenReturn(delivery(31, ConnectorDeliveryStatus.PENDING, 0, null, 1, NOW));
        ConnectorDelivery replayResponse = delivery(31, ConnectorDeliveryStatus.PENDING, 0, null, 1, NOW);
        when(operations.replay(dead.deliveryId(), ACTOR, CORRELATION, null)).thenReturn(replayResponse);
        assertEquals("PENDING", controller.replay(dead.deliveryId().toString(), new IntegrationApiModels.ReasonRequest("manual"), request).status());
        assertEquals("PENDING", controller.replay(dead.deliveryId().toString(), null, request).status());

        ConnectorRuntimeState state = new ConnectorRuntimeState(KEY, 0, null, NOW, null);
        var snapshot = new IntegrationOperationsService.RuntimeSnapshot(state, 1, 0);
        when(operations.runtime(KEY)).thenReturn(snapshot);
        when(operations.resume(KEY, ACTOR, CORRELATION, "resume")).thenReturn(snapshot);
        when(operations.resume(KEY, ACTOR, CORRELATION, null)).thenReturn(snapshot);
        assertEquals(1, controller.runtime(KEY.value()).backlog());
        assertEquals(1, controller.resume(KEY.value(), new IntegrationApiModels.ReasonRequest("resume"), request).backlog());
        assertEquals(1, controller.resume(KEY.value(), null, request).backlog());

        MockHttpServletRequest missingActor = new MockHttpServletRequest();
        CorrelationContext.bind(missingActor, CORRELATION);
        assertThrows(IllegalStateException.class, () -> controller.replay(dead.deliveryId().toString(), null, missingActor));
        MockHttpServletRequest missingCorrelation = new MockHttpServletRequest();
        missingCorrelation.setAttribute(LocalAuthenticationFilter.ACCOUNT_ATTRIBUTE, ACTOR);
        assertThrows(IllegalStateException.class, () -> controller.replay(dead.deliveryId().toString(), null, missingCorrelation));
    }

    @Test
    void exceptionHandlerUsesStableGenericProblemCodes() {
        IntegrationExceptionHandler handler = new IntegrationExceptionHandler(ApiProblemTestFixtures.support(CLOCK));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/integrations/webhooks/test");
        assertEquals(401, handler.auth(new io.infranexum.integrations.WebhookAuthenticationException("secret"), request).getStatusCode().value());
        assertEquals(404, handler.endpoint(new io.infranexum.integrations.ConnectorEndpointUnavailableException("missing"), request).getStatusCode().value());
        assertEquals(404, handler.missing(new io.infranexum.integrations.ConnectorDeliveryNotFoundException("missing"), request).getStatusCode().value());
        assertEquals(409, handler.conflict(new io.infranexum.integrations.DuplicateDeliveryConflictException("conflict"), request).getStatusCode().value());
        assertEquals(409, handler.conflict(new io.infranexum.integrations.ConnectorDeliveryStateConflictException("conflict"), request).getStatusCode().value());
        assertEquals(400, handler.invalid(new IllegalArgumentException("invalid"), request).getStatusCode().value());
        assertFalse(handler.auth(new io.infranexum.integrations.WebhookAuthenticationException("TOP_SECRET"), request).getBody().toString().contains("TOP_SECRET"));
    }

    private static IntegrationRuntimeProperties properties(Map<String, IntegrationRuntimeProperties.EndpointProperties> endpoints) {
        return properties(1_048_576, 50, Duration.ofSeconds(1), Duration.ofSeconds(30), 5,
                Duration.ofSeconds(1), Duration.ofMinutes(1), 0.2, 3, Duration.ofMinutes(15), endpoints);
    }

    private static IntegrationRuntimeProperties properties(
            int maxPayload, int batch, Duration poll, Duration lease, int attempts,
            Duration initial, Duration maximum, double jitter, int threshold, Duration suspension,
            Map<String, IntegrationRuntimeProperties.EndpointProperties> endpoints) {
        return new IntegrationRuntimeProperties(true, maxPayload, batch, poll, lease, attempts, initial, maximum,
                jitter, threshold, suspension, endpoints);
    }

    private static ConnectorWebhookEndpoint endpoint(boolean enabled) {
        return new ConnectorWebhookEndpoint(KEY, "jira-assets-handler", "env:PATH", Duration.ofMinutes(5), enabled);
    }

    private static ConnectorDeliveryHandler handler(String name) {
        return new ConnectorDeliveryHandler() {
            @Override public String name() { return name; }
            @Override public void handle(ConnectorDelivery delivery) { }
        };
    }

    private static ConnectorDelivery delivery(
            int sequence, ConnectorDeliveryStatus status, int attempts, String failure, int replayCount, Instant replayedAt) {
        boolean inflight = status == ConnectorDeliveryStatus.IN_FLIGHT;
        Instant processedAt = status == ConnectorDeliveryStatus.PROCESSED ? NOW : null;
        return new ConnectorDelivery(
                id(sequence), KEY, "delivery-" + sequence, "{\"secret\":\"not-returned\"}", "a".repeat(64), status,
                attempts, NOW, NOW, inflight ? "worker" : null, inflight ? NOW.plusSeconds(30) : null,
                processedAt, failure, replayCount, replayedAt);
    }

    private static MockHttpServletRequest operatorRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(LocalAuthenticationFilter.ACCOUNT_ATTRIBUTE, ACTOR);
        CorrelationContext.bind(request, CORRELATION);
        return request;
    }

    private static DomainIdentifier id(int sequence) {
        return DomainIdentifier.parse("018bcfe5-6800-7000-8000-%012d".formatted(sequence));
    }
}
