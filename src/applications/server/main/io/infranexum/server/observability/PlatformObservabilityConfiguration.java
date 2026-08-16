package io.infranexum.server.observability;

import io.infranexum.core.contracts.UuidV7Generator;
import io.micrometer.tracing.Tracer;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** HTTP observability composition shared by every Server endpoint, including Actuator probes. */
@Configuration(proxyBeanMethods = false)
public class PlatformObservabilityConfiguration {
    @Bean
    SensitiveDataRedactor sensitiveDataRedactor() {
        return new SensitiveDataRedactor();
    }


    @Bean("correlationIdentifiers")
    UuidV7Generator correlationIdentifiers(@Qualifier("platformClock") Clock clock) {
        return new UuidV7Generator(clock, new SecureRandom());
    }


    @Bean
    WorkerCorrelationBridge workerCorrelationBridge(Tracer tracer) {
        return new WorkerCorrelationBridge(tracer);
    }
}
