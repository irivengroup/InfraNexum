package io.infranexum.server.persistence;

import java.sql.Connection;

/** Supported transaction isolation levels for JDBC units of work. */
public enum JdbcIsolation {
    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),
    SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

    private final int jdbcValue;

    JdbcIsolation(int jdbcValue) {
        this.jdbcValue = jdbcValue;
    }

    public int jdbcValue() {
        return jdbcValue;
    }
}
