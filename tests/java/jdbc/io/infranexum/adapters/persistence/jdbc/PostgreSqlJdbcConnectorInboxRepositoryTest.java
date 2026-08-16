package io.infranexum.adapters.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.ExponentialBackoffPolicy;
import io.infranexum.integrations.ConnectorDeliveryStateConflictException;
import io.infranexum.integrations.ConnectorDeliveryStatus;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.DuplicateDeliveryConflictException;
import io.infranexum.integrations.WebhookAdmission;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Live PostgreSQL contract tests for connector inbox deduplication, leases, DLQ and suspension. */
class PostgreSqlJdbcConnectorInboxRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");
    private static final ConnectorKey CONNECTOR = new ConnectorKey("jira-assets.test");

    private PGSimpleDataSource dataSource;
    private JdbcConnectorInboxRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        String url = System.getenv("INFRANEXUM_POSTGRESQL_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "PostgreSQL integration URL is not configured");
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(requiredEnvironment("INFRANEXUM_POSTGRESQL_TEST_USERNAME"));
        dataSource.setPassword(requiredEnvironment("INFRANEXUM_POSTGRESQL_TEST_PASSWORD"));
        repository = new JdbcConnectorInboxRepository(dataSource, JdbcDatabaseDialect.POSTGRESQL);
        truncate();
    }

    @AfterEach
    void cleanUp() throws SQLException {
        if (dataSource != null) truncate();
    }

    @Test
    void admissionIsDurablyIdempotentAndDetectsDeliveryIdentifierReuse() {
        WebhookAdmission first = admission(1, "provider-001", "  {\"asset\":1}  ", NOW);
        var created = repository.admit(first);
        var duplicate = repository.admit(admission(2, "provider-001", first.payload(), NOW.plusMillis(1)));

        assertFalse(created.duplicate());
        assertTrue(duplicate.duplicate());
        assertEquals(first.deliveryId(), duplicate.delivery().deliveryId());
        assertEquals(first.payload(), duplicate.delivery().payload(), "authenticated payload bytes must remain semantically unchanged");
        assertEquals(1, repository.backlogSize(CONNECTOR, NOW));

        WebhookAdmission drift = admission(3, "provider-001", "{\"asset\":2}", NOW.plusMillis(2));
        assertThrows(DuplicateDeliveryConflictException.class, () -> repository.admit(drift));
    }

    @Test
    void retriesBecomeDeadLettersAndSuspendClaimsUntilExplicitResume() {
        repository.admit(admission(10, "provider-010", "{\"asset\":10}", NOW));
        var claimed = repository.claimBatch("worker-a", 10, NOW, Duration.ofSeconds(30)).getFirst();
        assertEquals(ConnectorDeliveryStatus.IN_FLIGHT, claimed.status());
        assertEquals(1, claimed.attempts());

        var terminal = repository.markFailed(
                claimed.deliveryId(), "worker-a", NOW.plusSeconds(1), retryPolicy(1),
                new SQLException("sensitive provider detail"), 1, Duration.ofMinutes(15));
        assertEquals(ConnectorDeliveryStatus.DEAD_LETTER, terminal);
        assertEquals(1, repository.deadLetterCount(CONNECTOR));
        assertTrue(repository.runtimeState(CONNECTOR).suspendedAt(NOW.plusSeconds(2)));
        var dead = repository.listDeadLetters(CONNECTOR, 0, 10).getFirst();
        assertEquals(SQLException.class.getName(), dead.lastFailure(), "only the failure class may be persisted");

        repository.admit(admission(11, "provider-011", "{\"asset\":11}", NOW.plusSeconds(2)));
        assertTrue(repository.claimBatch("worker-b", 10, NOW.plusSeconds(3), Duration.ofSeconds(30)).isEmpty(),
                "a suspended connector must not execute newly admitted work");

        var replay = repository.replay(claimed.deliveryId(), NOW.plusSeconds(4));
        assertEquals(ConnectorDeliveryStatus.PENDING, replay.status());
        assertEquals(0, replay.attempts());
        assertEquals(1, replay.replayCount());
        assertTrue(repository.claimBatch("worker-b", 10, NOW.plusSeconds(5), Duration.ofSeconds(30)).isEmpty(),
                "replay must not implicitly resume a suspended connector");

        repository.resume(CONNECTOR, NOW.plusSeconds(6));
        var resumed = repository.claimBatch("worker-b", 10, NOW.plusSeconds(7), Duration.ofSeconds(30));
        assertEquals(2, resumed.size());
        assertFalse(repository.runtimeState(CONNECTOR).suspendedAt(NOW.plusSeconds(7)));
    }

    @Test
    void concurrentDeadLettersIncrementRuntimeStateWithoutLosingSuspensionThreshold() throws Exception {
        repository.admit(admission(30, "provider-030", "{\"asset\":30}", NOW));
        repository.admit(admission(31, "provider-031", "{\"asset\":31}", NOW));
        var claimed = repository.claimBatch("worker-race", 2, NOW, Duration.ofSeconds(30));
        assertEquals(2, claimed.size());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return repository.markFailed(
                        claimed.get(0).deliveryId(), "worker-race", NOW.plusSeconds(1), retryPolicy(1),
                        new SQLException("first failure"), 2, Duration.ofMinutes(15));
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return repository.markFailed(
                        claimed.get(1).deliveryId(), "worker-race", NOW.plusSeconds(1), retryPolicy(1),
                        new SQLException("second failure"), 2, Duration.ofMinutes(15));
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(ConnectorDeliveryStatus.DEAD_LETTER, first.get(10, TimeUnit.SECONDS));
            assertEquals(ConnectorDeliveryStatus.DEAD_LETTER, second.get(10, TimeUnit.SECONDS));
        }

        var state = repository.runtimeState(CONNECTOR);
        assertEquals(2, state.consecutiveDeadLetters());
        assertTrue(state.suspendedAt(NOW.plusSeconds(2)));
    }

    @Test
    void completedDeliveryResetsRuntimeAndCannotBeReplayed() {
        repository.admit(admission(20, "provider-020", "{\"asset\":20}", NOW));
        var claimed = repository.claimBatch("worker", 1, NOW, Duration.ofSeconds(10)).getFirst();
        repository.markProcessed(claimed.deliveryId(), "worker", NOW.plusSeconds(1));

        assertEquals(0, repository.backlogSize(CONNECTOR, NOW.plusSeconds(2)));
        assertEquals(0, repository.deadLetterCount(CONNECTOR));
        assertEquals(NOW.plusSeconds(1), repository.runtimeState(CONNECTOR).lastSuccessAt());
        assertThrows(ConnectorDeliveryStateConflictException.class,
                () -> repository.replay(claimed.deliveryId(), NOW.plusSeconds(2)));
    }

    private void truncate() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "TRUNCATE TABLE infranexum_integrations.connector_inbox, infranexum_integrations.connector_runtime_state")) {
            statement.executeUpdate();
        }
    }

    private static WebhookAdmission admission(int sequence, String externalId, String payload, Instant receivedAt) {
        return new WebhookAdmission(id(sequence), CONNECTOR, externalId, payload, sha256(payload), receivedAt);
    }

    private static DomainIdentifier id(int sequence) {
        return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(sequence)));
    }

    private static String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static ExponentialBackoffPolicy retryPolicy(int maximumAttempts) {
        return new ExponentialBackoffPolicy(
                maximumAttempts, Duration.ofSeconds(1), Duration.ofSeconds(4), 0.0, () -> 0.0);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
