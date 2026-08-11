package io.infranexum.server.platform;

import io.infranexum.core.capabilities.CapabilityCatalog;
import io.infranexum.core.capabilities.CapabilityRegistry;
import io.infranexum.core.capabilities.QuotaCatalog;
import io.infranexum.server.platform.entitlements.ActivationRuntimeProperties;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root for the single capability and quota catalogue instances used by all runtime authorities. */
@Configuration(proxyBeanMethods = false)
public class PlatformCapabilityConfiguration {
    @Bean
    CapabilityCatalog capabilityCatalog(PlatformCapabilityProperties properties) {
        return CapabilityCatalog.loadEmbedded(properties.catalogVersion());
    }

    @Bean
    QuotaCatalog quotaCatalog(PlatformCapabilityProperties properties) {
        return QuotaCatalog.loadEmbedded(properties.catalogVersion());
    }

    @Bean
    PlatformCapabilityService platformCapabilityService(
            PlatformCapabilityProperties properties,
            CapabilityCatalog capabilityCatalog,
            QuotaCatalog quotaCatalog,
            ActivationRuntimeProperties entitlements) {
        return new PlatformCapabilityService(
                new CapabilityRegistry(capabilityCatalog, Clock.systemUTC()),
                properties, quotaCatalog, entitlements.enabled());
    }
}
