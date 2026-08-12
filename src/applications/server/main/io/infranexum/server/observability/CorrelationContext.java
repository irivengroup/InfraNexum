package io.infranexum.server.observability;

import io.infranexum.core.contracts.DomainIdentifier;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Optional;

/** Request-scoped correlation context shared by HTTP boundaries and problem translators. */
public final class CorrelationContext {
    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String MDC_KEY = "correlation_id";
    private static final String REQUEST_ATTRIBUTE = CorrelationContext.class.getName() + ".identifier";

    private CorrelationContext() {}

    /** Returns the canonical UUIDv7 associated with the current HTTP request, when initialized. */
    public static Optional<DomainIdentifier> identifier(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        return value instanceof DomainIdentifier identifier ? Optional.of(identifier) : Optional.empty();
    }

    /** Returns the canonical textual correlation identifier used in public problem responses. */
    public static String traceId(HttpServletRequest request) {
        return identifier(request).map(DomainIdentifier::toString).orElse(null);
    }

    /** Binds a correlation identifier that has already passed the platform UUIDv7 validation. */
    public static void bind(HttpServletRequest request, DomainIdentifier identifier) {
        Objects.requireNonNull(request, "request").setAttribute(
                REQUEST_ATTRIBUTE, Objects.requireNonNull(identifier, "identifier"));
    }
}
