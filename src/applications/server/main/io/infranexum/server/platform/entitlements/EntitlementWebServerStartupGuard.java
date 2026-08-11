package io.infranexum.server.platform.entitlements;

import io.infranexum.core.entitlements.EntitlementRuntimeAuthority;
import io.infranexum.core.entitlements.EntitlementRuntimeStatus;
import io.infranexum.server.platform.PlatformCapabilityProperties;
import io.infranexum.server.platform.PlatformCapabilityService;
import java.util.Objects;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;

/** Executes the hard-stop decision before the servlet container opens its network port. */
public final class EntitlementWebServerStartupGuard
        implements WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> {
    private final EntitlementRuntimeAuthority authority;
    private final PlatformCapabilityProperties platformProperties;
    private final PlatformCapabilityService capabilityService;

    public EntitlementWebServerStartupGuard(
            EntitlementRuntimeAuthority authority,
            PlatformCapabilityProperties platformProperties,
            PlatformCapabilityService capabilityService) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.platformProperties = Objects.requireNonNull(platformProperties, "platformProperties");
        this.capabilityService = Objects.requireNonNull(capabilityService, "capabilityService");
    }

    @Override
    public void customize(ConfigurableServletWebServerFactory factory) {
        Objects.requireNonNull(factory, "factory");
        EntitlementRuntimeStatus status = authority.initializeAndRequireStartup(platformProperties.profile());
        capabilityService.applyEntitlementStatus(status);
    }
}
