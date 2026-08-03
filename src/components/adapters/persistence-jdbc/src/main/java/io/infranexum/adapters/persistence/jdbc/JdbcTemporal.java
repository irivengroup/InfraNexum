package io.infranexum.adapters.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** JDBC 4.2 timestamp conversion preserving UTC instants across supported databases. */
final class JdbcTemporal {
    private JdbcTemporal() {}

    static void bindInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        statement.setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
    }

    static Instant readRequired(ResultSet resultSet, String column) throws SQLException {
        Instant value = readNullable(resultSet, column);
        if (value == null) {
            throw new SQLException("required timestamp is null: " + column);
        }
        return value;
    }

    static Instant readNullable(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new SQLException("unsupported timestamp representation for " + column);
    }
}
