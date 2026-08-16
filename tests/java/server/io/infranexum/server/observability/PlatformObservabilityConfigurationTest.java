package io.infranexum.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.infranexum.server.http.HttpBoundaryConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class PlatformObservabilityConfigurationTest {
    @Test
    void separatesObservabilityInternalsFromHttpBoundaryComposition() {
        PlatformObservabilityConfiguration observability = new PlatformObservabilityConfiguration();
        HttpBoundaryConfiguration http = new HttpBoundaryConfiguration();
        Clock clock = Clock.systemUTC();
        var redactor = observability.sensitiveDataRedactor();
        var identifiers = observability.correlationIdentifiers(clock);
        var problems = http.apiProblemSupport(clock, redactor, new tools.jackson.databind.ObjectMapper());
        var filter = http.correlationIdFilter(identifiers, new SimpleMeterRegistry(), problems);
        var workerBridge = observability.workerCorrelationBridge(Tracer.NOOP);

        assertNotNull(redactor);
        assertNotNull(identifiers.next());
        assertNotNull(workerBridge);
        assertEquals(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10, filter.getOrder());
    }
}
