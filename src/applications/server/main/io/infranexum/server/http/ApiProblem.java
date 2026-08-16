package io.infranexum.server.http;

import java.util.Map;

/**
 * Canonical InfraNexum RFC 9457 problem document.
 *
 * <p>The RFC fields are complemented by stable InfraNexum extensions. {@code message},
 * {@code details} and {@code timestamp} deliberately preserve the legacy local-authentication and
 * organization error contract while all clients migrate to {@code detail}, {@code occurred_at} and
 * the RFC problem media type.
 */
public record ApiProblem(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        String message,
        Map<String, Object> details,
        Map<String, Object> metadata,
        String occurred_at,
        String timestamp,
        String correlation_id,
        String trace_id) {

    public ApiProblem {
        details = details == null ? Map.of() : Map.copyOf(details);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
