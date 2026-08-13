package io.infranexum.server.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LocalAuthRuntimePropertiesTest {
    @Test
    void appliesSecureProductionDefaults() {
        var properties = new LocalAuthRuntimeProperties(false, null, true, null, null, null, 0, null, null, null, null);
        assertEquals("production", properties.environment());
        assertEquals("admin", properties.bootstrapUsername());
        assertEquals("Local Administrator", properties.bootstrapDisplayName());
        assertEquals("", properties.bootstrapPasswordFile());
        assertEquals(5, properties.lockThreshold());
        assertEquals(Duration.ofMinutes(15), properties.lockDuration());
        assertEquals(Duration.ofMinutes(30), properties.idleTimeout());
        assertEquals(Duration.ofHours(12), properties.absoluteTimeout());
        assertEquals(Duration.ofMinutes(1), properties.touchInterval());
        assertFalse(properties.localDevelopment());
    }

    @Test
    void normalizesExplicitValuesAndRecognizesLocalDevelopment() {
        var properties = new LocalAuthRuntimeProperties(true, " LOCAL ", false, " ADMIN2 ", " Operator ", " /secret ",
                7, Duration.ofMinutes(3), Duration.ofMinutes(9), Duration.ofHours(4), Duration.ofSeconds(20));
        assertEquals("local", properties.environment());
        assertEquals("admin2", properties.bootstrapUsername());
        assertEquals("Operator", properties.bootstrapDisplayName());
        assertEquals("/secret", properties.bootstrapPasswordFile());
        assertEquals(7, properties.lockThreshold());
        assertTrue(properties.localDevelopment());
    }

    @Test
    void refusesInsecureCookiesOutsideLocalEnvironment() {
        assertThrows(IllegalArgumentException.class, () ->
                new LocalAuthRuntimeProperties(true, "production", false, null, null, null, 0, null, null, null, null));
    }
}
