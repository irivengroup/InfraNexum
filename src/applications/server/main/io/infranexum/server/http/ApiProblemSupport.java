package io.infranexum.server.http;

import io.infranexum.server.observability.CorrelationContext;
import io.infranexum.server.observability.SensitiveDataRedactor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

/**
 * Single runtime boundary for constructing and serializing public HTTP problem responses.
 *
 * <p>All details pass through the platform redactor before leaving the Server. The support object
 * also carries legacy-compatible aliases while guaranteeing RFC 9457 fields, correlation metadata
 * and {@code application/problem+json} on every migrated boundary.
 */
public final class ApiProblemSupport {
    private static final int MAX_TEXT_LENGTH = 512;

    private final Clock clock;
    private final SensitiveDataRedactor redactor;
    private final ObjectMapper mapper;

    public ApiProblemSupport(Clock clock, SensitiveDataRedactor redactor, ObjectMapper mapper) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Builds a canonical problem for a request using the already validated correlation context. */
    public ApiProblem problem(
            HttpStatus status,
            String code,
            String title,
            String detail,
            Map<String, ?> details,
            Map<String, ?> metadata,
            HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        return problem(
                status,
                code,
                title,
                detail,
                details,
                metadata,
                request.getRequestURI(),
                CorrelationContext.traceId(request));
    }

    /** Builds a canonical problem when a request correlation cannot yet be bound to the request. */
    public ApiProblem problem(
            HttpStatus status,
            String code,
            String title,
            String detail,
            Map<String, ?> details,
            Map<String, ?> metadata,
            String instance,
            String correlationId) {
        Objects.requireNonNull(status, "status");
        String safeCode = requiredToken(code, "code");
        String safeTitle = safeText(title, "Request failed");
        String safeDetail = safeText(detail, "request failed");
        String occurredAt = clock.instant().toString();
        String correlation = blankToNull(correlationId);
        return new ApiProblem(
                "urn:infranexum:problem:" + safeCode.toLowerCase(Locale.ROOT).replace('_', '-'),
                safeTitle,
                status.value(),
                safeDetail,
                safeInstance(instance, correlation),
                safeCode,
                safeDetail,
                sanitizeMap(details),
                sanitizeMap(metadata),
                occurredAt,
                occurredAt,
                correlation,
                correlation);
    }

    /** Returns a problem response with the canonical media type and correlation header. */
    public ResponseEntity<ApiProblem> response(
            HttpStatus status,
            String code,
            String title,
            String detail,
            Map<String, ?> details,
            Map<String, ?> metadata,
            HttpServletRequest request) {
        ApiProblem problem = problem(status, code, title, detail, details, metadata, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("Cache-Control", "no-store");
        if (problem.correlation_id() != null) {
            response.header(CorrelationContext.HEADER_NAME, problem.correlation_id());
        }
        return response.body(problem);
    }

    /** Writes a terminal filter response using exactly the same canonical problem model. */
    public void write(
            HttpServletResponse response,
            ApiProblem problem) throws IOException {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(problem, "problem");
        if (response.isCommitted()) {
            throw new IOException("problem response was committed before the HTTP boundary");
        }
        response.resetBuffer();
        response.setStatus(problem.status());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        if (problem.correlation_id() != null) {
            response.setHeader(CorrelationContext.HEADER_NAME, problem.correlation_id());
        }
        byte[] body = mapper.writeValueAsBytes(problem);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    private Map<String, Object> sanitizeMap(Map<String, ?> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, Object> sanitized = new LinkedHashMap<>();
        values.forEach((key, value) -> sanitized.put(requiredToken(key, "detail key"), sanitizeValue(value)));
        return Map.copyOf(sanitized);
    }

    private Object sanitizeValue(Object value) {
        if (value == null) return "";
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof CharSequence chars) return safeText(chars.toString(), "");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> nested.put(String.valueOf(key), sanitizeValue(nestedValue)));
            return Map.copyOf(nested);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> nested = new ArrayList<>();
            iterable.forEach(item -> nested.add(sanitizeValue(item)));
            return List.copyOf(nested);
        }
        return safeText(String.valueOf(value), "");
    }

    private String safeText(String value, String fallback) {
        String source = value == null || value.isBlank() ? fallback : value;
        String redacted = redactor.redact("problem", source);
        String normalized = redacted.replaceAll("[\\r\\n\\t]+", " ").strip();
        return normalized.length() <= MAX_TEXT_LENGTH ? normalized : normalized.substring(0, MAX_TEXT_LENGTH);
    }

    private static String safeInstance(String instance, String correlation) {
        String normalized = blankToNull(instance);
        if (normalized != null) return normalized;
        return correlation == null ? "urn:infranexum:request:unavailable" : "urn:infranexum:request:" + correlation;
    }

    private static String requiredToken(String value, String name) {
        String normalized = blankToNull(value);
        if (normalized == null || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be a non-empty printable value");
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
