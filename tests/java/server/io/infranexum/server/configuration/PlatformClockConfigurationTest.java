package io.infranexum.server.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Regression coverage for framework Clock resolution when multiple bounded contexts are active. */
class PlatformClockConfigurationTest {
    private static final Instant ENTITLEMENT_NOW = Instant.parse("2026-08-11T19:06:33Z");
    private static final Instant WORKER_NOW = Instant.parse("2026-08-11T21:06:33Z");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(momentsAutoConfiguration()))
            .withUserConfiguration(PlatformClockConfiguration.class, ContextClockFixtures.class);

    @Test
    void momentsAutoConfigurationUsesPrimaryPlatformClockWithBoundedContextClocksPresent() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean("moments"));

            Clock byType = context.getBean(Clock.class);
            assertSame(context.getBean("platformClock", Clock.class), byType);
            assertEquals(ZoneOffset.UTC, byType.getZone());
            assertEquals(ENTITLEMENT_NOW, context.getBean("entitlementClock", Clock.class).instant());
            assertEquals(WORKER_NOW, context.getBean("workerClock", Clock.class).instant());
        });
    }

    private static Class<?> momentsAutoConfiguration() {
        // Spring Modulith keeps this auto-configuration package-private; reflective loading lets
        // the regression exercise the real class without coupling production code to it.
        try {
            return Class.forName("org.springframework.modulith.moments.autoconfigure.MomentsAutoConfiguration");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Spring Modulith Moments auto-configuration is missing", exception);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ContextClockFixtures {
        @Bean("entitlementClock")
        Clock entitlementClock() {
            return Clock.fixed(ENTITLEMENT_NOW, ZoneOffset.UTC);
        }

        @Bean("workerClock")
        Clock workerClock() {
            return Clock.fixed(WORKER_NOW, ZoneOffset.UTC);
        }
    }
}
