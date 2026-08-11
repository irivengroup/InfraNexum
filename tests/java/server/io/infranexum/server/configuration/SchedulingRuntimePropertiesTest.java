package io.infranexum.server.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.infranexum.core.contracts.ConfigurationException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Regression coverage for bounded platform scheduling configuration. */
class SchedulingRuntimePropertiesTest {

    @Test
    void acceptsBoundedSchedulerConfiguration() {
        var properties = new SchedulingRuntimeProperties(2, Duration.ofSeconds(10));

        assertEquals(2, properties.poolSize());
        assertEquals(Duration.ofSeconds(10), properties.shutdownTimeout());
    }

    @Test
    void rejectsInvalidPoolSizes() {
        assertThrows(ConfigurationException.class,
                () -> new SchedulingRuntimeProperties(0, Duration.ofSeconds(10)));
        assertThrows(ConfigurationException.class,
                () -> new SchedulingRuntimeProperties(33, Duration.ofSeconds(10)));
    }

    @Test
    void rejectsInvalidShutdownTimeouts() {
        assertThrows(ConfigurationException.class,
                () -> new SchedulingRuntimeProperties(2, Duration.ZERO));
        assertThrows(ConfigurationException.class,
                () -> new SchedulingRuntimeProperties(2, Duration.ofMinutes(6)));
        assertThrows(ConfigurationException.class,
                () -> new SchedulingRuntimeProperties(2, null));
    }
}
