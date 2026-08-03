package io.infranexum.core.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CoreContractsTest {
    @Test
    void exposesStableContractVocabularies() {
        assertEquals(ComponentKind.WEB, ComponentKind.valueOf("WEB"));
        assertEquals(HealthState.DEGRADED, HealthState.valueOf("DEGRADED"));
        assertEquals(RuntimeMode.REGIONAL, RuntimeMode.valueOf("REGIONAL"));
    }

    @Test
    void preservesConfigurationErrorMessage() {
        assertEquals("invalid", new ConfigurationException("invalid").getMessage());
    }
}
