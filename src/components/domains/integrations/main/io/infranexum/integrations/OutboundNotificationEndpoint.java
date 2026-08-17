package io.infranexum.integrations;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Immutable, secret-free webhook destination used by the notification dispatcher. */
public record OutboundNotificationEndpoint(
        ConnectorKey endpointKey,
        URI destination,
        String secretReference,
        Duration requestTimeout,
        boolean enabled) {
    public OutboundNotificationEndpoint {
        Objects.requireNonNull(endpointKey, "endpointKey");
        destination = validateDestination(destination);
        secretReference = requireSecretReference(secretReference);
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                || requestTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("notification requestTimeout must be between >0 and 60 seconds");
        }
    }

    private static URI validateDestination(URI value) {
        Objects.requireNonNull(value, "destination");
        if (!"https".equalsIgnoreCase(value.getScheme()) || value.getHost() == null || value.getHost().isBlank()) {
            throw new IllegalArgumentException("notification destination must be an absolute HTTPS URI");
        }
        if (value.getUserInfo() != null || value.getFragment() != null) {
            throw new IllegalArgumentException("notification destination must not contain userinfo or fragment");
        }
        if (value.getPort() != -1 && value.getPort() != 443) {
            throw new IllegalArgumentException("notification destination may only use the default HTTPS port");
        }
        return value.normalize();
    }

    private static String requireSecretReference(String value) {
        Objects.requireNonNull(value, "secretReference");
        String normalized = value.strip();
        if (!(normalized.startsWith("env:") || normalized.startsWith("file:"))) {
            throw new IllegalArgumentException("notification secretReference must use env: or file:");
        }
        return normalized;
    }
}
