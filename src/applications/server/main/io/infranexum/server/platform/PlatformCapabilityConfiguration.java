package io.infranexum.server.platform;

import io.infranexum.core.capabilities.CapabilityCatalog;
import io.infranexum.core.capabilities.CapabilityRegistry;
import io.infranexum.core.capabilities.QuotaCatalog;
import io.infranexum.server.http.ApiProblemSupport;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
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
            @Value("${infranexum.entitlements.enabled:true}") boolean entitlementsEnabled) {
        return new PlatformCapabilityService(
                new CapabilityRegistry(capabilityCatalog, Clock.systemUTC()),
                properties, quotaCatalog, entitlementsEnabled);
    }

    @Bean
    ApiCapabilityFilter apiCapabilityFilter(PlatformCapabilityService capabilities, ApiProblemSupport problems) {
        return new ApiCapabilityFilter(capabilities, problems);
    }
}
