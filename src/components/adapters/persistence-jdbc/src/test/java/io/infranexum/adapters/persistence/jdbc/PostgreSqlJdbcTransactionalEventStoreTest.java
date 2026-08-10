package io.infranexum.adapters.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.EventEnvelope;
import io.infranexum.core.events.EventSource;
import io.infranexum.core.events.EventType;
import io.infranexum.core.events.ExponentialBackoffPolicy;
import io.infranexum.core.events.InboxProcessingResult;
import io.infranexum.core.events.InboxProcessor;
import io.infranexum.core.events.OutboxRecord;
import io.infranexum.core.events.OutboxStatus;
import io.infranexum.core.events.TransactionExecutionException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Live PostgreSQL contract tests enabled by the database CI job. */
class PostgreSqlJdbcTransactionalEventStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-03T15:00:00Z");

    private PGSimpleDataSource dataSource;
    private JdbcTransactionalEventStore store;

    @BeforeEach
    void setUp() throws SQLException {
        String url = System.getenv("INFRANEXUM_POSTGRESQL_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "PostgreSQL integration URL is not configured");
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(requiredEnvironment("INFRANEXUM_POSTGRESQL_TEST_USERNAME"));
        dataSource.setPassword(requiredEnvironment("INFRANEXUM_POSTGRESQL_TEST_PASSWORD"));
        store = new JdbcTransactionalEventStore(dataSource, JdbcDatabaseDialect.POSTGRESQL);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        CREATE TABLE IF NOT EXISTS infranexum_core.jdbc_uow_probe (
                            probe_id UUID PRIMARY KEY,
                            value_text VARCHAR(128) NOT NULL
                        )
                        """)) {
            statement.executeUpdate();
        }
        truncate();
    }

    @AfterEach
    void cleanUp() throws SQLException {
        if (dataSource != null) truncate();
    }

    @Test
    void commitsBusinessWriteAndOutboxOnTheSameConnection() throws SQLException {
        EventEnvelope event = event(1);
        store.execute(transaction -> {
            insertProbe(store.requireCurrentConnection(), event.eventId(), "committed");
            transaction.append(event);
            return null;
        });
        assertEquals(1, count("infranexum_core.jdbc_uow_probe"));
        assertEquals(1, count("infranexum_core.outbox_event"));
    }

    @Test
    void rollsBackBusinessWriteAndOutboxTogether() throws SQLException {
        EventEnvelope event = event(2);
        assertThrows(TransactionExecutionException.class, () -> store.execute(transaction -> {
            insertProbe(store.requireCurrentConnection(), event.eventId(), "rolled-back");
            transaction.append(event);
            throw new IllegalStateException("business failure");
        }));
        assertEquals(0, count("infranexum_core.jdbc_uow_probe"));
        assertEquals(0, count("infranexum_core.outbox_event"));
    }

    @Test
    void deduplicatesCommittedInboxDeliveryAndRollsBackFailedHandler() throws SQLException {
        InboxProcessor processor = new InboxProcessor(store, Clock.fixed(NOW, ZoneOffset.UTC));
        EventEnvelope inbound = event(3);
        assertThrows(TransactionExecutionException.class, () -> processor.process(
                "core.postgresql-live", inbound, (event, transaction) -> {
                    insertProbe(store.requireCurrentConnection(), event.eventId(), "failed");
                    throw new IllegalStateException("projection failure");
                }));
        assertEquals(0, count("infranexum_core.inbox_receipt"));
        assertEquals(0, count("infranexum_core.jdbc_uow_probe"));

        assertEquals(InboxProcessingResult.PROCESSED, processor.process(
                "core.postgresql-live", inbound, (event, transaction) ->
                        insertProbe(store.requireCurrentConnection(), event.eventId(), "processed")).value());
        assertEquals(InboxProcessingResult.DUPLICATE, processor.process(
                "core.postgresql-live", inbound, (event, transaction) -> {
                    throw new AssertionError("duplicate handler executed");
                }).value());
        assertEquals(1, count("infranexum_core.inbox_receipt"));
        assertEquals(1, count("infranexum_core.jdbc_uow_probe"));
    }

    @Test
    void concurrentWorkersClaimEachEventAtMostOnce() throws Exception {
        store.execute(transaction -> {
            for (int index = 10; index < 50; index++) transaction.append(event(index));
            return null;
        });
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<List<OutboxRecord>>> tasks = List.of(
                    () -> store.claimBatch("worker-a", 10, NOW.plusSeconds(1), Duration.ofMinutes(1)),
                    () -> store.claimBatch("worker-b", 10, NOW.plusSeconds(1), Duration.ofMinutes(1)),
                    () -> store.claimBatch("worker-c", 10, NOW.plusSeconds(1), Duration.ofMinutes(1)),
                    () -> store.claimBatch("worker-d", 10, NOW.plusSeconds(1), Duration.ofMinutes(1)));
            Set<DomainIdentifier> claimed = new HashSet<>();
            for (var future : executor.invokeAll(tasks)) {
                for (OutboxRecord record : future.get()) {
                    assertTrue(claimed.add(record.event().eventId()), "event claimed by more than one worker");
                }
            }
            assertEquals(40, claimed.size());
        }
    }

    @Test
    void retriesThenPublishesWithLeaseOwnershipEnforced() {
        EventEnvelope event = event(60);
        store.execute(transaction -> { transaction.append(event); return null; });
        store.claimBatch("owner", 1, NOW.plusSeconds(1), Duration.ofSeconds(5));
        assertThrows(IllegalStateException.class,
                () -> store.markPublished(event.eventId(), "intruder", NOW.plusSeconds(2)));
        var retry = new ExponentialBackoffPolicy(
                2, Duration.ofSeconds(2), Duration.ofSeconds(2), 0.0, () -> 0.0);
        assertEquals(OutboxStatus.PENDING,
                store.markFailed(event.eventId(), "owner", NOW.plusSeconds(2), retry,
                        new SQLException("transport unavailable")));
        assertTrue(store.claimBatch("owner", 1, NOW.plusSeconds(3), Duration.ofSeconds(5)).isEmpty());
        store.claimBatch("owner", 1, NOW.plusSeconds(4), Duration.ofSeconds(5));
        store.markPublished(event.eventId(), "owner", NOW.plusSeconds(5));
        assertEquals("PUBLISHED", queryString(
                "SELECT status FROM infranexum_core.outbox_event WHERE event_id = ?", event.eventId()));
    }

    private void truncate() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        TRUNCATE TABLE infranexum_core.jdbc_uow_probe,
                            infranexum_core.inbox_receipt,
                            infranexum_core.outbox_event
                        """)) {
            statement.executeUpdate();
        }
    }

    private static void insertProbe(Connection connection, DomainIdentifier id, String value)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO infranexum_core.jdbc_uow_probe (probe_id, value_text) VALUES (?, ?)")) {
            statement.setObject(1, id.value());
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private int count(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private String queryString(String sql, DomainIdentifier id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) throw new AssertionError("query returned no row");
                return resultSet.getString(1);
            }
        } catch (SQLException failure) {
            throw new AssertionError("query failed", failure);
        }
    }

    private static EventEnvelope event(int sequence) {
        String suffix = "%012d".formatted(sequence);
        return new EventEnvelope(
                id("018bcfe5-6800-7000-8000-" + suffix),
                new EventType("core.asset.created.v1"),
                ContractVersion.parse("1.0.0"),
                NOW.plusMillis(sequence),
                new EventSource("core/server-live"),
                id("018bcfe5-6800-7002-8000-" + suffix),
                null,
                "{\"sequence\":" + sequence + "}");
    }

    private static DomainIdentifier id(String value) {
        return new DomainIdentifier(UUID.fromString(value));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
