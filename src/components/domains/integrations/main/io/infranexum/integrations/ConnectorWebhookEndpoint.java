package io.infranexum.integrations;

import java.time.Duration;
import java.util.Objects;

/** Secret-free webhook endpoint definition; secret material is resolved through a dedicated port. */
public record ConnectorWebhookEndpoint(
        ConnectorKey connectorKey,
        String handlerName,
        String secretReference,
        Duration maximumClockSkew,
        boolean enabled) {
    public ConnectorWebhookEndpoint {
        Objects.requireNonNull(connectorKey, "connectorKey");
        handlerName = token(handlerName, "handlerName");
        secretReference = token(secretReference, "secretReference");
        Objects.requireNonNull(maximumClockSkew, "maximumClockSkew");
        if (maximumClockSkew.isNegative() || maximumClockSkew.isZero() || maximumClockSkew.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("maximumClockSkew must be > 0 and <= 15 minutes");
        }
    }

    private static String token(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
