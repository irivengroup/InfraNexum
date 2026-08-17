package io.infranexum.adapters.persistence.jdbc;

import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.connection;
import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.dataSource;
import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.query;
import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.update;
import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.updateFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.IdempotencyLedger;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exhaustive durable API-idempotency JDBC branch coverage. */
final class JdbcApiIdempotencyLedgerCoverageTest {
    private static final Instant T = Instant.parse("2026-08-17T00:00:00Z");

    @Test
    void findCoversMissingCompletedAndNullableStatusRows() {
        var missing = connection(query(List.of()));
        assertTrue(ledger(missing).find("scope", "op", "key").isEmpty());

        var completedRow = row("COMPLETED", 201);
        var completed = connection(query(completedRow));
        IdempotencyLedger.Entry entry = ledger(completed).find("scope", "op", "key").orElseThrow();
        assertEquals(201, entry.httpStatus());
        assertEquals("application/json", entry.contentType());

        var indeterminateRow = row("INDETERMINATE", null);
        var indeterminate = connection(query(indeterminateRow));
        IdempotencyLedger.Entry nullable = ledger(indeterminate).find("scope", "op", "key").orElseThrow();
        assertNull(nullable.httpStatus());
    }

    @Test
    void reserveCoversSuccessUniqueConflictAndInfrastructureFailure() {
        var success = connection(update(1));
        assertTrue(ledger(success).reserve("scope", "op", "key", "a".repeat(64), T));

        var duplicate = connection(updateFailure(new SQLException("duplicate", "23505")));
        assertFalse(ledger(duplicate).reserve("scope", "op", "key", "a".repeat(64), T));

        var unavailable = connection(updateFailure(new SQLException("offline", "08006")));
        assertThrows(JdbcPersistenceException.class,
                () -> ledger(unavailable).reserve("scope", "op", "key", "a".repeat(64), T));
    }

    @Test
    void completeCoversNullableAndNonNullableBindingsAndAffectedRowGuard() {
        var complete = connection(update(1), update(1));
        JdbcApiIdempotencyLedger ledger = ledger(complete);
        ledger.complete("scope", "op", "key", "a".repeat(64), 200,
                null, null, null, null, T);
        ledger.complete("scope", "op", "key2", "b".repeat(64), 201,
                "application/json", "etag", "/resource/1", "e30=", T.plusSeconds(1));

        var missingTransition = connection(update(0));
        assertThrows(JdbcPersistenceException.class, () -> ledger(missingTransition).complete(
                "scope", "op", "key", "a".repeat(64), 200, null, null, null, null, T));
    }

    @Test
    void nonStrictTransitionsAcceptZeroRowsAndSqlFailuresRemainWrapped() {
        JdbcApiIdempotencyLedger ledger = ledger(connection(update(0), update(0)));
        ledger.markIndeterminate("scope", "op", "key", "a".repeat(64), T);
        ledger.release("scope", "op", "key", "a".repeat(64));

        var failed = connection(updateFailure(new SQLException("offline", "08006")));
        assertThrows(JdbcPersistenceException.class,
                () -> ledger(failed).markIndeterminate("scope", "op", "key", "a".repeat(64), T));
    }

    private static JdbcApiIdempotencyLedger ledger(JdbcScriptedSupport.ScriptedConnection connection) {
        return new JdbcApiIdempotencyLedger(dataSource(connection.connection()), JdbcDatabaseDialect.POSTGRESQL);
    }

    private static Map<String, Object> row(String state, Integer status) {
        var row = new LinkedHashMap<String, Object>();
        row.put("scope_key", "scope");
        row.put("operation_name", "op");
        row.put("idempotency_key", "key");
        row.put("request_sha256", "a".repeat(64));
        row.put("state", state);
        row.put("http_status", status);
        row.put("content_type", "application/json");
        row.put("etag", "etag");
        row.put("location", "/resource/1");
        row.put("response_body_b64", "e30=");
        row.put("created_at", T);
        row.put("updated_at", T);
        return row;
    }
}
