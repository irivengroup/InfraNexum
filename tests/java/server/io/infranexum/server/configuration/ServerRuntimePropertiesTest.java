package io.infranexum.server.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.core.contracts.RuntimeMode;
import org.junit.jupiter.api.Test;

class ServerRuntimePropertiesTest {
    @Test
    void normalizesStandaloneConfiguration() {
        var properties = new ServerRuntimeProperties(
                " server-1 ", RuntimeMode.STANDALONE, " eu-west ", " paris-1 ", " 2.0.0-alpha.0.52 ", " 2.0.0-draft.21 ");
        assertEquals("server-1", properties.instanceId());
        assertEquals("eu-west", properties.region());
        assertEquals("paris-1", properties.site());
        assertEquals("2.0.0-alpha.0.52", properties.version());
        assertEquals("2.0.0-draft.21", properties.architectureBaseline());
    }

    @Test
    void rejectsGlobalModeOutsideGlobalRegion() {
        assertThrows(ConfigurationException.class, () -> new ServerRuntimeProperties(
                "server-1", RuntimeMode.GLOBAL, "eu-west", "paris-1", "2.0.0-alpha.0.52", "2.0.0-draft.21"));
    }

    @Test
    void acceptsGlobalModeInGlobalRegion() {
        var properties = new ServerRuntimeProperties(
                "server-1", RuntimeMode.GLOBAL, "GLOBAL", "control", "2.0.0-alpha.0.52", "2.0.0-draft.21");
        assertEquals(RuntimeMode.GLOBAL, properties.mode());
    }

    @Test
    void rejectsBlankAndNullValues() {
        assertThrows(ConfigurationException.class, () -> new ServerRuntimeProperties(
                " ", RuntimeMode.STANDALONE, "local", "local", "2.0.0-alpha.0.52", "2.0.0-draft.21"));
        assertThrows(ConfigurationException.class, () -> new ServerRuntimeProperties(
                null, RuntimeMode.STANDALONE, "local", "local", "2.0.0-alpha.0.52", "2.0.0-draft.21"));
        assertThrows(ConfigurationException.class, () -> new ServerRuntimeProperties(
                "server-1", null, "local", "local", "2.0.0-alpha.0.52", "2.0.0-draft.21"));
    }
}
