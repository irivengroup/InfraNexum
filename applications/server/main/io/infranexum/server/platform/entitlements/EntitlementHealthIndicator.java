package io.infranexum.server.platform.entitlements;

import io.infranexum.core.entitlements.EntitlementRuntimeAuthority;
import io.infranexum.core.entitlements.EntitlementRuntimeStatus;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Readiness contribution that reports only the lifecycle state and never activation secrets. */
public final class EntitlementHealthIndicator implements HealthIndicator {
    private final EntitlementRuntimeAuthority authority;

    public EntitlementHealthIndicator(EntitlementRuntimeAuthority authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    @Override
    public Health health() {
        try {
            EntitlementRuntimeStatus status = authority.currentStatus();
            Health.Builder builder = status.serviceStartupPermitted() ? Health.up() : Health.down();
            return builder.withDetail("profile", status.profile().name())
                    .withDetail("phase", status.phase().name())
                    .withDetail("mutationPermitted", status.mutationPermitted())
                    .build();
        } catch (RuntimeException failure) {
            return Health.down().withDetail("reason", "entitlement runtime unavailable").build();
        }
    }
}
