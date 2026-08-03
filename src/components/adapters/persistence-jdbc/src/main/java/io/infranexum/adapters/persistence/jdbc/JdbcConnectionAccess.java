package io.infranexum.adapters.persistence.jdbc;

import java.sql.Connection;

/**
 * Gives JDBC repositories access to the connection owned by the current unit of work.
 *
 * <p>Callers must never close, commit, roll back, or retain the returned connection.
 */
public interface JdbcConnectionAccess {
    Connection requireCurrentConnection();
}
