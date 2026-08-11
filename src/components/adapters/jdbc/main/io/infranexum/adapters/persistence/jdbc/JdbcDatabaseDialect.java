package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.InboxReservation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** SQL and identifier mapping required by the certified PostgreSQL and Oracle dialects. */
public enum JdbcDatabaseDialect {
    POSTGRESQL {
        @Override
        void bindIdentifier(PreparedStatement statement, int index, DomainIdentifier identifier)
                throws SQLException {
            statement.setObject(index, identifier.value());
        }

        @Override
        boolean tryReserveInbox(Connection connection, InboxReservation reservation) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(postgresqlReserveInboxSql())) {
                bindReservation(statement, reservation);
                return statement.executeUpdate() == 1;
            }
        }

        @Override
        String insertOutboxSql() {
            return """
                    /*inx:outbox-insert*/
                    INSERT INTO infranexum_core.outbox_event (
                        event_id, event_type, schema_version, occurred_at, event_source,
                        correlation_id, causation_id, payload_json, status, attempts, available_at,
                        lease_owner, lease_until, published_at, last_failure, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, %s, 'PENDING', 0, ?, NULL, NULL, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.formatted(jsonParameter());
        }

        @Override
        String claimReturningSql() {
            return """
                    /*inx:outbox-claim-postgresql*/
                    WITH candidates AS (
                        SELECT event_id
                        FROM infranexum_core.outbox_event
                        WHERE (status = 'PENDING' AND available_at <= ?)
                           OR (status = 'IN_FLIGHT' AND lease_until <= ?)
                        ORDER BY available_at, occurred_at, event_id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                    )
                    UPDATE infranexum_core.outbox_event AS event
                    SET status = 'IN_FLIGHT', attempts = event.attempts + 1,
                        lease_owner = ?, lease_until = ?, updated_at = ?
                    FROM candidates
                    WHERE event.event_id = candidates.event_id
                    RETURNING event.*
                    """;
        }

        @Override
        boolean supportsClaimReturning() {
            return true;
        }
    },
    ORACLE {
        @Override
        void bindIdentifier(PreparedStatement statement, int index, DomainIdentifier identifier)
                throws SQLException {
            statement.setString(index, identifier.toString());
        }

        @Override
        boolean tryReserveInbox(Connection connection, InboxReservation reservation) throws SQLException {
            Savepoint savepoint = connection.setSavepoint();
            try (PreparedStatement statement = connection.prepareStatement(oracleReserveInboxSql())) {
                bindReservation(statement, reservation);
                boolean inserted = statement.executeUpdate() == 1;
                connection.releaseSavepoint(savepoint);
                return inserted;
            } catch (SQLException failure) {
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
                if (isUniqueViolation(failure)) {
                    return false;
                }
                throw failure;
            }
        }

        @Override
        String insertOutboxSql() {
            return """
                    /*inx:outbox-insert*/
                    INSERT INTO INFRANEXUM_CORE_OUTBOX_EVENT (
                        EVENT_ID, EVENT_TYPE, SCHEMA_VERSION, OCCURRED_AT, EVENT_SOURCE,
                        CORRELATION_ID, CAUSATION_ID, PAYLOAD_JSON, STATUS, ATTEMPTS, AVAILABLE_AT,
                        LEASE_OWNER, LEASE_UNTIL, PUBLISHED_AT, LAST_FAILURE, CREATED_AT, UPDATED_AT
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, %s, 'PENDING', 0, ?, NULL, NULL, NULL, NULL, SYSTIMESTAMP, SYSTIMESTAMP)
                    """.formatted(jsonParameter());
        }

        @Override
        boolean isUniqueViolation(SQLException failure) {
            return failure.getErrorCode() == 1;
        }
    };

    private static final String COLUMNS = """
            event_id, event_type, schema_version, occurred_at, event_source,
            correlation_id, causation_id, payload_json, status, attempts, available_at,
            lease_owner, lease_until, published_at, last_failure
            """;

    abstract void bindIdentifier(PreparedStatement statement, int index, DomainIdentifier identifier)
            throws SQLException;

    void bindNullableIdentifier(PreparedStatement statement, int index, DomainIdentifier identifier)
            throws SQLException {
        if (identifier == null) {
            statement.setNull(index, this == POSTGRESQL ? java.sql.Types.OTHER : java.sql.Types.CHAR);
        } else {
            bindIdentifier(statement, index, identifier);
        }
    }

    abstract boolean tryReserveInbox(Connection connection, InboxReservation reservation) throws SQLException;

    abstract String insertOutboxSql();

    /** Returns the SQL placeholder required to bind a JSON document for this database. */
    String jsonParameter() {
        return this == POSTGRESQL ? "CAST(? AS JSONB)" : "?";
    }

    /**
     * Binds JSON without relying on driver-specific implicit casts. PostgreSQL receives a text value
     * through an explicit JSONB cast in SQL; Oracle receives a character stream suitable for CLOB
     * JSON columns, avoiding VARCHAR size limits for larger manifests and event payloads.
     */
    void bindJson(PreparedStatement statement, int index, String json) throws SQLException {
        Objects.requireNonNull(statement, "statement");
        String value = Objects.requireNonNull(json, "json");
        if (this == POSTGRESQL) {
            statement.setString(index, value);
        } else {
            statement.setCharacterStream(index, new java.io.StringReader(value), value.length());
        }
    }

    String claimReturningSql() {
        throw new UnsupportedOperationException("dialect does not support UPDATE RETURNING claims");
    }

    boolean supportsClaimReturning() {
        return false;
    }

    boolean isUniqueViolation(SQLException failure) {
        return "23505".equals(failure.getSQLState());
    }

    DomainIdentifier readIdentifier(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value instanceof UUID uuid) {
            return new DomainIdentifier(uuid);
        }
        if (value instanceof String text) {
            return DomainIdentifier.parse(text.strip().toLowerCase(Locale.ROOT));
        }
        throw new SQLException("unsupported identifier representation for " + column);
    }

    String selectClaimCandidatesSql() {
        String table = this == POSTGRESQL
                ? "infranexum_core.outbox_event"
                : "INFRANEXUM_CORE_OUTBOX_EVENT";
        return "/*inx:outbox-select-oracle*/ SELECT " + COLUMNS + " FROM " + table + " "
                + "WHERE (status = 'PENDING' AND available_at <= ?) "
                + "OR (status = 'IN_FLIGHT' AND lease_until <= ?) "
                + "ORDER BY available_at, occurred_at, event_id FOR UPDATE SKIP LOCKED";
    }

    String claimOneSql() {
        String table = this == POSTGRESQL
                ? "infranexum_core.outbox_event"
                : "INFRANEXUM_CORE_OUTBOX_EVENT";
        return "/*inx:outbox-claim-oracle*/ UPDATE " + table + " "
                + "SET status = 'IN_FLIGHT', attempts = attempts + 1, lease_owner = ?, "
                + "lease_until = ?, updated_at = ? WHERE event_id = ?";
    }

    String publishSql() {
        String table = this == POSTGRESQL
                ? "infranexum_core.outbox_event"
                : "INFRANEXUM_CORE_OUTBOX_EVENT";
        return "/*inx:outbox-publish*/ UPDATE " + table + " "
                + "SET status = 'PUBLISHED', published_at = ?, lease_owner = NULL, lease_until = NULL, "
                + "last_failure = NULL, updated_at = ? "
                + "WHERE event_id = ? AND status = 'IN_FLIGHT' AND lease_owner = ?";
    }

    String selectLeaseSql() {
        String table = this == POSTGRESQL
                ? "infranexum_core.outbox_event"
                : "INFRANEXUM_CORE_OUTBOX_EVENT";
        return "/*inx:outbox-state*/ SELECT status, attempts, lease_owner FROM " + table
                + " WHERE event_id = ? FOR UPDATE";
    }

    String failSql() {
        String table = this == POSTGRESQL
                ? "infranexum_core.outbox_event"
                : "INFRANEXUM_CORE_OUTBOX_EVENT";
        return "/*inx:outbox-fail*/ UPDATE " + table + " "
                + "SET status = ?, available_at = ?, lease_owner = NULL, lease_until = NULL, "
                + "last_failure = ?, updated_at = ? "
                + "WHERE event_id = ? AND status = 'IN_FLIGHT' AND lease_owner = ?";
    }

    String completeInboxSql() {
        String table = this == POSTGRESQL
                ? "infranexum_core.inbox_receipt"
                : "INFRANEXUM_CORE_INBOX_RECEIPT";
        return "/*inx:inbox-complete*/ UPDATE " + table + " "
                + "SET status = 'COMPLETED', completed_at = ? "
                + "WHERE consumer_name = ? AND event_id = ? AND status = 'PROCESSING'";
    }

    String inboxStatusSql() {
        String table = this == POSTGRESQL
                ? "infranexum_core.inbox_receipt"
                : "INFRANEXUM_CORE_INBOX_RECEIPT";
        return "/*inx:inbox-status*/ SELECT status FROM " + table
                + " WHERE consumer_name = ? AND event_id = ?";
    }

    private static String postgresqlReserveInboxSql() {
        return """
                /*inx:inbox-reserve*/
                INSERT INTO infranexum_core.inbox_receipt (
                    consumer_name, event_id, event_type, payload_sha256, received_at, completed_at, status
                ) VALUES (?, ?, ?, ?, ?, NULL, 'PROCESSING')
                ON CONFLICT (consumer_name, event_id) DO NOTHING
                """;
    }

    private static String oracleReserveInboxSql() {
        return """
                /*inx:inbox-reserve*/
                INSERT INTO INFRANEXUM_CORE_INBOX_RECEIPT (
                    CONSUMER_NAME, EVENT_ID, EVENT_TYPE, PAYLOAD_SHA256, RECEIVED_AT, COMPLETED_AT, STATUS
                ) VALUES (?, ?, ?, ?, ?, NULL, 'PROCESSING')
                """;
    }

    final void bindReservation(PreparedStatement statement, InboxReservation reservation)
            throws SQLException {
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(reservation, "reservation");
        statement.setString(1, reservation.key().consumerName());
        bindIdentifier(statement, 2, reservation.key().eventId());
        statement.setString(3, reservation.eventType().value());
        statement.setString(4, reservation.payloadSha256());
        JdbcTemporal.bindInstant(statement, 5, reservation.receivedAt());
    }
}
