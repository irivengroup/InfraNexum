package io.infranexum.server.http;

import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.server.observability.SensitiveDataRedactor;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** HTTP-boundary composition kept separate from observability internals to preserve module direction. */
@Configuration(proxyBeanMethods = false)
public class HttpBoundaryConfiguration {
    @Bean
    public ApiProblemSupport apiProblemSupport(
            @Qualifier("platformClock") Clock clock, SensitiveDataRedactor redactor, ObjectMapper mapper) {
        return new ApiProblemSupport(clock, redactor, mapper);
    }

    @Bean
    public CorrelationIdFilter correlationIdFilter(
            @Qualifier("correlationIdentifiers") UuidV7Generator identifiers,
            MeterRegistry registry,
            ApiProblemSupport problems) {
        return new CorrelationIdFilter(identifiers, registry, problems);
    }
}
