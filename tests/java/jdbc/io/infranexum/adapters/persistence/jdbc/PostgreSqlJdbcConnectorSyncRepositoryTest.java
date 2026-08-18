package io.infranexum.adapters.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorRollbackStrategy;
import io.infranexum.integrations.ConnectorSyncCheckpointKind;
import io.infranexum.integrations.ConnectorSyncDirection;
import io.infranexum.integrations.ConnectorSyncRepository;
import io.infranexum.integrations.ConnectorSyncRunStatus;
import io.infranexum.integrations.ConnectorSyncStateConflictException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Live PostgreSQL contracts for durable connector sync checkpoints, resume, fencing and compensation. */
class PostgreSqlJdbcConnectorSyncRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-18T18:00:00Z");
    private static final ConnectorKey CONNECTOR = new ConnectorKey("sync.live-test");
    private static final ConnectorKey RACE_CONNECTOR = new ConnectorKey("sync.race-test");
    private static final String EMPTY_CURSOR_SHA256 = sha256("");

    private PGSimpleDataSource dataSource;
    private JdbcConnectorSyncRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        String url = System.getenv("INFRANEXUM_POSTGRESQL_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "PostgreSQL integration URL is not configured");
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(requiredEnvironment("INFRANEXUM_POSTGRESQL_TEST_USERNAME"));
        dataSource.setPassword(requiredEnvironment("INFRANEXUM_POSTGRESQL_TEST_PASSWORD"));
        repository = new JdbcConnectorSyncRepository(dataSource, JdbcDatabaseDialect.POSTGRESQL);
        truncate();
    }

    @AfterEach
    void cleanUp() throws SQLException {
        if (dataSource != null) truncate();
    }

    @Test
    void runLifecycleIsDurableIdempotentResumableAndCompensatedAppendOnly() {
        DomainIdentifier runId = id(1);
        DomainIdentifier duplicateRunId = id(2);
        DomainIdentifier progressOne = id(11);
        DomainIdentifier progressTwo = id(12);
        DomainIdentifier compensation = id(13);
        String requestHash = sha256("sync-request-1");

        ConnectorSyncRepository.BeginResult created = repository.begin(
                runId, CONNECTOR, "test-provider", ConnectorSyncDirection.OUTBOUND,
                ConnectorRollbackStrategy.REMOTE_COMPENSATION, "sync-live-idem-001", requestHash,
                Set.of("asset.name", "asset.status"), false, 4, id(90), id(91), NOW);
        assertTrue(created.created());
        assertNull(created.cursor());
        assertEquals(0, created.revision());
        assertEquals(ConnectorSyncRunStatus.RUNNING, created.run().status());

        ConnectorSyncRepository.BeginResult duplicate = repository.begin(
                duplicateRunId, CONNECTOR, "test-provider", ConnectorSyncDirection.OUTBOUND,
                ConnectorRollbackStrategy.REMOTE_COMPENSATION, "sync-live-idem-001", requestHash,
                Set.of("asset.status", "asset.name"), false, 4, id(90), id(91), NOW.plusMillis(1));
        assertFalse(duplicate.created());
        assertEquals(runId, duplicate.run().runId());
        assertThrows(ConnectorSyncStateConflictException.class, () -> repository.begin(
                id(3), CONNECTOR, "test-provider", ConnectorSyncDirection.OUTBOUND,
                ConnectorRollbackStrategy.REMOTE_COMPENSATION, "sync-live-idem-001", sha256("semantic-drift"),
                Set.of("asset.name", "asset.status"), false, 4, id(90), id(91), NOW.plusMillis(2)));

        var first = repository.appendCheckpoint(
                progressOne, runId, 0, ConnectorSyncCheckpointKind.PROGRESS,
                "cursor-1", sha256("cursor-1"), 10, 7, 1, NOW.plusSeconds(1));
        assertEquals(1, first.revision());
        assertEquals("cursor-1", first.cursor());

        assertEquals(ConnectorSyncRunStatus.PAUSED,
                repository.pause(runId, "PROVIDER_TIMEOUT", NOW.plusSeconds(2)).status());
        ConnectorSyncRepository.Activation resumed = repository.activate(runId, NOW.plusSeconds(3));
        assertEquals(1, resumed.revision());
        assertEquals("cursor-1", resumed.cursor());
        assertEquals(ConnectorSyncRunStatus.RUNNING, resumed.run().status());

        assertThrows(ConnectorSyncStateConflictException.class, () -> repository.appendCheckpoint(
                id(14), runId, 0, ConnectorSyncCheckpointKind.PROGRESS,
                "stale-cursor", sha256("stale-cursor"), 1, 1, 0, NOW.plusSeconds(4)));

        var second = repository.appendCheckpoint(
                progressTwo, runId, 1, ConnectorSyncCheckpointKind.PROGRESS,
                "cursor-2", sha256("cursor-2"), 20, 16, 2, NOW.plusSeconds(5));
        assertEquals(2, second.revision());
        assertEquals(ConnectorSyncRunStatus.SUCCEEDED,
                repository.succeed(runId, NOW.plusSeconds(6)).status());

        ConnectorSyncRepository.CompensationStart started = repository.beginCompensation(runId, NOW.plusSeconds(7));
        assertEquals(ConnectorSyncRunStatus.COMPENSATING, started.run().status());
        assertNull(started.initialCursor());
        assertEquals("cursor-2", started.currentCursor());
        assertEquals(2, started.currentRevision());

        var compensated = repository.finishCompensation(
                runId, 2, compensation, null, EMPTY_CURSOR_SHA256, NOW.plusSeconds(8));
        assertEquals(ConnectorSyncRunStatus.COMPENSATED, compensated.status());
        assertEquals(3, compensated.lastCheckpointRevision());
        assertEquals(3L, compensated.compensationCheckpointRevision());

        var checkpoints = repository.listCheckpoints(CONNECTOR, 0, 10);
        assertEquals(3, checkpoints.size());
        assertEquals(ConnectorSyncCheckpointKind.COMPENSATION, checkpoints.get(0).kind());
        assertEquals(3, checkpoints.get(0).revision());
        assertNull(checkpoints.get(0).cursor());
        assertEquals(ConnectorSyncCheckpointKind.PROGRESS, checkpoints.get(1).kind());
        assertEquals(2, checkpoints.get(1).revision());
        assertEquals(ConnectorSyncCheckpointKind.PROGRESS, checkpoints.get(2).kind());
        assertEquals(1, checkpoints.get(2).revision());
        assertEquals(runId, repository.findRun(runId).orElseThrow().runId());
        assertEquals(1, repository.listRuns(CONNECTOR, 0, 10).size());
    }

    @Test
    void concurrentAdmissionKeepsExactlyOneActiveRunFencePerConnector() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> beginRace(id(21), "sync-race-idem-001", ready, start));
            var second = executor.submit(() -> beginRace(id(22), "sync-race-idem-002", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int created = 0;
            int fenced = 0;
            for (var future : java.util.List.of(first, second)) {
                try {
                    assertTrue(future.get(10, TimeUnit.SECONDS).created());
                    created++;
                } catch (ExecutionException failure) {
                    assertTrue(failure.getCause() instanceof ConnectorSyncStateConflictException,
                            () -> "unexpected concurrent failure: " + failure.getCause());
                    fenced++;
                }
            }
            assertEquals(1, created, "one transaction must own the connector state fence");
            assertEquals(1, fenced, "the competing transaction must fail closed");
        }
    }

    private ConnectorSyncRepository.BeginResult beginRace(
            DomainIdentifier runId, String idempotencyKey, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return repository.begin(
                runId, RACE_CONNECTOR, "test-provider", ConnectorSyncDirection.OUTBOUND,
                ConnectorRollbackStrategy.REMOTE_COMPENSATION, idempotencyKey, sha256(idempotencyKey),
                Set.of("asset.name"), false, 2, id(92), id(93), NOW);
    }

    private void truncate() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "TRUNCATE TABLE infranexum_integrations.connector_sync_checkpoint, "
                                + "infranexum_integrations.connector_sync_run, "
                                + "infranexum_integrations.connector_sync_state")) {
            statement.executeUpdate();
        }
    }

    private static DomainIdentifier id(int sequence) {
        return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(sequence)));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
