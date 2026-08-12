package io.infranexum.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class InfraNexumServerApplicationTest {
    @Test
    void startsThroughTheExecutableEntryPoint() {
        InfraNexumServerApplication.main(new String[] {
            "--spring.main.web-application-type=none",
            "--spring.main.banner-mode=off",
            "--spring.main.register-shutdown-hook=false",
            "--infranexum.entitlements.enabled=false",
            "--infranexum.workers.enabled=false",
            "--infranexum.persistence.mode=MEMORY"
        });
    }

    @Test
    void defaultRuntimeDoesNotCreateAnOtlpMetricsExporter() {
        try (var context = new SpringApplicationBuilder(InfraNexumServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.main.banner-mode=off",
                        "spring.main.register-shutdown-hook=false",
                        "infranexum.entitlements.enabled=false",
                        "infranexum.workers.enabled=false",
                        "infranexum.persistence.mode=MEMORY")
                .run()) {
            assertTrue(
                    context.getBeansOfType(OtlpMeterRegistry.class).isEmpty(),
                    "OTLP metrics export must be opt-in and absent from the default runtime");
        }
    }
}
