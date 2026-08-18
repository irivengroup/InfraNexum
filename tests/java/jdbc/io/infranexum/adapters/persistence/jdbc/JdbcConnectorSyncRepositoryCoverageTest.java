package io.infranexum.adapters.persistence.jdbc;

import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.integrations.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Hosted-JDK coverage for fenced connector synchronization persistence. */
final class JdbcConnectorSyncRepositoryCoverageTest {
    private static final Instant NOW = Instant.parse("2026-08-18T08:00:00Z");
    private static final ConnectorKey KEY = new ConnectorKey("sync-jdbc");
    private static final DomainIdentifier RUN = id("0198b180-0000-7001-8000-000000000001");
    private static final DomainIdentifier CHECKPOINT = id("0198b180-0000-7002-8000-000000000002");
    private static final DomainIdentifier ACTOR = id("0198b180-0000-7003-8000-000000000003");
    private static final DomainIdentifier CORRELATION = id("0198b180-0000-7004-8000-000000000004");
    private static final String EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String CURSOR_SHA = "a".repeat(64);

    @Test
    void validatesPublicBoundariesAndWrapsConnectionFailures() {
        assertThrows(NullPointerException.class, () -> new JdbcConnectorSyncRepository(null, JdbcDatabaseDialect.POSTGRESQL));
        assertThrows(NullPointerException.class, () -> new JdbcConnectorSyncRepository(dataSource(connection().connection()), null));
        JdbcConnectorSyncRepository repo = repository(JdbcDatabaseDialect.POSTGRESQL, connection());
        assertThrows(NullPointerException.class, () -> repo.begin(null, KEY, "provider", ConnectorSyncDirection.INBOUND,
                ConnectorRollbackStrategy.LOCAL_CHECKPOINT, "idem", CURSOR_SHA, Set.of(), false, 1, ACTOR, CORRELATION, NOW));
        assertThrows(NullPointerException.class, () -> repo.begin(RUN, null, "provider", ConnectorSyncDirection.INBOUND,
                ConnectorRollbackStrategy.LOCAL_CHECKPOINT, "idem", CURSOR_SHA, Set.of(), false, 1, ACTOR, CORRELATION, NOW));
        assertThrows(NullPointerException.class, () -> repo.begin(RUN, KEY, null, ConnectorSyncDirection.INBOUND,
                ConnectorRollbackStrategy.LOCAL_CHECKPOINT, "idem", CURSOR_SHA, Set.of(), false, 1, ACTOR, CORRELATION, NOW));
        assertThrows(NullPointerException.class, () -> repo.begin(RUN, KEY, "provider", null,
                ConnectorRollbackStrategy.LOCAL_CHECKPOINT, "idem", CURSOR_SHA, Set.of(), false, 1, ACTOR, CORRELATION, NOW));
        assertThrows(NullPointerException.class, () -> repo.begin(RUN, KEY, "provider", ConnectorSyncDirection.INBOUND,
                null, "idem", CURSOR_SHA, Set.of(), false, 1, ACTOR, CORRELATION, NOW));
        assertThrows(NullPointerException.class, () -> repo.activate(RUN, null));
        assertThrows(NullPointerException.class, () -> repo.appendCheckpoint(null, RUN, 0,
                ConnectorSyncCheckpointKind.PROGRESS, null, CURSOR_SHA, 0, 0, 0, NOW));
        assertThrows(NullPointerException.class, () -> repo.appendCheckpoint(CHECKPOINT, RUN, 0,
                null, null, CURSOR_SHA, 0, 0, 0, NOW));
        assertThrows(NullPointerException.class, () -> repo.appendCheckpoint(CHECKPOINT, RUN, 0,
                ConnectorSyncCheckpointKind.PROGRESS, null, null, 0, 0, 0, NOW));
        assertThrows(NullPointerException.class, () -> repo.listCheckpoints(null, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> repo.listRuns(null, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> repo.listRuns(null, 1_000_001, 1));
        assertThrows(IllegalArgumentException.class, () -> repo.listRuns(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> repo.listRuns(null, 0, 202));
        JdbcConnectorSyncRepository failing = new JdbcConnectorSyncRepository(
                failingDataSource(new SQLException("offline")), JdbcDatabaseDialect.POSTGRESQL);
        assertEquals("offline", assertThrows(JdbcPersistenceException.class, () -> failing.findRun(RUN)).getCause().getMessage());
    }

    @Test
    void beginsNewAndDeduplicatedRunsAcrossDialects() {
        ScriptedConnection pg = connection(
                update(1), query(state(0, null, null)), query(List.of()), update(1), update(1), query(runRow(ConnectorSyncRunStatus.RUNNING, 0, 0, null, null)));
        ConnectorSyncRepository.BeginResult created = repository(JdbcDatabaseDialect.POSTGRESQL, pg).begin(
                RUN, KEY, "future-provider", ConnectorSyncDirection.INBOUND, ConnectorRollbackStrategy.LOCAL_CHECKPOINT,
                "sync-idem-001", CURSOR_SHA, Set.of("serial", "name"), false, 3, ACTOR, CORRELATION, NOW);
        assertTrue(created.created());
        assertEquals(0, created.revision());
        assertEquals(Set.of("name", "serial"), created.run().fields());
        assertTrue(pg.sql().get(0).contains("ON CONFLICT"));
        assertTrue(pg.exhausted());

        Map<String,Object> duplicateRun = runRow(ConnectorSyncRunStatus.PAUSED, 0, 0, "WAIT", null);
        ScriptedConnection duplicate = connection(update(0), query(state(0, null, null)), query(duplicateRun));
        ConnectorSyncRepository.BeginResult existing = repository(JdbcDatabaseDialect.POSTGRESQL, duplicate).begin(
                RUN, KEY, "future-provider", ConnectorSyncDirection.INBOUND, ConnectorRollbackStrategy.LOCAL_CHECKPOINT,
                "sync-idem-001", CURSOR_SHA, Set.of("name", "serial"), false, 3, ACTOR, CORRELATION, NOW);
        assertFalse(existing.created());
        assertEquals(ConnectorSyncRunStatus.PAUSED, existing.run().status());

        Map<String,Object> conflictingRun = runRow(ConnectorSyncRunStatus.PAUSED, 0, 0, "WAIT", null);
        conflictingRun.put("request_sha256", "b".repeat(64));
        ScriptedConnection conflicting = connection(update(0), query(state(0, null, null)), query(conflictingRun));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, conflicting).begin(
                RUN, KEY, "future-provider", ConnectorSyncDirection.INBOUND, ConnectorRollbackStrategy.LOCAL_CHECKPOINT,
                "sync-idem-001", CURSOR_SHA, Set.of(), false, 3, ACTOR, CORRELATION, NOW));

        ScriptedConnection active = connection(update(0), query(state(0, null, RUN)), query(List.of()));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, active).begin(
                id("0198b180-0000-7005-8000-000000000005"), KEY, "future-provider", ConnectorSyncDirection.OUTBOUND,
                ConnectorRollbackStrategy.REMOTE_COMPENSATION, "sync-idem-002", CURSOR_SHA, Set.of(), false, 1, ACTOR, CORRELATION, NOW));

        ScriptedConnection oracle = connection(
                updateFailure(new SQLException("duplicate", "23000", 1)), query(state(0, null, null)), query(List.of()),
                update(1), update(1), query(runRow(ConnectorSyncRunStatus.RUNNING, 0, 0, null, null)));
        assertTrue(repository(JdbcDatabaseDialect.ORACLE, oracle).begin(
                RUN, KEY, "future-provider", ConnectorSyncDirection.OUTBOUND, ConnectorRollbackStrategy.REMOTE_COMPENSATION,
                "sync-idem-003", CURSOR_SHA, Set.of(), true, 2, ACTOR, CORRELATION, NOW).created());
        assertTrue(oracle.sql().get(0).contains("INFRANEXUM_SYNC_STATE"));

        ScriptedConnection oracleFailure = connection(updateFailure(new SQLException("missing table", "42000", 942)));
        JdbcPersistenceException failure = assertThrows(JdbcPersistenceException.class, () -> repository(JdbcDatabaseDialect.ORACLE, oracleFailure).begin(
                RUN, KEY, "future-provider", ConnectorSyncDirection.OUTBOUND, ConnectorRollbackStrategy.REMOTE_COMPENSATION,
                "sync-idem-004", CURSOR_SHA, Set.of(), false, 1, ACTOR, CORRELATION, NOW));
        assertEquals(942, ((SQLException) failure.getCause()).getErrorCode());
    }

    @Test
    void activatesPausedRunsAndRejectsInvalidFences() {
        ScriptedConnection ok = connection(
                query(runRow(ConnectorSyncRunStatus.PAUSED, 0, 1, "WAIT", null)), query(state(1, "c1", null)),
                update(1), update(1), query(runRow(ConnectorSyncRunStatus.RUNNING, 0, 1, null, null)));
        ConnectorSyncRepository.Activation activation = repository(JdbcDatabaseDialect.POSTGRESQL, ok).activate(RUN, NOW);
        assertEquals("c1", activation.cursor());
        assertEquals(1, activation.revision());
        assertEquals(ConnectorSyncRunStatus.RUNNING, activation.run().status());

        ScriptedConnection terminal = connection(query(runRow(ConnectorSyncRunStatus.SUCCEEDED, 0, 1, null, NOW)));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, terminal).activate(RUN, NOW));
        ScriptedConnection running = connection(query(runRow(ConnectorSyncRunStatus.RUNNING, 0, 1, null, null)));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, running).activate(RUN, NOW));
        ScriptedConnection fenced = connection(query(runRow(ConnectorSyncRunStatus.PAUSED, 0, 1, "WAIT", null)), query(state(1, "c1", CHECKPOINT)));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, fenced).activate(RUN, NOW));
        ScriptedConnection advanced = connection(query(runRow(ConnectorSyncRunStatus.PAUSED, 0, 1, "WAIT", null)), query(state(2, "c2", null)));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, advanced).activate(RUN, NOW));
    }

    @Test
    void appendsMonotonicCheckpointsAndRejectsStaleOrUnownedProgress() {
        ScriptedConnection ok = connection(
                query(runRow(ConnectorSyncRunStatus.RUNNING, 0, 0, null, null)), query(state(0, null, RUN)),
                update(1), update(1), update(1), query(checkpointRow(1, ConnectorSyncCheckpointKind.PROGRESS, "cursor-1")));
        ConnectorSyncCheckpoint checkpoint = repository(JdbcDatabaseDialect.POSTGRESQL, ok).appendCheckpoint(
                CHECKPOINT, RUN, 0, ConnectorSyncCheckpointKind.PROGRESS, "cursor-1", CURSOR_SHA, 10, 8, 2, NOW);
        assertEquals(1, checkpoint.revision());
        assertEquals("cursor-1", checkpoint.cursor());

        ScriptedConnection wrongStatus = connection(query(runRow(ConnectorSyncRunStatus.PAUSED, 0, 0, "WAIT", null)));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, wrongStatus).appendCheckpoint(
                CHECKPOINT, RUN, 0, ConnectorSyncCheckpointKind.PROGRESS, null, EMPTY_SHA, 0, 0, 0, NOW));
        ScriptedConnection noFence = connection(query(runRow(ConnectorSyncRunStatus.RUNNING, 0, 0, null, null)), query(state(0, null, null)));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, noFence).appendCheckpoint(
                CHECKPOINT, RUN, 0, ConnectorSyncCheckpointKind.PROGRESS, null, EMPTY_SHA, 0, 0, 0, NOW));
        ScriptedConnection stale = connection(query(runRow(ConnectorSyncRunStatus.RUNNING, 0, 0, null, null)), query(state(1, "x", RUN)));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, stale).appendCheckpoint(
                CHECKPOINT, RUN, 0, ConnectorSyncCheckpointKind.PROGRESS, null, EMPTY_SHA, 0, 0, 0, NOW));
    }

    @Test
    void pausesSucceedsAndFailsOnlyOwnedRunningRuns() {
        for (ConnectorSyncRunStatus target : List.of(ConnectorSyncRunStatus.PAUSED, ConnectorSyncRunStatus.SUCCEEDED, ConnectorSyncRunStatus.FAILED)) {
            String code = target == ConnectorSyncRunStatus.PAUSED ? "WAIT" : target == ConnectorSyncRunStatus.FAILED ? "REMOTE_500" : null;
            Instant completed = target == ConnectorSyncRunStatus.PAUSED ? null : NOW;
            ScriptedConnection connection = connection(
                    query(runRow(ConnectorSyncRunStatus.RUNNING, 0, 1, null, null)), query(state(1, "cursor", RUN)),
                    update(1), update(1), query(runRow(target, 0, 1, code, completed)));
            JdbcConnectorSyncRepository repository = repository(JdbcDatabaseDialect.POSTGRESQL, connection);
            ConnectorSyncRun result = switch (target) {
                case PAUSED -> repository.pause(RUN, code, NOW);
                case SUCCEEDED -> repository.succeed(RUN, NOW);
                case FAILED -> repository.fail(RUN, code, NOW);
                default -> throw new AssertionError();
            };
            assertEquals(target, result.status());
            assertTrue(connection.exhausted());
        }
        ScriptedConnection notRunning = connection(query(runRow(ConnectorSyncRunStatus.PAUSED, 0, 1, "WAIT", null)));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, notRunning).succeed(RUN, NOW));
    }

    @Test
    void compensatesAppendOnlyAndFencesNewerState() {
        ScriptedConnection start = connection(
                query(runRow(ConnectorSyncRunStatus.SUCCEEDED, 0, 1, null, NOW)), query(state(1, "cursor-1", null)),
                update(1), update(1), query(runRow(ConnectorSyncRunStatus.COMPENSATING, 0, 1, null, null)));
        ConnectorSyncRepository.CompensationStart begun = repository(JdbcDatabaseDialect.POSTGRESQL, start).beginCompensation(RUN, NOW);
        assertNull(begun.initialCursor());
        assertEquals("cursor-1", begun.currentCursor());

        ScriptedConnection finish = connection(
                query(runRow(ConnectorSyncRunStatus.COMPENSATING, 0, 1, null, null)), query(state(1, "cursor-1", RUN)),
                update(1), update(1), update(1), update(1), query(runRow(ConnectorSyncRunStatus.COMPENSATED, 0, 2, null, NOW, 2L)));
        ConnectorSyncRun compensated = repository(JdbcDatabaseDialect.POSTGRESQL, finish).finishCompensation(
                RUN, 1, CHECKPOINT, null, EMPTY_SHA, NOW);
        assertEquals(ConnectorSyncRunStatus.COMPENSATED, compensated.status());
        assertEquals(2L, compensated.compensationCheckpointRevision().longValue());

        ScriptedConnection failed = connection(
                query(runRow(ConnectorSyncRunStatus.COMPENSATING, 0, 1, null, null)), query(state(1, "cursor-1", RUN)),
                update(1), update(1), query(runRow(ConnectorSyncRunStatus.COMPENSATION_FAILED, 0, 1, "ROLLBACK_500", NOW)));
        assertEquals(ConnectorSyncRunStatus.COMPENSATION_FAILED,
                repository(JdbcDatabaseDialect.POSTGRESQL, failed).compensationFailed(RUN, "ROLLBACK_500", NOW).status());

        for (ConnectorSyncRunStatus blocked : List.of(ConnectorSyncRunStatus.COMPENSATED, ConnectorSyncRunStatus.COMPENSATING, ConnectorSyncRunStatus.COMPENSATION_FAILED)) {
            ScriptedConnection c = connection(query(runRow(blocked, 0, 1, "X", NOW)));
            assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, c).beginCompensation(RUN, NOW));
        }
        ScriptedConnection ownerConflict = connection(query(runRow(ConnectorSyncRunStatus.SUCCEEDED, 0, 1, null, NOW)), query(state(1, "c", CHECKPOINT)));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, ownerConflict).beginCompensation(RUN, NOW));
        ScriptedConnection newerState = connection(query(runRow(ConnectorSyncRunStatus.SUCCEEDED, 0, 1, null, NOW)), query(state(2, "c2", null)));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, newerState).beginCompensation(RUN, NOW));
        ScriptedConnection wrongFinish = connection(query(runRow(ConnectorSyncRunStatus.SUCCEEDED, 0, 1, null, NOW)));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, wrongFinish).finishCompensation(RUN, 1, CHECKPOINT, null, EMPTY_SHA, NOW));
        ScriptedConnection wrongFail = connection(query(runRow(ConnectorSyncRunStatus.FAILED, 0, 1, "X", NOW)));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, wrongFail).compensationFailed(RUN, "X", NOW));
    }

    @Test
    void readsRunsAndCheckpointsWithDialectSpecificPaginationAndRepresentations() {
        ScriptedConnection find = connection(query(runRow(ConnectorSyncRunStatus.SUCCEEDED, 0, 1, null, NOW)));
        assertEquals(RUN, repository(JdbcDatabaseDialect.POSTGRESQL, find).findRun(RUN).orElseThrow().runId());
        ScriptedConnection missing = connection(query(List.of()));
        assertTrue(repository(JdbcDatabaseDialect.POSTGRESQL, missing).findRun(RUN).isEmpty());

        ScriptedConnection pgRuns = connection(query(List.of(runRow(ConnectorSyncRunStatus.SUCCEEDED, 0, 1, null, NOW))));
        assertEquals(1, repository(JdbcDatabaseDialect.POSTGRESQL, pgRuns).listRuns(KEY, 2, 3).size());
        assertTrue(pgRuns.sql().getFirst().contains("LIMIT ? OFFSET ?"));
        ScriptedConnection oracleRuns = connection(query(List.of(runRowOracle(ConnectorSyncRunStatus.SUCCEEDED, 0, 1, null, NOW))));
        assertEquals(1, repository(JdbcDatabaseDialect.ORACLE, oracleRuns).listRuns(null, 2, 3).size());
        assertTrue(oracleRuns.sql().getFirst().contains("OFFSET ? ROWS"));

        ScriptedConnection pgCheckpoints = connection(query(List.of(checkpointRow(1, ConnectorSyncCheckpointKind.PROGRESS, "c1"))));
        assertEquals(1, repository(JdbcDatabaseDialect.POSTGRESQL, pgCheckpoints).listCheckpoints(KEY, 0, 10).size());
        ScriptedConnection oracleCheckpoints = connection(query(List.of(checkpointRowOracle(2, ConnectorSyncCheckpointKind.COMPENSATION, null))));
        assertEquals(ConnectorSyncCheckpointKind.COMPENSATION,
                repository(JdbcDatabaseDialect.ORACLE, oracleCheckpoints).listCheckpoints(KEY, 1, 2).getFirst().kind());
    }

    @Test
    void coversInitialCursorLookupAndStateFailures() {
        Map<String,Object> runWithInitial = runRow(ConnectorSyncRunStatus.SUCCEEDED, 1, 2, null, NOW);
        ScriptedConnection initial = connection(
                query(runWithInitial), query(state(2, "current", null)), query(Map.of("cursor_value", "initial")),
                update(1), update(1), query(runRow(ConnectorSyncRunStatus.COMPENSATING, 1, 2, null, null)));
        assertEquals("initial", repository(JdbcDatabaseDialect.POSTGRESQL, initial).beginCompensation(RUN, NOW).initialCursor());

        ScriptedConnection missingCheckpoint = connection(query(runWithInitial), query(state(2, "current", null)), query(List.of()));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, missingCheckpoint).beginCompensation(RUN, NOW));
        ScriptedConnection missingState = connection(update(1), query(List.of()));
        assertThrows(ConnectorSyncNotFoundException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, missingState).begin(
                RUN, KEY, "provider", ConnectorSyncDirection.INBOUND, ConnectorRollbackStrategy.LOCAL_CHECKPOINT,
                "idem-missing-state", CURSOR_SHA, Set.of(), false, 1, ACTOR, CORRELATION, NOW));
        ScriptedConnection badMutation = connection(
                update(1), query(state(0, null, null)), query(List.of()), update(0));
        assertThrows(IllegalStateException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, badMutation).begin(
                RUN, KEY, "provider", ConnectorSyncDirection.INBOUND, ConnectorRollbackStrategy.LOCAL_CHECKPOINT,
                "idem-bad-count", CURSOR_SHA, Set.of(), false, 1, ACTOR, CORRELATION, NOW));
    }


    @Test
    void coversRemainingOracleAdmissionAndNullableRepresentations() {
        Map<String,Object> oracleCreatedRow = runRowOracle(ConnectorSyncRunStatus.RUNNING, 0, 0, null, null);
        oracleCreatedRow.put("requested_fields", null);
        oracleCreatedRow.put("propagate_deletions", 0);
        ScriptedConnection oracle = connection(
                update(1), query(state(0, null, null)), query(List.of()), update(1), update(1),
                query(oracleCreatedRow));
        ConnectorSyncRepository.BeginResult created = repository(JdbcDatabaseDialect.ORACLE, oracle).begin(
                RUN, KEY, "future-provider", ConnectorSyncDirection.OUTBOUND, ConnectorRollbackStrategy.REMOTE_COMPENSATION,
                "sync-idem-oracle-false", CURSOR_SHA, null, false, 1, ACTOR, CORRELATION, NOW);
        assertTrue(created.created());
        assertTrue(created.run().fields().isEmpty());
        assertEquals(0, oracle.parameters().get(3).get(9));
        assertTrue(oracle.exhausted());

        Map<String,Object> numericTrue = runRowOracle(ConnectorSyncRunStatus.SUCCEEDED, 0, 1, null, NOW);
        numericTrue.put("propagate_deletions", 1);
        numericTrue.put("requested_fields", null);
        assertTrue(repository(JdbcDatabaseDialect.ORACLE, connection(query(numericTrue))).findRun(RUN).orElseThrow().propagateDeletions());
        assertTrue(repository(JdbcDatabaseDialect.POSTGRESQL, connection(query(List.of()))).listRuns(null, 0, 1).isEmpty());
        assertTrue(repository(JdbcDatabaseDialect.POSTGRESQL, connection(query(List.of()))).listCheckpoints(KEY, 0, 1).isEmpty());

        Map<String,Object> emptyFields = runRow(ConnectorSyncRunStatus.SUCCEEDED, 0, 1, null, NOW);
        emptyFields.put("requested_fields", "");
        assertTrue(repository(JdbcDatabaseDialect.POSTGRESQL, connection(query(emptyFields))).findRun(RUN).orElseThrow().fields().isEmpty());
    }

    @Test
    void rejectsEveryRemainingFenceAndCheckpointRace() {
        ScriptedConnection wrongAppendFence = connection(
                query(runRow(ConnectorSyncRunStatus.RUNNING, 0, 0, null, null)), query(state(0, null, CHECKPOINT)));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, wrongAppendFence)
                .appendCheckpoint(CHECKPOINT, RUN, 0, ConnectorSyncCheckpointKind.PROGRESS, null, EMPTY_SHA, 0, 0, 0, NOW));

        ScriptedConnection missingInsertedCheckpoint = connection(
                query(runRow(ConnectorSyncRunStatus.RUNNING, 0, 0, null, null)), query(state(0, null, RUN)),
                update(1), update(1), update(1), query(List.of()));
        assertThrows(ConnectorSyncNotFoundException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, missingInsertedCheckpoint)
                .appendCheckpoint(CHECKPOINT, RUN, 0, ConnectorSyncCheckpointKind.PROGRESS, null, EMPTY_SHA, 1, 1, 0, NOW));

        ScriptedConnection staleCompensation = connection(
                query(runRow(ConnectorSyncRunStatus.COMPENSATING, 0, 1, null, null)), query(state(2, "newer", RUN)));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, staleCompensation)
                .finishCompensation(RUN, 1, CHECKPOINT, null, EMPTY_SHA, NOW));

        ScriptedConnection wrongCompensationFence = connection(
                query(runRow(ConnectorSyncRunStatus.COMPENSATING, 0, 1, null, null)), query(state(1, "cursor", CHECKPOINT)));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, wrongCompensationFence)
                .finishCompensation(RUN, 1, CHECKPOINT, null, EMPTY_SHA, NOW));

        ScriptedConnection wrongFailureFence = connection(
                query(runRow(ConnectorSyncRunStatus.COMPENSATING, 0, 1, null, null)), query(state(1, "cursor", CHECKPOINT)));
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository(JdbcDatabaseDialect.POSTGRESQL, wrongFailureFence)
                .compensationFailed(RUN, "ROLLBACK_500", NOW));
    }

    @Test
    void allowsSameRunToReacquireCompensationFenceAndRejectsWrongActiveFinishFence() {
        ScriptedConnection sameOwner = connection(
                query(runRow(ConnectorSyncRunStatus.SUCCEEDED, 0, 1, null, NOW)), query(state(1, "cursor", RUN)),
                update(1), update(1), query(runRow(ConnectorSyncRunStatus.COMPENSATING, 0, 1, null, null)));
        ConnectorSyncRepository.CompensationStart started = repository(JdbcDatabaseDialect.POSTGRESQL, sameOwner).beginCompensation(RUN, NOW);
        assertEquals(ConnectorSyncRunStatus.COMPENSATING, started.run().status());

        ScriptedConnection wrongFinishFence = connection(
                query(runRow(ConnectorSyncRunStatus.RUNNING, 0, 1, null, null)), query(state(1, "cursor", CHECKPOINT)));
        assertThrows(ConnectorSyncStateConflictException.class,
                () -> repository(JdbcDatabaseDialect.POSTGRESQL, wrongFinishFence).pause(RUN, "WAIT", NOW));
    }

    @Test
    void transactionBoundaryRestoresAutocommitAndPreservesRollbackFailure() {
        ScriptedConnection success = connection(query(runRow(ConnectorSyncRunStatus.SUCCEEDED, 0, 1, null, NOW))).autoCommit(true);
        assertTrue(repository(JdbcDatabaseDialect.POSTGRESQL, success).findRun(RUN).isPresent());
        assertTrue(success.autoCommit());

        SQLException queryFailure = new SQLException("query failed", "42000", 77);
        SQLException rollbackFailure = new SQLException("rollback failed", "08006", 88);
        ScriptedConnection failed = connection(queryFailure(queryFailure)).rollbackFails(rollbackFailure);
        JdbcPersistenceException wrapped = assertThrows(JdbcPersistenceException.class,
                () -> repository(JdbcDatabaseDialect.POSTGRESQL, failed).findRun(RUN));
        assertSame(queryFailure, wrapped.getCause());
        assertArrayEquals(new Throwable[] {rollbackFailure}, wrapped.getCause().getSuppressed());

        ScriptedConnection restoreFailure = connection(query(runRow(ConnectorSyncRunStatus.SUCCEEDED, 0, 1, null, NOW)))
                .autoCommit(true).restoreAutoCommitFails(new SQLException("restore failed"));
        assertTrue(repository(JdbcDatabaseDialect.POSTGRESQL, restoreFailure).findRun(RUN).isPresent());
        assertFalse(restoreFailure.autoCommit());
    }

    @Test
    void validatesRemainingRequiredBeginMetadata() {
        JdbcConnectorSyncRepository repo = repository(JdbcDatabaseDialect.POSTGRESQL, connection());
        assertThrows(NullPointerException.class, () -> repo.begin(RUN, KEY, "provider", ConnectorSyncDirection.INBOUND,
                ConnectorRollbackStrategy.LOCAL_CHECKPOINT, "idem", CURSOR_SHA, Set.of(), false, 1, null, CORRELATION, NOW));
        assertThrows(NullPointerException.class, () -> repo.begin(RUN, KEY, "provider", ConnectorSyncDirection.INBOUND,
                ConnectorRollbackStrategy.LOCAL_CHECKPOINT, "idem", CURSOR_SHA, Set.of(), false, 1, ACTOR, null, NOW));
        assertThrows(NullPointerException.class, () -> repo.begin(RUN, KEY, "provider", ConnectorSyncDirection.INBOUND,
                ConnectorRollbackStrategy.LOCAL_CHECKPOINT, "idem", CURSOR_SHA, Set.of(), false, 1, ACTOR, CORRELATION, null));
        assertThrows(NullPointerException.class, () -> repo.activate(RUN, null));
        assertThrows(NullPointerException.class, () -> repo.finishCompensation(RUN, 0, null, null, EMPTY_SHA, NOW));
        assertThrows(NullPointerException.class, () -> repo.finishCompensation(RUN, 0, CHECKPOINT, null, null, NOW));
        assertThrows(NullPointerException.class, () -> repo.finishCompensation(RUN, 0, CHECKPOINT, null, EMPTY_SHA, null));
        assertThrows(NullPointerException.class, () -> repo.compensationFailed(RUN, "FAIL", null));
    }

    private static JdbcConnectorSyncRepository repository(JdbcDatabaseDialect dialect, ScriptedConnection connection) {
        return new JdbcConnectorSyncRepository(dataSource(connection.connection()), dialect);
    }

    private static DomainIdentifier id(String value) { return new DomainIdentifier(UUID.fromString(value)); }

    private static Map<String,Object> state(long revision, String cursor, DomainIdentifier active) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("current_revision", revision);
        row.put("cursor_value", cursor);
        row.put("cursor_sha256", cursor == null ? EMPTY_SHA : CURSOR_SHA);
        row.put("active_run_id", active == null ? null : active.value());
        return row;
    }

    private static Map<String,Object> runRow(ConnectorSyncRunStatus status, long initialRevision, long lastRevision,
            String failureCode, Instant completedAt) {
        return runRow(status, initialRevision, lastRevision, failureCode, completedAt, null);
    }

    private static Map<String,Object> runRow(ConnectorSyncRunStatus status, long initialRevision, long lastRevision,
            String failureCode, Instant completedAt, Long compensationRevision) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("run_id", RUN.value());
        row.put("connector_key", KEY.value());
        row.put("provider", "future-provider");
        row.put("direction", ConnectorSyncDirection.INBOUND.name());
        row.put("rollback_strategy", ConnectorRollbackStrategy.LOCAL_CHECKPOINT.name());
        row.put("status", status.name());
        row.put("idempotency_key", "sync-idem-001");
        row.put("request_sha256", CURSOR_SHA);
        row.put("requested_fields", "name,serial");
        row.put("propagate_deletions", Boolean.FALSE);
        row.put("max_batches", 3);
        row.put("initial_revision", initialRevision);
        row.put("last_checkpoint_revision", lastRevision);
        row.put("failure_code", failureCode);
        row.put("actor_id", ACTOR.value());
        row.put("correlation_id", CORRELATION.value());
        row.put("started_at", NOW.minusSeconds(60));
        row.put("updated_at", NOW);
        row.put("completed_at", completedAt);
        row.put("compensation_checkpoint_revision", compensationRevision);
        return row;
    }

    private static Map<String,Object> runRowOracle(ConnectorSyncRunStatus status, long initialRevision, long lastRevision,
            String failureCode, Instant completedAt) {
        Map<String,Object> row = runRow(status, initialRevision, lastRevision, failureCode, completedAt);
        row.put("run_id", RUN.toString());
        row.put("actor_id", ACTOR.toString());
        row.put("correlation_id", CORRELATION.toString());
        row.put("propagate_deletions", 0);
        return row;
    }

    private static Map<String,Object> checkpointRow(long revision, ConnectorSyncCheckpointKind kind, String cursor) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("checkpoint_id", CHECKPOINT.value());
        row.put("connector_key", KEY.value());
        row.put("run_id", RUN.value());
        row.put("revision", revision);
        row.put("kind", kind.name());
        row.put("cursor_value", cursor);
        row.put("cursor_sha256", cursor == null ? EMPTY_SHA : CURSOR_SHA);
        row.put("processed_count", 10L);
        row.put("changed_count", 8L);
        row.put("rejected_count", 2L);
        row.put("created_at", NOW);
        return row;
    }

    private static Map<String,Object> checkpointRowOracle(long revision, ConnectorSyncCheckpointKind kind, String cursor) {
        Map<String,Object> row = checkpointRow(revision, kind, cursor);
        row.put("checkpoint_id", CHECKPOINT.toString());
        row.put("run_id", RUN.toString());
        return row;
    }
}
