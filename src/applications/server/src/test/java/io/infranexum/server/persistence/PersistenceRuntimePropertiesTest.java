package io.infranexum.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PersistenceRuntimePropertiesTest {
    @Test
    void exposesSelectedModeAndIsolation() {
        var properties = new PersistenceRuntimeProperties(
                PersistenceMode.POSTGRESQL, JdbcIsolation.SERIALIZABLE);
        assertEquals(PersistenceMode.POSTGRESQL, properties.mode());
        assertEquals(java.sql.Connection.TRANSACTION_SERIALIZABLE, properties.isolation().jdbcValue());
    }

    @Test
    void rejectsMissingModeOrIsolation() {
        assertThrows(IllegalArgumentException.class,
                () -> new PersistenceRuntimeProperties(null, JdbcIsolation.READ_COMMITTED));
        assertThrows(IllegalArgumentException.class,
                () -> new PersistenceRuntimeProperties(PersistenceMode.POSTGRESQL, null));
    }
}
