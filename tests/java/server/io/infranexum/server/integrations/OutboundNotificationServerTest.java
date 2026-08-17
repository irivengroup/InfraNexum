package io.infranexum.server.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.infranexum.core.audit.AuditEntry;
import io.infranexum.core.audit.AuditJournal;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.OutboundNotificationDelivery;
import io.infranexum.integrations.OutboundNotificationEndpoint;
import io.infranexum.integrations.OutboundNotificationNotFoundException;
import io.infranexum.integrations.OutboundNotificationPublisher;
import io.infranexum.integrations.OutboundNotificationRepository;
import io.infranexum.integrations.OutboundNotificationRuntimeObserver;
import io.infranexum.integrations.OutboundNotificationStateConflictException;
import io.infranexum.integrations.OutboundNotificationStatus;
import io.infranexum.server.http.ApiProblemTestFixtures;
import io.infranexum.server.identity.LocalAuthenticationFilter;
import io.infranexum.server.observability.CorrelationContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/** Server-boundary regression tests for governed outbound notification operations. */
class OutboundNotificationServerTest {
    private static final Instant NOW = Instant.parse("2026-08-17T16:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ConnectorKey ENDPOINT_KEY = new ConnectorKey("ops.webhook");
    private static final DomainIdentifier ACTOR = id(801);
    private static final DomainIdentifier CORRELATION = id(802);

    @Test
    void configuredEndpointRegistryFailsClosedForUnknownEndpoints() {
        OutboundNotificationEndpoint endpoint = endpoint();
        var registry = new ConfiguredOutboundNotificationEndpointRegistry(Map.of(ENDPOINT_KEY, endpoint));

        assertEquals(endpoint, registry.require(ENDPOINT_KEY));
        assertThrows(OutboundNotificationNotFoundException.class,
                () -> registry.require(new ConnectorKey("missing.webhook")));
    }

    @Test
    void runtimeNeverCreatesOrReadsStateForAnUnknownConfiguredEndpoint() {
        OutboundNotificationRepository repository = mock(OutboundNotificationRepository.class);
        NotificationOperationsService service = service(Map.of(), repository, mock(AuditJournal.class),
                mock(OutboundNotificationRuntimeObserver.class));

        assertThrows(OutboundNotificationNotFoundException.class,
                () -> service.runtime(new ConnectorKey("missing.webhook")));
        verify(repository, never()).runtimeState(new ConnectorKey("missing.webhook"));
    }

    @Test
    void replayNormalizesOperatorReasonBeforeAuditAndRejectsUnsafeBounds() {
        OutboundNotificationRepository repository = mock(OutboundNotificationRepository.class);
        OutboundNotificationRuntimeObserver observer = mock(OutboundNotificationRuntimeObserver.class);
        AuditJournal journal = mock(AuditJournal.class);
        OutboundNotificationDelivery replayed = replayedDelivery();
        when(repository.replay(replayed.deliveryId(), NOW)).thenReturn(replayed);
        NotificationOperationsService service = service(Map.of(ENDPOINT_KEY, endpoint()), repository, journal, observer);
        NotificationController controller = new NotificationController(service, new ObjectMapper(), CLOCK);
        MockHttpServletRequest request = operatorRequest();

        var response = controller.replay(replayed.deliveryId().toString(),
                new NotificationApiModels.ReasonRequest("  operator approved retry  "), request);

        assertEquals(replayed.deliveryId().toString(), response.deliveryId());
        ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(journal).append(audit.capture());
        assertEquals("operator approved retry", audit.getValue().reason());
        assertThrows(IllegalArgumentException.class,
                () -> controller.replay(replayed.deliveryId().toString(),
                        new NotificationApiModels.ReasonRequest("x"), request));
    }

    @Test
    void integrationAdviceMapsNotificationFailuresWithoutLeakingInternalMessages() {
        IntegrationExceptionHandler handler = new IntegrationExceptionHandler(ApiProblemTestFixtures.support(CLOCK));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/integrations/notifications/dlq/id/replay");

        var missing = handler.notificationMissing(new OutboundNotificationNotFoundException("TOP_SECRET"), request);
        var conflict = handler.notificationConflict(new OutboundNotificationStateConflictException("TOP_SECRET"), request);

        assertEquals(404, missing.getStatusCode().value());
        assertEquals(409, conflict.getStatusCode().value());
        org.junit.jupiter.api.Assertions.assertFalse(missing.getBody().toString().contains("TOP_SECRET"));
        org.junit.jupiter.api.Assertions.assertFalse(conflict.getBody().toString().contains("TOP_SECRET"));
    }

    private static NotificationOperationsService service(
            Map<ConnectorKey, OutboundNotificationEndpoint> endpoints,
            OutboundNotificationRepository repository,
            AuditJournal journal,
            OutboundNotificationRuntimeObserver observer) {
        var registry = new ConfiguredOutboundNotificationEndpointRegistry(endpoints);
        var ids = new UuidV7Generator(CLOCK, new SecureRandom());
        var publisher = new OutboundNotificationPublisher(registry, repository, observer, ids, CLOCK, 1_048_576);
        return new NotificationOperationsService(registry, publisher, repository, observer, journal, ids, CLOCK);
    }

    private static OutboundNotificationEndpoint endpoint() {
        return new OutboundNotificationEndpoint(
                ENDPOINT_KEY,
                URI.create("https://hooks.example.test/infranexum"),
                "env:PATH",
                Duration.ofSeconds(3),
                true);
    }

    private static OutboundNotificationDelivery replayedDelivery() {
        return new OutboundNotificationDelivery(
                id(900), ENDPOINT_KEY, "event-0001", "platform.health", "{}".getBytes(StandardCharsets.UTF_8),
                "a".repeat(64), OutboundNotificationStatus.PENDING, 0, NOW.minusSeconds(60), NOW,
                null, null, null, null, 1, NOW);
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
