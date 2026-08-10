package io.infranexum.server.platform.entitlements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ActivationRuntimePropertiesTest {
    @Test
    void acceptsBoundedExternalizedConfiguration() {
        var properties = new ActivationRuntimeProperties(
                true,
                " customer-1 ",
                Path.of("/etc/infranexum/trust.json"),
                Path.of("/run/secrets/integrity"),
                Path.of("/var/lib/infranexum/integrity"),
                4_194_304,
                Duration.ofMinutes(5));
        assertEquals("customer-1", properties.customerId());
        assertEquals(Duration.ofMinutes(5), properties.refreshInterval());
        assertNull(new ActivationRuntimeProperties(
                true, "customer-1", Path.of(""), Path.of("key"), Path.of("proof"),
                1024, Duration.ofMinutes(1)).trustStorePath());
    }

    @Test
    void rejectsBlankCustomerInvalidSizeAndRefreshBounds() {
        assertThrows(IllegalArgumentException.class, () -> properties(" ", 1024, Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class, () -> properties("customer", 1023, Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class, () -> properties("customer", 4_194_305, Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class, () -> properties("customer", 1024, Duration.ofSeconds(59)));
        assertThrows(IllegalArgumentException.class, () -> properties("customer", 1024, Duration.ofHours(25)));
        assertThrows(NullPointerException.class, () -> new ActivationRuntimeProperties(
                true, "customer", null, null, Path.of("proof"), 1024, Duration.ofMinutes(5)));
    }

    private static ActivationRuntimeProperties properties(String customer, int size, Duration refresh) {
        return new ActivationRuntimeProperties(
                true, customer, null, Path.of("key"), Path.of("proof"), size, refresh);
    }
}
