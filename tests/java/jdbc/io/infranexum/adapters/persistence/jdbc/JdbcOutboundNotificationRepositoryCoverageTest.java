package io.infranexum.adapters.persistence.jdbc;

import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.RetryPolicy;
import io.infranexum.integrations.*;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Hosted-JDK coverage closure for the durable notification JDBC repository. */
final class JdbcOutboundNotificationRepositoryCoverageTest {
    private static final Instant NOW = Instant.parse("2026-08-17T20:00:00Z");
    private static final ConnectorKey KEY = new ConnectorKey("ops-webhook");
    private static final DomainIdentifier ID = new DomainIdentifier(UUID.fromString("0198b170-0000-7001-8000-000000000001"));
    private static final RetryPolicy RETRY = new RetryPolicy() {
        @Override public int maximumAttempts() { return 3; }
        @Override public Duration delayAfterFailure(int attempts) { return Duration.ofSeconds(attempts); }
    };

    @Test
    void validatesPublicBoundariesAndWrapsConnectionFailures() {
        assertThrows(NullPointerException.class, () -> new JdbcOutboundNotificationRepository(null, JdbcDatabaseDialect.POSTGRESQL));
        assertThrows(NullPointerException.class, () -> new JdbcOutboundNotificationRepository(dataSource(connection().connection()), null));
        var repo = repository(JdbcDatabaseDialect.POSTGRESQL, connection());
        assertThrows(NullPointerException.class, () -> repo.admit(null));
        assertThrows(IllegalArgumentException.class, () -> repo.claimBatch(" ", 1, NOW, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> repo.claimBatch("w", 0, NOW, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> repo.claimBatch("w", 1001, NOW, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> repo.claimBatch("w", 1, null, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> repo.claimBatch("w", 1, NOW, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> repo.listDeadLetters(null, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> repo.listDeadLetters(null, 1_000_001, 1));
        assertThrows(IllegalArgumentException.class, () -> repo.markFailed(ID, "w", NOW, RETRY, new RuntimeException(), true, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> repo.markFailed(ID, "w", NOW, RETRY, new RuntimeException(), true, 101, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> repo.markFailed(ID, "w", NOW, RETRY, new RuntimeException(), true, 1, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> repo.runtimeState(null));
        assertThrows(NullPointerException.class, () -> repo.resume(null, NOW));
        assertThrows(NullPointerException.class, () -> repo.backlogSize(null, NOW));
        assertThrows(NullPointerException.class, () -> repo.deadLetterCount(null));
        var failing = new JdbcOutboundNotificationRepository(failingDataSource(new SQLException("offline")), JdbcDatabaseDialect.POSTGRESQL);
        assertInstanceOf(SQLException.class, assertThrows(JdbcPersistenceException.class, () -> failing.runtimeState(KEY)).getCause());
    }

    @Test
    void admitsNewDuplicateAndConflictingDeliveriesAcrossDialects() {
        var admission = admission();
        var pgInsert = connection(update(1), query(row("PENDING", 0, null, null, null, 0, null)));
        var pg = repository(JdbcDatabaseDialect.POSTGRESQL, pgInsert);
        assertFalse(pg.admit(admission).duplicate());
        assertTrue(pgInsert.exhausted());

        var duplicate = connection(update(0), query(row("PENDING", 0, null, null, null, 0, null)));
        assertTrue(repository(JdbcDatabaseDialect.POSTGRESQL, duplicate).admit(admission).duplicate());

        var conflictRow = row("PENDING", 0, null, null, null, 0, null);
        conflictRow.put("payload_sha256", "b".repeat(64));
        var conflict = connection(update(0), query(conflictRow));
        assertThrows(DuplicateDeliveryConflictException.class,
                () -> repository(JdbcDatabaseDialect.POSTGRESQL, conflict).admit(admission));

        var oracle = connection(updateFailure(new SQLException("duplicate", "23000", 1)), query(row("PENDING", 0, null, null, null, 0, null)));
        assertTrue(repository(JdbcDatabaseDialect.ORACLE, oracle).admit(admission).duplicate());
        assertTrue(oracle.exhausted());
    }

    @Test
    void claimsPostgresqlAndOracleAndEnforcesLeaseOwnership() {
        var pgConn = connection(query(row("PENDING", 0, null, null, null, 0, null)));
        var pgClaimed = repository(JdbcDatabaseDialect.POSTGRESQL, pgConn).claimBatch("worker-1", 5, NOW, Duration.ofSeconds(30));
        assertEquals(1, pgClaimed.size());

        var oracleConn = connection(query(row("PENDING", 0, null, null, null, 0, null)), update(1));
        var oracleClaimed = repository(JdbcDatabaseDialect.ORACLE, oracleConn).claimBatch("worker-2", 5, NOW, Duration.ofSeconds(30));
        assertEquals(OutboundNotificationStatus.IN_FLIGHT, oracleClaimed.getFirst().status());
        assertEquals("worker-2", oracleClaimed.getFirst().leaseOwner());

        var wrongLease = connection(query(row("IN_FLIGHT", 1, "another", NOW.plusSeconds(30), null, 0, null)));
        assertThrows(IllegalStateException.class,
                () -> repository(JdbcDatabaseDialect.POSTGRESQL, wrongLease).markDelivered(ID, "worker-1", NOW));
    }

    @Test
    void deliversRetriesAndDeadLettersWithDialectSpecificStateUpdates() {
        var deliveredPg = connection(
                query(row("IN_FLIGHT", 1, "worker", NOW.plusSeconds(30), null, 0, null)), update(1), update(1));
        repository(JdbcDatabaseDialect.POSTGRESQL, deliveredPg).markDelivered(ID, "worker", NOW);
        assertTrue(deliveredPg.exhausted());

        var deliveredOracle = connection(
                query(row("IN_FLIGHT", 1, "worker", NOW.plusSeconds(30), null, 0, null)), update(1), update(1));
        repository(JdbcDatabaseDialect.ORACLE, deliveredOracle).markDelivered(ID, "worker", NOW);

        var retry = connection(query(row("IN_FLIGHT", 1, "worker", NOW.plusSeconds(30), null, 0, null)), update(1));
        assertEquals(OutboundNotificationStatus.PENDING,
                repository(JdbcDatabaseDialect.POSTGRESQL, retry).markFailed(
                        ID, "worker", NOW, RETRY, new IllegalStateException("transient"), true, 2, Duration.ofMinutes(5)));

        var deadPg = connection(query(row("IN_FLIGHT", 3, "worker", NOW.plusSeconds(30), null, 0, null)), update(1), update(1));
        assertEquals(OutboundNotificationStatus.DEAD_LETTER,
                repository(JdbcDatabaseDialect.POSTGRESQL, deadPg).markFailed(
                        ID, "worker", NOW, RETRY,
                        new OutboundNotificationTransportException("REMOTE_503", true), true, 1, Duration.ofMinutes(5)));

        var deadOracleUpdate = connection(query(row("IN_FLIGHT", 1, "worker", NOW.plusSeconds(30), null, 0, null)), update(1), update(1));
        assertEquals(OutboundNotificationStatus.DEAD_LETTER,
                repository(JdbcDatabaseDialect.ORACLE, deadOracleUpdate).markFailed(
                        ID, "worker", NOW, RETRY, new RuntimeException("permanent"), false, 2, Duration.ofMinutes(5)));

        var deadOracleInsert = connection(query(row("IN_FLIGHT", 1, "worker", NOW.plusSeconds(30), null, 0, null)), update(1), update(0), update(1));
        assertEquals(OutboundNotificationStatus.DEAD_LETTER,
                repository(JdbcDatabaseDialect.ORACLE, deadOracleInsert).markFailed(
                        ID, "worker", NOW, RETRY, new RuntimeException("permanent"), false, 1, Duration.ofMinutes(5)));
    }

    @Test
    void listsReplaysCountsResumesAndMapsRuntimeState() {
        var pgList = connection(query(List.of(row("DEAD_LETTER", 2, null, null, null, 1, NOW.minusSeconds(1)))));
        assertEquals(1, repository(JdbcDatabaseDialect.POSTGRESQL, pgList).listDeadLetters(null, 0, 10).size());
        var oracleList = connection(query(List.of(row("DEAD_LETTER", 2, null, null, null, 1, NOW.minusSeconds(1)))));
        assertEquals(1, repository(JdbcDatabaseDialect.ORACLE, oracleList).listDeadLetters(KEY, 2, 10).size());

        var replay = connection(query(row("DEAD_LETTER", 2, null, null, null, 1, NOW.minusSeconds(1))), update(1),
                query(row("PENDING", 0, null, null, null, 2, NOW)));
        assertEquals(OutboundNotificationStatus.PENDING, repository(JdbcDatabaseDialect.POSTGRESQL, replay).replay(ID, NOW).status());
        var conflict = connection(query(row("PENDING", 0, null, null, null, 0, null)));
        assertThrows(OutboundNotificationStateConflictException.class,
                () -> repository(JdbcDatabaseDialect.POSTGRESQL, conflict).replay(ID, NOW));
        var missing = connection(query(List.of()));
        assertThrows(OutboundNotificationNotFoundException.class,
                () -> repository(JdbcDatabaseDialect.POSTGRESQL, missing).replay(ID, NOW));

        var emptyState = connection(query(List.of()));
        assertEquals(0, repository(JdbcDatabaseDialect.POSTGRESQL, emptyState).runtimeState(KEY).consecutiveDeadLetters());
        var state = connection(query(runtimeRow(3, NOW.plusSeconds(30), NOW.minusSeconds(60), NOW)));
        assertTrue(repository(JdbcDatabaseDialect.POSTGRESQL, state).runtimeState(KEY).suspendedAt(NOW));

        var resumePg = connection(update(1), query(runtimeRow(0, null, NOW.minusSeconds(60), NOW.minusSeconds(30))));
        assertFalse(repository(JdbcDatabaseDialect.POSTGRESQL, resumePg).resume(KEY, NOW).suspendedAt(NOW));
        var resumeOracle = connection(query(runtimeRow(2, NOW.plusSeconds(30), NOW.minusSeconds(60), NOW.minusSeconds(30))), update(1),
                query(runtimeRow(0, null, NOW.minusSeconds(60), NOW.minusSeconds(30))));
        assertEquals(0, repository(JdbcDatabaseDialect.ORACLE, resumeOracle).resume(KEY, NOW).consecutiveDeadLetters());

        var backlog = connection(query(Map.of("count", 4L)));
        assertEquals(4L, repository(JdbcDatabaseDialect.POSTGRESQL, backlog).backlogSize(KEY, NOW));
        var deadCount = connection(query(Map.of("count", 2L)));
        assertEquals(2L, repository(JdbcDatabaseDialect.ORACLE, deadCount).deadLetterCount(KEY));
        var badCount = connection(query(List.of()));
        assertThrows(JdbcPersistenceException.class,
                () -> repository(JdbcDatabaseDialect.POSTGRESQL, badCount).deadLetterCount(KEY));
    }

    @Test
    void rejectsUnexpectedMutationCountsAndSqlFailuresWithoutLosingCause() {
        var badUpdate = connection(query(row("IN_FLIGHT", 1, "worker", NOW.plusSeconds(30), null, 0, null)), update(0));
        assertThrows(IllegalStateException.class,
                () -> repository(JdbcDatabaseDialect.POSTGRESQL, badUpdate).markDelivered(ID, "worker", NOW));
        var sqlFailure = connection(queryFailure(new SQLException("query failed")));
        JdbcPersistenceException failure = assertThrows(JdbcPersistenceException.class,
                () -> repository(JdbcDatabaseDialect.POSTGRESQL, sqlFailure).runtimeState(KEY));
        assertEquals("query failed", failure.getCause().getMessage());
    }


    @Test
    void closesOracleRacesAndTransactionBranchesWithoutWeakeningFencing() {
        var oracleInsert = connection(update(1), query(row("PENDING", 0, null, null, null, 0, null)));
        assertFalse(repository(JdbcDatabaseDialect.ORACLE, oracleInsert).admit(admission()).duplicate());
        assertTrue(oracleInsert.exhausted());

        var oracleNonUnique = connection(updateFailure(new SQLException("table missing", "42000", 942)));
        JdbcPersistenceException insertFailure = assertThrows(JdbcPersistenceException.class,
                () -> repository(JdbcDatabaseDialect.ORACLE, oracleNonUnique).admit(admission()));
        assertEquals(942, ((SQLException) insertFailure.getCause()).getErrorCode());

        var emptyOracleClaim = connection(query(List.of()), update(1));
        assertTrue(repository(JdbcDatabaseDialect.ORACLE, emptyOracleClaim)
                .claimBatch("worker-empty", 10, NOW, Duration.ofSeconds(30)).isEmpty());
        assertTrue(emptyOracleClaim.exhausted());

        var race = connection(
                query(row("IN_FLIGHT", 1, "worker", NOW.plusSeconds(30), null, 0, null)),
                update(1),
                update(0),
                updateFailure(new SQLException("duplicate state", "23000", 1)),
                update(1));
        assertEquals(OutboundNotificationStatus.DEAD_LETTER,
                repository(JdbcDatabaseDialect.ORACLE, race).markFailed(
                        ID, "worker", NOW, RETRY, new RuntimeException("permanent"), false, 2, Duration.ofMinutes(5)));
        assertTrue(race.exhausted());

        var thresholdAboveOneInsert = connection(
                query(row("IN_FLIGHT", 1, "worker", NOW.plusSeconds(30), null, 0, null)),
                update(1), update(0), update(1));
        assertEquals(OutboundNotificationStatus.DEAD_LETTER,
                repository(JdbcDatabaseDialect.ORACLE, thresholdAboveOneInsert).markFailed(
                        ID, "worker", NOW, RETRY, new RuntimeException("permanent"), false, 2, Duration.ofMinutes(5)));

        var autoCommit = connection(query(runtimeRow(0, null, null, null))).autoCommit(true);
        assertEquals(0, repository(JdbcDatabaseDialect.POSTGRESQL, autoCommit).runtimeState(KEY).consecutiveDeadLetters());
        assertTrue(autoCommit.autoCommit(), "repository must restore caller auto-commit state");
    }

    @Test
    void coversFailureCodeBoundsAndRemainingInputEdges() {
        var repo = repository(JdbcDatabaseDialect.POSTGRESQL, connection());
        assertThrows(NullPointerException.class, () -> repo.claimBatch(null, 1, NOW, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> repo.claimBatch("w".repeat(161), 1, NOW, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class,
                () -> repo.markFailed(ID, "w", NOW, RETRY, new RuntimeException(), true, 1, null));

        var longFailure = connection(
                query(row("IN_FLIGHT", 1, "worker", NOW.plusSeconds(30), null, 0, null)), update(1));
        repository(JdbcDatabaseDialect.POSTGRESQL, longFailure).markFailed(
                ID, "worker", NOW, RETRY,
                new ThisFailureClassNameIsIntentionallyLongEnoughToExerciseNotificationFailureCodeTruncationBoundary(),
                true, 2, Duration.ofMinutes(5));
        Object failureCode = longFailure.parameters().get(1).get(3);
        assertInstanceOf(String.class, failureCode);
        assertEquals(64, ((String) failureCode).length());

        var pgFiltered = connection(query(List.of()));
        assertTrue(repository(JdbcDatabaseDialect.POSTGRESQL, pgFiltered).listDeadLetters(KEY, 0, 10).isEmpty());
        var oracleUnfiltered = connection(query(List.of()));
        assertTrue(repository(JdbcDatabaseDialect.ORACLE, oracleUnfiltered).listDeadLetters(null, 0, 10).isEmpty());
    }

    private static final class ThisFailureClassNameIsIntentionallyLongEnoughToExerciseNotificationFailureCodeTruncationBoundary
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static JdbcOutboundNotificationRepository repository(JdbcDatabaseDialect dialect, ScriptedConnection connection) {
        return new JdbcOutboundNotificationRepository(dataSource(connection.connection()), dialect);
    }

    private static OutboundNotificationAdmission admission() {
        return new OutboundNotificationAdmission(ID, KEY, "evt-20260817-0001", "infra.changed", "{}".getBytes(), "a".repeat(64), NOW);
    }

    private static Map<String, Object> row(String status, int attempts, String owner, Instant leaseUntil,
            Instant deliveredAt, int replayCount, Instant replayedAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("delivery_id", ID.value());
        row.put("endpoint_key", KEY.value());
        row.put("event_id", "evt-20260817-0001");
        row.put("event_type", "infra.changed");
        row.put("payload_json", "{}");
        row.put("payload_sha256", "a".repeat(64));
        row.put("status", status);
        row.put("attempts", attempts);
        row.put("created_at", NOW.minusSeconds(60));
        row.put("available_at", NOW.minusSeconds(30));
        row.put("lease_owner", owner);
        row.put("lease_until", leaseUntil);
        row.put("delivered_at", deliveredAt);
        row.put("last_failure", status.equals("DEAD_LETTER") ? "REMOTE_503" : null);
        row.put("replay_count", replayCount);
        row.put("last_replayed_at", replayedAt);
        return row;
    }

    private static Map<String, Object> runtimeRow(int failures, Instant suspended, Instant success, Instant failure) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("consecutive_dead_letters", failures);
        row.put("suspended_until", suspended);
        row.put("last_success_at", success);
        row.put("last_failure_at", failure);
        return row;
    }
}
