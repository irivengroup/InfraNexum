package io.infranexum.core.contracts;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class ContractVersionTest {
    @Test
    void parsesFormatsAndOrdersEveryVersionComponent() {
        ContractVersion version = ContractVersion.parse("1.2.3");
        assertEquals("1.2.3", version.toString());
        assertTrue(version.compareTo(ContractVersion.parse("0.9.9")) > 0);
        assertTrue(version.compareTo(ContractVersion.parse("1.1.9")) > 0);
        assertTrue(version.compareTo(ContractVersion.parse("1.2.2")) > 0);
        assertEquals(0, version.compareTo(ContractVersion.parse("1.2.3")));
        assertTrue(version.compareTo(ContractVersion.parse("2.0.0")) < 0);
    }

    @Test
    void appliesReaderCompatibilityByMajorAndOrder() {
        ContractVersion reader = ContractVersion.parse("1.2.0");
        assertTrue(reader.canRead(ContractVersion.parse("1.1.9")));
        assertFalse(reader.canRead(ContractVersion.parse("1.2.1")));
        assertFalse(reader.canRead(ContractVersion.parse("2.0.0")));
        assertThrows(NullPointerException.class, () -> reader.canRead(null));
        assertThrows(NullPointerException.class, () -> reader.compareTo(null));
    }

    @Test
    void rejectsInvalidVersionsAndNegativeComponents() {
        assertThrows(IllegalArgumentException.class, () -> ContractVersion.parse("01.2.3"));
        assertThrows(IllegalArgumentException.class, () -> ContractVersion.parse("1.2"));
        assertThrows(NullPointerException.class, () -> ContractVersion.parse(null));
        assertThrows(IllegalArgumentException.class, () -> new ContractVersion(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ContractVersion(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ContractVersion(0, 0, -1));
    }
}
