package io.infranexum.server.identity;

import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Local authentication runtime policy with safe production defaults. */
@ConfigurationProperties(prefix = "infranexum.identity.local")
public record LocalAuthRuntimeProperties(
        boolean enabled,
        String environment,
        boolean cookieSecure,
        String bootstrapUsername,
        String bootstrapDisplayName,
        String bootstrapPasswordFile,
        int lockThreshold,
        Duration lockDuration,
        Duration idleTimeout,
        Duration absoluteTimeout,
        Duration touchInterval) {
    public LocalAuthRuntimeProperties {
        environment = normalize(environment, "production");
        bootstrapUsername = normalize(bootstrapUsername, "admin");
        bootstrapDisplayName = bootstrapDisplayName == null || bootstrapDisplayName.isBlank()
                ? "Local Administrator" : bootstrapDisplayName.strip();
        bootstrapPasswordFile = bootstrapPasswordFile == null ? "" : bootstrapPasswordFile.strip();
        lockThreshold = lockThreshold == 0 ? 5 : lockThreshold;
        lockDuration = lockDuration == null ? Duration.ofMinutes(15) : lockDuration;
        idleTimeout = idleTimeout == null ? Duration.ofMinutes(30) : idleTimeout;
        absoluteTimeout = absoluteTimeout == null ? Duration.ofHours(12) : absoluteTimeout;
        touchInterval = touchInterval == null ? Duration.ofMinutes(1) : touchInterval;
        if (enabled && !cookieSecure && !"local".equals(environment)) {
            throw new IllegalArgumentException("local-auth cookies may be insecure only in environment=local");
        }
    }

    public boolean localDevelopment() { return "local".equals(environment); }

    private static String normalize(String value, String fallback) {
        String result = value == null || value.isBlank() ? fallback : value.strip();
        return result.toLowerCase(Locale.ROOT);
    }
}
