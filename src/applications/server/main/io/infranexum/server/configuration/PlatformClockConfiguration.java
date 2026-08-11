package io.infranexum.server.configuration;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Provides the canonical infrastructure clock for framework integrations.
 *
 * <p>Bounded contexts keep explicitly qualified clocks so their ownership remains visible at every
 * application injection point. Third-party auto-configurations, however, commonly resolve a
 * {@link Clock} by type only. The single primary platform clock gives those integrations a
 * deterministic UTC clock without weakening bounded-context qualification.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformClockConfiguration {
    @Bean("platformClock")
    @Primary
    Clock platformClock() {
        return Clock.systemUTC();
    }
}
