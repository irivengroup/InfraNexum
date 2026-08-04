package io.infranexum.server.persistence;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Explicit non-JDBC DataSource used only by the isolated MEMORY runtime.
 *
 * <p>Its presence prevents Spring Boot from silently creating a pooled database connection when the
 * operator explicitly selected MEMORY. Any accidental JDBC access fails immediately and visibly.
 */
public final class UnavailableDataSource implements DataSource {
    private final String reason;

    public UnavailableDataSource(String reason) {
        this.reason = Objects.requireNonNull(reason, "reason").strip();
        if (this.reason.isEmpty()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        throw unavailable();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw unavailable();
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // No underlying driver exists in MEMORY mode.
    }

    @Override
    public void setLoginTimeout(int seconds) {
        // No underlying driver exists in MEMORY mode.
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("MEMORY mode has no JDBC driver hierarchy");
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        Objects.requireNonNull(type, "type");
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new SQLException("not a wrapper for " + type.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return Objects.requireNonNull(type, "type").isInstance(this);
    }

    private SQLException unavailable() {
        return new SQLException(reason);
    }
}
