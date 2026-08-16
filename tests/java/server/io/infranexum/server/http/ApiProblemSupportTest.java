package io.infranexum.server.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.server.observability.CorrelationContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiProblemSupportTest {
    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");
    private static final String CORRELATION = "018bcfe5-6800-7001-8000-000000000001";
    private final ApiProblemSupport support = ApiProblemTestFixtures.support(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void buildsCanonicalRfcProblemAndLegacyAliasesFromOneCorrelationContext() {
        var request = request();
        CorrelationContext.bind(request, DomainIdentifier.parse(CORRELATION));

        var response = support.response(
                HttpStatus.CONFLICT,
                "INFRANEXUM_TEST_CONFLICT",
                "Test conflict",
                "resource changed",
                Map.of("field", "version"),
                Map.of("source", "test"),
                request);

        ApiProblem body = response.getBody();
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        assertEquals(CORRELATION, response.getHeaders().getFirst(CorrelationContext.HEADER_NAME));
        assertEquals("urn:infranexum:problem:infranexum-test-conflict", body.type());
        assertEquals(409, body.status());
        assertEquals("resource changed", body.detail());
        assertEquals(body.detail(), body.message());
        assertEquals(NOW.toString(), body.occurred_at());
        assertEquals(body.occurred_at(), body.timestamp());
        assertEquals(CORRELATION, body.correlation_id());
        assertEquals(CORRELATION, body.trace_id());
        assertEquals("version", body.details().get("field"));
    }

    @Test
    void redactsSecretsAndBoundsPublicDetailBeforeSerialization() throws Exception {
        var request = request();
        CorrelationContext.bind(request, DomainIdentifier.parse(CORRELATION));
        String secret = "password=never-return Authorization: Bearer never-return-token " + "x".repeat(700);
        ApiProblem problem = support.problem(
                HttpStatus.BAD_REQUEST,
                "INFRANEXUM_INVALID_REQUEST",
                "Invalid request",
                secret,
                Map.of("credential", secret),
                Map.of(),
                request);
        var response = new MockHttpServletResponse();

        support.write(response, problem);

        String payload = response.getContentAsString();
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/problem+json"));
        assertTrue(problem.detail().length() <= 512);
        assertFalse(payload.contains("never-return-token"));
        assertFalse(payload.contains("password=never-return"));
        assertTrue(payload.contains("[REDACTED]"));
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/test");
    }
}
