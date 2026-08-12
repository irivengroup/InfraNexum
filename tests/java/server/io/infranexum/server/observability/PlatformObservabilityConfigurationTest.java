package io.infranexum.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class PlatformObservabilityConfigurationTest {
    @Test
    void createsPlatformOwnedCorrelationGeneratorAndFilter() {
        PlatformObservabilityConfiguration configuration = new PlatformObservabilityConfiguration();
        Clock clock = Clock.systemUTC();
        var identifiers = configuration.correlationIdentifiers(clock);
        var filter = configuration.correlationIdFilter(identifiers, clock, new SimpleMeterRegistry());

        assertNotNull(identifiers.next());
        assertEquals(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10, filter.getOrder());
    }
}
