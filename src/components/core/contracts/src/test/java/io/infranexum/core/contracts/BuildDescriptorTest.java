
package io.infranexum.core.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BuildDescriptorTest {
    @Test
    void normalizesAndExposesValidIdentity() {
        var descriptor = new BuildDescriptor(" InfraNexum ", "2.0.0-alpha.0.11", "2.0.0-draft.21", ComponentKind.SERVER);
        assertEquals("InfraNexum", descriptor.product());
        assertEquals(ComponentKind.SERVER, descriptor.component());
    }

    @Test
    void rejectsBlankProduct() {
        assertThrows(ConfigurationException.class,
                () -> new BuildDescriptor(" ", "2.0.0-alpha.0.11", "2.0.0-draft.21", ComponentKind.SERVER));
    }

    @Test
    void rejectsBlankVersion() {
        assertThrows(ConfigurationException.class,
                () -> new BuildDescriptor("InfraNexum", null, "2.0.0-draft.21", ComponentKind.SERVER));
    }

    @Test
    void rejectsBlankBaseline() {
        assertThrows(ConfigurationException.class,
                () -> new BuildDescriptor("InfraNexum", "2.0.0-alpha.0.11", "", ComponentKind.SERVER));
    }

    @Test
    void rejectsMissingComponent() {
        assertThrows(NullPointerException.class,
                () -> new BuildDescriptor("InfraNexum", "2.0.0-alpha.0.11", "2.0.0-draft.21", null));
    }
}
