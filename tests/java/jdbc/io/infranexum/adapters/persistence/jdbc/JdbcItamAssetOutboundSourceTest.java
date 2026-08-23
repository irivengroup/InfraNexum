package io.infranexum.adapters.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorOutboundPage;
import io.infranexum.integrations.ConnectorSyncBatchContext;
import io.infranexum.integrations.ConnectorSyncDirection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Regression coverage for keyset-based ITAM outbound pagination used by governed provider sync. */
class JdbcItamAssetOutboundSourceTest {
    private static final DomainIdentifier RUN = id("018f0d34-2c00-7000-8000-000000000001");
    private static final DomainIdentifier ASSET_1 = id("018f0d34-2c00-7000-8000-000000000002");
    private static final DomainIdentifier ASSET_2 = id("018f0d34-2c00-7000-8000-000000000003");
    private static final Instant T1 = Instant.parse("2026-08-22T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-22T10:00:01Z");

    @Test
    void postgresqlUsesStableKeysetCursorAndReturnsOnlyGovernedFields() {
        var connection = JdbcScriptedSupport.connection(JdbcScriptedSupport.query(List.of(
                row(ASSET_1, T1, "HARDWARE"), row(ASSET_2, T2, "SOFTWARE"))));
        var source = new JdbcItamAssetOutboundSource(
                JdbcScriptedSupport.dataSource(connection.connection()), JdbcDatabaseDialect.POSTGRESQL, 1);

        ConnectorOutboundPage page = source.read(context(null, Set.of("id", "asset_type")));

        assertEquals(1, page.records().size());
        assertFalse(page.completed());
        assertEquals(ASSET_1.toString(), page.records().getFirst().sourceIdentity());
        assertEquals(Map.of("id", ASSET_1.toString(), "asset_type", "HARDWARE"),
                page.records().getFirst().fields());
        assertFalse(page.records().getFirst().deleted());
        assertEquals(T1 + "|" + ASSET_1, page.nextCursor());
        assertTrue(connection.sql().getFirst().startsWith(
                "SELECT asset_type,id,lifecycle_status,updated_at FROM infranexum_itam.asset"));
        assertTrue(connection.sql().getFirst().contains("ORDER BY updated_at,id LIMIT ?"));
        assertEquals(Map.of(1, 2), connection.parameters().getFirst());
    }

    @Test
    void cursorBindsTimestampTwiceAndUuidBeforeLimitForPostgresql() {
        String cursor = T1 + "|" + ASSET_1;
        var connection = JdbcScriptedSupport.connection(JdbcScriptedSupport.query(row(ASSET_2, T2, "SOFTWARE")));
        var source = new JdbcItamAssetOutboundSource(
                JdbcScriptedSupport.dataSource(connection.connection()), JdbcDatabaseDialect.POSTGRESQL, 20);

        ConnectorOutboundPage page = source.read(context(cursor, Set.of("id", "asset_type")));

        assertTrue(page.completed());
        assertEquals(T2 + "|" + ASSET_2, page.nextCursor());
        assertTrue(connection.sql().getFirst().contains(
                "WHERE (updated_at>? OR (updated_at=? AND id>?)) ORDER BY updated_at,id LIMIT ?"));
        Map<Integer, Object> parameters = connection.parameters().getFirst();
        assertEquals(OffsetDateTime.ofInstant(T1, ZoneOffset.UTC), parameters.get(1));
        assertEquals(OffsetDateTime.ofInstant(T1, ZoneOffset.UTC), parameters.get(2));
        assertEquals(ASSET_1.value(), parameters.get(3));
        assertEquals(21, parameters.get(4));
    }

    @Test
    void oracleUsesSameCursorSemanticsWithStringIdentifiersAndFetchNext() {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("id", ASSET_2.toString());
        row.put("asset_type", "SOFTWARE");
        row.put("lifecycle_status", "DEPLOYED");
        row.put("updated_at", T2);
        var connection = JdbcScriptedSupport.connection(JdbcScriptedSupport.query(row));
        var source = new JdbcItamAssetOutboundSource(
                JdbcScriptedSupport.dataSource(connection.connection()), JdbcDatabaseDialect.ORACLE, 5);

        ConnectorOutboundPage page = source.read(context(T1 + "|" + ASSET_1, Set.of("asset_type")));

        assertEquals(Map.of("asset_type", "SOFTWARE"), page.records().getFirst().fields());
        assertTrue(connection.sql().getFirst().contains(
                "FROM INFRANEXUM_ITAM_ASSET WHERE (updated_at>? OR (updated_at=? AND id>?))"));
        assertTrue(connection.sql().getFirst().endsWith("ORDER BY updated_at,id FETCH NEXT ? ROWS ONLY"));
        assertEquals(ASSET_1.toString(), connection.parameters().getFirst().get(3));
    }

    @Test
    void disposedAssetsAreEmittedAsTombstonesWithoutLeakingUngovernedLifecycleField() {
        var connection = JdbcScriptedSupport.connection(JdbcScriptedSupport.query(
                row(ASSET_1, T1, "HARDWARE", "DISPOSED")));
        var source = new JdbcItamAssetOutboundSource(
                JdbcScriptedSupport.dataSource(connection.connection()), JdbcDatabaseDialect.POSTGRESQL, 10);

        ConnectorOutboundPage page = source.read(context(null, Set.of("id", "asset_type")));

        assertTrue(page.records().getFirst().deleted());
        assertEquals(Map.of("id", ASSET_1.toString(), "asset_type", "HARDWARE"),
                page.records().getFirst().fields());
        assertFalse(page.records().getFirst().fields().containsKey("lifecycle_status"));
    }

    @Test
    void rejectsUnsupportedFieldsMalformedCursorAndPersistenceFailure() {
        var source = new JdbcItamAssetOutboundSource(
                JdbcScriptedSupport.failingDataSource(new SQLException("offline")), JdbcDatabaseDialect.POSTGRESQL, 10);
        assertThrows(IllegalArgumentException.class, () -> source.read(context(null, Set.of())));
        assertThrows(IllegalArgumentException.class, () -> source.read(context(null, Set.of("secret"))));
        assertThrows(IllegalArgumentException.class,
                () -> source.read(context("bad-cursor", Set.of("id"))));
        assertThrows(JdbcPersistenceException.class, () -> source.read(context(null, Set.of("id"))));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcItamAssetOutboundSource(
                        JdbcScriptedSupport.failingDataSource(new SQLException("unused")), JdbcDatabaseDialect.POSTGRESQL, 201));
    }

    private static ConnectorSyncBatchContext context(String cursor, Set<String> fields) {
        return new ConnectorSyncBatchContext(
                RUN, new ConnectorKey("jira-prod"), ConnectorSyncDirection.OUTBOUND,
                cursor, 0, 1, fields, false);
    }

    private static Map<String, Object> row(DomainIdentifier id, Instant updatedAt, String assetType) {
        return row(id, updatedAt, assetType, "DEPLOYED");
    }

    private static Map<String, Object> row(
            DomainIdentifier id, Instant updatedAt, String assetType, String lifecycleStatus) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("id", id.value());
        row.put("asset_type", assetType);
        row.put("lifecycle_status", lifecycleStatus);
        row.put("updated_at", updatedAt);
        return row;
    }

    private static DomainIdentifier id(String value) {
        return new DomainIdentifier(UUID.fromString(value));
    }
}
