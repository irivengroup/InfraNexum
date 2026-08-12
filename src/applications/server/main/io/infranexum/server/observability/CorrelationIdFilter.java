package io.infranexum.server.observability;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes one canonical UUIDv7 correlation identifier for every HTTP dispatch.
 *
 * <p>Malformed caller-provided identifiers are rejected instead of silently replaced. The raw
 * rejected header is never reflected in the response or placed in the logging context.
 */
public final class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {
    private static final int CANONICAL_UUID_LENGTH = 36;
    private static final String INVALID_CODE = "INFRANEXUM_INVALID_CORRELATION_ID";

    private final UuidV7Generator identifiers;
    private final Clock clock;
    private final Counter generated;
    private final Counter rejected;

    public CorrelationIdFilter(
            UuidV7Generator identifiers, @Qualifier("platformClock") Clock clock, MeterRegistry registry) {
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(registry, "registry");
        this.generated = Counter.builder("infranexum.http.correlation.generated")
                .description("Accepted HTTP requests with no caller correlation identifier")
                .register(registry);
        this.rejected = Counter.builder("infranexum.http.correlation.rejected")
                .description("HTTP requests rejected because X-Correlation-ID was not canonical UUIDv7")
                .register(registry);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        DomainIdentifier correlationId = CorrelationContext.identifier(request).orElse(null);
        if (correlationId == null) {
            String supplied = request.getHeader(CorrelationContext.HEADER_NAME);
            if (supplied == null) {
                correlationId = identifiers.next();
                generated.increment();
            } else {
                correlationId = parseCanonical(supplied);
                if (correlationId == null) {
                    reject(response, identifiers.next());
                    return;
                }
            }
            CorrelationContext.bind(request, correlationId);
        }

        response.setHeader(CorrelationContext.HEADER_NAME, correlationId.toString());
        String previous = MDC.get(CorrelationContext.MDC_KEY);
        MDC.put(CorrelationContext.MDC_KEY, correlationId.toString());
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(CorrelationContext.MDC_KEY);
            } else {
                MDC.put(CorrelationContext.MDC_KEY, previous);
            }
        }
    }

    private DomainIdentifier parseCanonical(String supplied) {
        String normalized = supplied.strip();
        if (normalized.length() != CANONICAL_UUID_LENGTH) {
            return null;
        }
        try {
            DomainIdentifier parsed = DomainIdentifier.parse(normalized);
            return parsed.toString().equals(normalized) ? parsed : null;
        } catch (IllegalArgumentException invalidIdentifier) {
            return null;
        }
    }

    private void reject(HttpServletResponse response, DomainIdentifier serverCorrelationId) throws IOException {
        rejected.increment();
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader(CorrelationContext.HEADER_NAME, serverCorrelationId.toString());
        String occurredAt = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString();
        String problem = "{"
                + "\"type\":\"urn:infranexum:problem:invalid-correlation-id\","
                + "\"title\":\"Invalid correlation identifier\","
                + "\"status\":400,"
                + "\"detail\":\"X-Correlation-ID must be a canonical UUIDv7 identifier\","
                + "\"instance\":\"urn:infranexum:request:" + serverCorrelationId + "\","
                + "\"code\":\"" + INVALID_CODE + "\","
                + "\"occurred_at\":\"" + occurredAt + "\","
                + "\"trace_id\":\"" + serverCorrelationId + "\"}"
                + System.lineSeparator();
        response.getWriter().write(problem);
    }
}
