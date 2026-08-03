package io.infranexum.server.platform;

import io.infranexum.core.capabilities.CapabilityCatalog;
import io.infranexum.core.capabilities.CapabilityEnvironment;
import io.infranexum.core.capabilities.CapabilityRegistry;
import io.infranexum.core.capabilities.QuotaAllocationPlan;
import io.infranexum.core.capabilities.QuotaCatalog;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root for capability and quota catalogues. */
@Configuration(proxyBeanMethods = false)
public class PlatformCapabilityConfiguration {
    @Bean
    PlatformCapabilityService platformCapabilityService(PlatformCapabilityProperties properties) {
        CapabilityCatalog capabilityCatalog = CapabilityCatalog.loadEmbedded(properties.catalogVersion());
        QuotaCatalog quotaCatalog = QuotaCatalog.loadEmbedded(properties.catalogVersion());
        CapabilityEnvironment environment = properties.toEnvironment();
        QuotaAllocationPlan quotaPlan = quotaCatalog.allocate(
                properties.profile(),
                properties.allocationTier(),
                properties.catalogVersion(),
                properties.quotaOverrides());
        return new PlatformCapabilityService(
                new CapabilityRegistry(capabilityCatalog, Clock.systemUTC()), environment, quotaPlan);
    }
}
