package io.infranexum.server.platform.entitlements;

import io.infranexum.core.entitlements.EntitlementRuntimeAuthority;
import io.infranexum.core.entitlements.EntitlementRuntimeStatus;
import io.infranexum.server.platform.PlatformCapabilityProperties;
import io.infranexum.server.platform.PlatformCapabilityService;
import java.util.Objects;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;

/** Periodically re-evaluates expiry, revocations and temporal integrity and fails closed. */
public final class EntitlementRefreshScheduler {
    private final EntitlementRuntimeAuthority authority;
    private final PlatformCapabilityProperties platformProperties;
    private final PlatformCapabilityService capabilityService;
    private final ConfigurableApplicationContext context;

    public EntitlementRefreshScheduler(
            EntitlementRuntimeAuthority authority,
            PlatformCapabilityProperties platformProperties,
            PlatformCapabilityService capabilityService,
            ConfigurableApplicationContext context) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.platformProperties = Objects.requireNonNull(platformProperties, "platformProperties");
        this.capabilityService = Objects.requireNonNull(capabilityService, "capabilityService");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Scheduled(fixedDelayString = "${infranexum.entitlements.refresh-interval:PT5M}")
    public void refresh() {
        try {
            EntitlementRuntimeStatus status = authority.refresh(platformProperties.profile());
            if (!status.serviceStartupPermitted()) {
                context.close();
                return;
            }
            capabilityService.applyEntitlementStatus(status);
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }
}
