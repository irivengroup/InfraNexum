package io.infranexum.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import org.junit.jupiter.api.Test;

class UnavailableDataSourceTest {
    @Test
    void failsEveryConnectionAttemptWithoutPretendingToBeAJdbcPool() throws Exception {
        var dataSource = new UnavailableDataSource("memory mode");
        assertEquals("memory mode", assertThrows(SQLException.class, dataSource::getConnection).getMessage());
        assertEquals("memory mode", assertThrows(
                SQLException.class, () -> dataSource.getConnection("user", "secret")).getMessage());
        assertThrows(SQLFeatureNotSupportedException.class, dataSource::getParentLogger);
        assertSame(dataSource, dataSource.unwrap(UnavailableDataSource.class));
        assertTrue(dataSource.isWrapperFor(UnavailableDataSource.class));
        assertFalse(dataSource.isWrapperFor(String.class));
        assertThrows(SQLException.class, () -> dataSource.unwrap(String.class));
        assertThrows(NullPointerException.class, () -> dataSource.unwrap(null));
        assertThrows(NullPointerException.class, () -> dataSource.isWrapperFor(null));
        assertThrows(IllegalArgumentException.class, () -> new UnavailableDataSource(" "));
    }

    @Test
    void exposesOnlyInertStandardDataSourceSettings() throws Exception {
        var dataSource = new UnavailableDataSource("memory mode");
        dataSource.setLogWriter(null);
        dataSource.setLoginTimeout(15);
        assertEquals(null, dataSource.getLogWriter());
        assertEquals(0, dataSource.getLoginTimeout());
    }
}
