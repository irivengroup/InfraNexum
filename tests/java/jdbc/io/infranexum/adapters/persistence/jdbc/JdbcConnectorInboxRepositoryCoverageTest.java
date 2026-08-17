package io.infranexum.adapters.persistence.jdbc;

import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.RetryPolicy;
import io.infranexum.integrations.ConnectorDeliveryNotFoundException;
import io.infranexum.integrations.ConnectorDeliveryStateConflictException;
import io.infranexum.integrations.ConnectorDeliveryStatus;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.DuplicateDeliveryConflictException;
import io.infranexum.integrations.WebhookAdmission;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Deterministic branch coverage for connector inbox/DLQ JDBC behavior across both supported dialects. */
final class JdbcConnectorInboxRepositoryCoverageTest {
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");
    private static final ConnectorKey KEY = new ConnectorKey("coverage.connector");
    private static final DomainIdentifier DELIVERY = id(1);
    private static final String SHA = "a".repeat(64);

    @Test
    void admissionCoversCreateDuplicateConflictAndOracleUniqueSavepoint() {
        var create = connection(update(1), query(deliveryRow(ConnectorDeliveryStatus.PENDING, 0, null, null)));
        var created = pg(create).admit(admission(DELIVERY, "provider-1", SHA));
        assertFalse(created.duplicate());

        var duplicate = connection(update(0), query(deliveryRow(ConnectorDeliveryStatus.PENDING, 0, null, null)));
        assertTrue(pg(duplicate).admit(admission(id(2), "provider-1", SHA)).duplicate());

        var conflict = connection(update(0), query(deliveryRow(ConnectorDeliveryStatus.PENDING, 0, null, null)));
        assertThrows(DuplicateDeliveryConflictException.class,
                () -> pg(conflict).admit(admission(id(3), "provider-1", "b".repeat(64))));

        var oracleCreate = connection(
                update(1), query(oracleRow(deliveryRow(ConnectorDeliveryStatus.PENDING, 0, null, null))));
        assertFalse(oracle(oracleCreate).admit(admission(id(40), "provider-40", SHA)).duplicate());

        var pgFailure = connection(updateFailure(new SQLException("offline", "08006", 2)));
        assertThrows(JdbcPersistenceException.class,
                () -> pg(pgFailure).admit(admission(id(41), "provider-41", SHA)));

        var oracleDuplicate = connection(
                updateFailure(new SQLException("duplicate", "23000", 1)),
                query(oracleRow(deliveryRow(ConnectorDeliveryStatus.PENDING, 0, null, null))));
        assertTrue(oracle(oracleDuplicate).admit(admission(id(4), "provider-1", SHA)).duplicate());

        var oracleFailure = connection(updateFailure(new SQLException("offline", "08006", 2)));
        assertThrows(JdbcPersistenceException.class,
                () -> oracle(oracleFailure).admit(admission(id(5), "provider-5", SHA)));
    }

    @Test
    void claimAndLeaseTransitionsCoverPostgresqlOracleAndFencing() {
        var pending = deliveryRow(ConnectorDeliveryStatus.PENDING, 0, null, null);
        var pgClaim = connection(query(List.of(pending))).autoCommit(true);
        assertEquals(1, pg(pgClaim).claimBatch("worker", 1, NOW, Duration.ofSeconds(5)).size());
        assertTrue(pgClaim.autoCommit(), "transaction restores the caller auto-commit mode");

        var oracleClaim = connection(query(List.of(oracleRow(pending))), update(1));
        var claimed = oracle(oracleClaim).claimBatch("worker", 1, NOW, Duration.ofSeconds(5));
        assertEquals(ConnectorDeliveryStatus.IN_FLIGHT, claimed.getFirst().status());
        assertEquals("worker", claimed.getFirst().leaseOwner());

        var processed = connection(
                query(deliveryRow(ConnectorDeliveryStatus.IN_FLIGHT, 1, "worker", NOW.plusSeconds(30))),
                update(1), update(1));
        pg(processed).markProcessed(DELIVERY, "worker", NOW.plusSeconds(1));

        var processedOracle = connection(
                query(oracleRow(deliveryRow(ConnectorDeliveryStatus.IN_FLIGHT, 1, "worker", NOW.plusSeconds(30)))),
                update(1), update(1));
        oracle(processedOracle).markProcessed(DELIVERY, "worker", NOW.plusSeconds(1));

        var wrongStatus = connection(query(deliveryRow(ConnectorDeliveryStatus.PENDING, 1, null, null)));
        assertThrows(IllegalStateException.class,
                () -> pg(wrongStatus).markProcessed(DELIVERY, "worker", NOW));
        var wrongWorker = connection(query(deliveryRow(ConnectorDeliveryStatus.IN_FLIGHT, 1, "other", NOW.plusSeconds(30))));
        assertThrows(IllegalStateException.class,
                () -> pg(wrongWorker).markProcessed(DELIVERY, "worker", NOW));
    }

    @Test
    void failurePathsCoverRetryDeadLetterPostgresqlAndOracleStateRaces() {
        RetryPolicy retryTwice = retry(2);
        var retry = connection(
                query(deliveryRow(ConnectorDeliveryStatus.IN_FLIGHT, 1, "worker", NOW.plusSeconds(30))),
                update(1));
        assertEquals(ConnectorDeliveryStatus.PENDING,
                pg(retry).markFailed(DELIVERY, "worker", NOW, retryTwice, new SQLException("x"), 2, Duration.ofMinutes(1)));

        var pgDead = connection(
                query(deliveryRow(ConnectorDeliveryStatus.IN_FLIGHT, 2, "worker", NOW.plusSeconds(30))),
                update(1), update(1));
        assertEquals(ConnectorDeliveryStatus.DEAD_LETTER,
                pg(pgDead).markFailed(DELIVERY, "worker", NOW, retryTwice, new SQLException("x"), 1, Duration.ofMinutes(1)));

        var oracleExisting = connection(
                query(oracleRow(deliveryRow(ConnectorDeliveryStatus.IN_FLIGHT, 2, "worker", NOW.plusSeconds(30)))),
                update(1), update(1));
        assertEquals(ConnectorDeliveryStatus.DEAD_LETTER,
                oracle(oracleExisting).markFailed(DELIVERY, "worker", NOW, retryTwice, new SQLException("x"), 2, Duration.ofMinutes(1)));

        var oracleInsert = connection(
                query(oracleRow(deliveryRow(ConnectorDeliveryStatus.IN_FLIGHT, 2, "worker", NOW.plusSeconds(30)))),
                update(1), update(0), update(1));
        assertEquals(ConnectorDeliveryStatus.DEAD_LETTER,
                oracle(oracleInsert).markFailed(DELIVERY, "worker", NOW, retryTwice, new SQLException("x"), 2, Duration.ofMinutes(1)));

        var oracleInsertFailure = connection(
                query(oracleRow(deliveryRow(ConnectorDeliveryStatus.IN_FLIGHT, 2, "worker", NOW.plusSeconds(30)))),
                update(1), update(0), updateFailure(new SQLException("offline", "08006", 2)));
        assertThrows(JdbcPersistenceException.class, () -> oracle(oracleInsertFailure).markFailed(
                DELIVERY, "worker", NOW, retryTwice, new SQLException("x"), 2, Duration.ofMinutes(1)));

        var oracleRace = connection(
                query(oracleRow(deliveryRow(ConnectorDeliveryStatus.IN_FLIGHT, 2, "worker", NOW.plusSeconds(30)))),
                update(1), update(0), updateFailure(new SQLException("duplicate", "23000", 1)), update(1));
        assertEquals(ConnectorDeliveryStatus.DEAD_LETTER,
                oracle(oracleRace).markFailed(DELIVERY, "worker", NOW, retryTwice, new SQLException("x"), 1, Duration.ofMinutes(1)));
    }

    @Test
    void replayRuntimeCountsAndDlqCoverMissingPresentAndStateConflicts() {
        var replay = connection(
                query(deliveryRow(ConnectorDeliveryStatus.DEAD_LETTER, 2, null, null)),
                update(1), query(deliveryRow(ConnectorDeliveryStatus.PENDING, 0, null, null)));
        assertEquals(ConnectorDeliveryStatus.PENDING, pg(replay).replay(DELIVERY, NOW).status());

        var conflict = connection(query(deliveryRow(ConnectorDeliveryStatus.PROCESSED, 1, null, null, NOW)));
        assertThrows(ConnectorDeliveryStateConflictException.class, () -> pg(conflict).replay(DELIVERY, NOW));

        var missing = connection(query(List.of()));
        assertThrows(ConnectorDeliveryNotFoundException.class, () -> pg(missing).replay(DELIVERY, NOW));

        var state = connection(query(stateRow(3, NOW.plusSeconds(30), NOW.minusSeconds(1), NOW)));
        assertEquals(3, pg(state).runtimeState(KEY).consecutiveDeadLetters());
        var stateMissing = connection(query(List.of()));
        assertEquals(0, pg(stateMissing).runtimeState(KEY).consecutiveDeadLetters());

        var resumePg = connection(update(1), query(stateRow(0, null, NOW, null)));
        assertEquals(0, pg(resumePg).resume(KEY, NOW).consecutiveDeadLetters());
        var resumeOracle = connection(query(stateRow(2, NOW.plusSeconds(10), NOW.minusSeconds(1), NOW)), update(1), query(stateRow(0, null, NOW.minusSeconds(1), NOW)));
        assertEquals(0, oracle(resumeOracle).resume(KEY, NOW).consecutiveDeadLetters());

        var counts = connection(query(Map.of("count", 4L)), query(Map.of("count", 2L)));
        assertEquals(4, pg(counts).backlogSize(KEY, NOW));
        assertEquals(2, pg(counts).deadLetterCount(KEY));
        var missingCount = connection(query(List.of()));
        assertThrows(JdbcPersistenceException.class, () -> pg(missingCount).deadLetterCount(KEY));

        var dlqPg = connection(query(List.of(deliveryRow(ConnectorDeliveryStatus.DEAD_LETTER, 2, null, null))));
        assertEquals(1, pg(dlqPg).listDeadLetters(KEY, 0, 1).size());
        var dlqOracle = connection(query(List.of(oracleRow(deliveryRow(ConnectorDeliveryStatus.DEAD_LETTER, 2, null, null)))));
        assertEquals(1, oracle(dlqOracle).listDeadLetters(null, 1, 1).size());
    }

    @Test
    void defensiveValidationAndAffectedRowGuardsRemainFailClosed() {
        var repository = pg(connection());
        assertThrows(IllegalArgumentException.class, () -> repository.claimBatch(" ", 1, NOW, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> repository.claimBatch("x".repeat(161), 1, NOW, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> repository.claimBatch("worker", 0, NOW, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> repository.claimBatch("worker", 1001, NOW, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> repository.claimBatch("worker", 1, NOW, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> repository.markFailed(DELIVERY, "worker", NOW, retry(1), new RuntimeException(), 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> repository.markFailed(DELIVERY, "worker", NOW, retry(1), new RuntimeException(), 101, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> repository.markFailed(DELIVERY, "worker", NOW, retry(1), new RuntimeException(), 1, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> repository.listDeadLetters(KEY, 1_000_001, 1));

        var updateMismatch = connection(
                query(deliveryRow(ConnectorDeliveryStatus.IN_FLIGHT, 1, "worker", NOW.plusSeconds(30))), update(0));
        assertThrows(IllegalStateException.class, () -> pg(updateMismatch).markProcessed(DELIVERY, "worker", NOW));

        var claimMismatch = connection(query(List.of(oracleRow(deliveryRow(ConnectorDeliveryStatus.PENDING, 0, null, null)))), update(0));
        assertThrows(IllegalStateException.class,
                () -> oracle(claimMismatch).claimBatch("worker", 1, NOW, Duration.ofSeconds(1)));
    }

    private static JdbcConnectorInboxRepository pg(ScriptedConnection c) {
        return new JdbcConnectorInboxRepository(dataSource(c.connection()), JdbcDatabaseDialect.POSTGRESQL);
    }

    private static JdbcConnectorInboxRepository oracle(ScriptedConnection c) {
        return new JdbcConnectorInboxRepository(dataSource(c.connection()), JdbcDatabaseDialect.ORACLE);
    }

    private static WebhookAdmission admission(DomainIdentifier id, String external, String sha) {
        return new WebhookAdmission(id, KEY, external, "{\"ok\":true}", sha, NOW);
    }

    private static RetryPolicy retry(int maximum) {
        return new RetryPolicy() {
            @Override public int maximumAttempts() { return maximum; }
            @Override public Duration delayAfterFailure(int attempts) { return Duration.ofSeconds(attempts); }
        };
    }

    private static LinkedHashMap<String, Object> deliveryRow(ConnectorDeliveryStatus status, int attempts, String owner, Instant leaseUntil) {
        return deliveryRow(status, attempts, owner, leaseUntil, null);
    }

    private static LinkedHashMap<String, Object> deliveryRow(ConnectorDeliveryStatus status, int attempts, String owner, Instant leaseUntil, Instant processedAt) {
        var row = new LinkedHashMap<String, Object>();
        row.put("delivery_id", DELIVERY.value());
        row.put("connector_key", KEY.value());
        row.put("external_delivery_id", "provider-1");
        row.put("payload_raw", "{\"ok\":true}");
        row.put("payload_sha256", SHA);
        row.put("status", status.name());
        row.put("attempts", attempts);
        row.put("received_at", NOW);
        row.put("available_at", NOW);
        row.put("lease_owner", owner);
        row.put("lease_until", leaseUntil);
        row.put("processed_at", processedAt);
        row.put("last_failure", status == ConnectorDeliveryStatus.DEAD_LETTER ? SQLException.class.getName() : null);
        row.put("replay_count", 0);
        row.put("last_replayed_at", null);
        return row;
    }

    private static LinkedHashMap<String, Object> stateRow(int failures, Instant suspendedUntil, Instant success, Instant failure) {
        var row = new LinkedHashMap<String, Object>();
        row.put("consecutive_dead_letters", failures);
        row.put("suspended_until", suspendedUntil);
        row.put("last_success_at", success);
        row.put("last_failure_at", failure);
        return row;
    }

    private static LinkedHashMap<String, Object> oracleRow(LinkedHashMap<String, Object> source) {
        var row = new LinkedHashMap<String, Object>(source);
        row.put("delivery_id", source.get("delivery_id").toString());
        return row;
    }

    private static DomainIdentifier id(int sequence) {
        return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(sequence)));
    }
}
