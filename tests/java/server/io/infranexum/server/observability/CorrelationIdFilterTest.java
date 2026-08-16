package io.infranexum.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.server.http.ApiProblemTestFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {
    private static final Instant NOW = Instant.parse("2026-08-11T20:30:00Z");
    private static final String VALID = "018bcfe5-6800-7001-8000-000000000001";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesUuidV7AndBindsItToResponseRequestAndMdc() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CorrelationIdFilter filter = filter(registry);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/build");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (filteredRequest, filteredResponse) -> {
            String value = response.getHeader(CorrelationContext.HEADER_NAME);
            DomainIdentifier parsed = DomainIdentifier.parse(value);
            assertEquals(7, parsed.value().version());
            assertEquals(value, CorrelationContext.traceId(request));
            assertEquals(value, MDC.get(CorrelationContext.MDC_KEY));
        });

        assertNull(MDC.get(CorrelationContext.MDC_KEY));
        assertEquals(1.0d, registry.get("infranexum.http.correlation.generated").counter().count());
        assertEquals(0.0d, registry.get("infranexum.http.correlation.rejected").counter().count());
    }

    @Test
    void preservesCanonicalCallerUuidV7AndRestoresExistingMdc() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CorrelationIdFilter filter = filter(registry);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health/readiness");
        request.addHeader(CorrelationContext.HEADER_NAME, "  " + VALID + "  ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put(CorrelationContext.MDC_KEY, "outer-context");

        filter.doFilter(request, response, (filteredRequest, filteredResponse) ->
                assertEquals(VALID, MDC.get(CorrelationContext.MDC_KEY)));

        assertEquals(VALID, response.getHeader(CorrelationContext.HEADER_NAME));
        assertEquals(VALID, CorrelationContext.traceId(request));
        assertEquals("outer-context", MDC.get(CorrelationContext.MDC_KEY));
        assertEquals(0.0d, registry.get("infranexum.http.correlation.generated").counter().count());
    }

    @Test
    void rejectsMalformedCallerValueWithoutReflectingItOrInvokingTheApplication() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CorrelationIdFilter filter = filter(registry);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/build");
        String malicious = "not-a-uuid-secret-token";
        request.addHeader(CorrelationContext.HEADER_NAME, malicious);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (filteredRequest, filteredResponse) -> invoked.set(true));

        assertFalse(invoked.get());
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/problem+json"));
        DomainIdentifier serverId = DomainIdentifier.parse(response.getHeader(CorrelationContext.HEADER_NAME));
        assertEquals(7, serverId.value().version());
        String body = response.getContentAsString();
        assertTrue(body.contains("INFRANEXUM_INVALID_CORRELATION_ID"));
        assertTrue(body.contains(serverId.toString()));
        assertFalse(body.contains(malicious));
        assertEquals(1.0d, registry.get("infranexum.http.correlation.rejected").counter().count());
    }

    @Test
    void rejectsNonV7AndNonCanonicalUuidRepresentations() throws Exception {
        for (String invalid : new String[] {
                "123e4567-e89b-42d3-a456-426614174000",
                VALID.toUpperCase()
        }) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
            request.addHeader(CorrelationContext.HEADER_NAME, invalid);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter(registry).doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
                throw new AssertionError("invalid correlation identifier reached the application");
            });

            assertEquals(400, response.getStatus());
            assertEquals(1.0d, registry.get("infranexum.http.correlation.rejected").counter().count());
        }
    }

    private static CorrelationIdFilter filter(SimpleMeterRegistry registry) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new CorrelationIdFilter(new UuidV7Generator(clock, new SecureRandom()), registry, ApiProblemTestFixtures.support(clock));
    }
}
