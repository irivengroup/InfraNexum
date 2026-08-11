package io.infranexum.adapters.persistence.jdbc;

/** Safe persistence failure that preserves the JDBC cause without exposing SQL or credentials. */
public final class JdbcPersistenceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JdbcPersistenceException(String operation, Throwable cause) {
        super("JDBC persistence operation failed: " + operation, cause);
    }
}
