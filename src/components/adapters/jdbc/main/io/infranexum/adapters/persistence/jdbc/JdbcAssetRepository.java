package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.asset.application.AssetPage;
import io.infranexum.itam.asset.application.AssetSearchCriteria;
import io.infranexum.itam.asset.domain.Asset;
import io.infranexum.itam.asset.domain.AssetConflictException;
import io.infranexum.itam.asset.domain.AssetCustodian;
import io.infranexum.itam.asset.domain.AssetCustodianKind;
import io.infranexum.itam.asset.domain.AssetCustodyEvent;
import io.infranexum.itam.asset.domain.AssetCustodyEventType;
import io.infranexum.itam.asset.domain.AssetLifecycleStatus;
import io.infranexum.itam.asset.domain.AssetType;
import io.infranexum.itam.asset.domain.AssetValue;
import io.infranexum.itam.asset.ports.AssetRepository;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** PostgreSQL/Oracle ITAM asset repository with append-only custody history. */
public final class JdbcAssetRepository implements AssetRepository {
    private static final String COLUMNS = "id,rsot_object_id,asset_type,owning_organization_id,owning_subdivision_id,"
            + "acquisition_date,acquisition_value,currency_code,acquired_from_partner_id,lifecycle_status,"
            + "current_custodian_kind,current_custodian_id,version,created_at,updated_at,created_by,updated_by,last_reason";

    private final DataSource dataSource;
    private final JdbcConnectionAccess transaction;
    private final JdbcDatabaseDialect dialect;

    public JdbcAssetRepository(DataSource dataSource, JdbcConnectionAccess transaction, JdbcDatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public long count() {
        try (PreparedStatement statement = transaction.requireCurrentConnection()
                        .prepareStatement("SELECT COUNT(*) FROM " + assetTable());
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) throw new SQLException("asset count returned no row");
            return resultSet.getLong(1);
        } catch (SQLException failure) {
            throw fail("count ITAM assets", failure);
        }
    }

    @Override
    public boolean existsByRsotObjectId(DomainIdentifier rsotObjectId) {
        Objects.requireNonNull(rsotObjectId, "rsotObjectId");
        try (PreparedStatement statement = transaction.requireCurrentConnection()
                .prepareStatement("SELECT 1 FROM " + assetTable() + " WHERE rsot_object_id=?")) {
            dialect.bindIdentifier(statement, 1, rsotObjectId);
            try (ResultSet resultSet = statement.executeQuery()) { return resultSet.next(); }
        } catch (SQLException failure) {
            throw fail("check ITAM asset canonical object", failure);
        }
    }

    @Override
    public Optional<Asset> findById(DomainIdentifier id) {
        Objects.requireNonNull(id, "id");
        Connection current = currentConnectionOrNull();
        if (current != null) return findById(current, id);
        try (Connection connection = dataSource.getConnection()) {
            return findById(connection, id);
        } catch (SQLException failure) {
            throw fail("find ITAM asset", failure);
        }
    }

    @Override
    public void insert(Asset asset, AssetCustodyEvent acquisitionEvent) {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(acquisitionEvent, "acquisitionEvent");
        String sql = "INSERT INTO " + assetTable() + " (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            bindAsset(statement, asset);
            if (statement.executeUpdate() != 1) throw new SQLException("asset insert affected unexpected rows");
            insertCustody(transaction.requireCurrentConnection(), acquisitionEvent);
        } catch (SQLException failure) {
            if (dialect.isUniqueViolation(failure)) {
                throw new AssetConflictException("ITAM_ASSET_RSOT_CONFLICT", "ITAM asset or canonical RSOT link was committed concurrently");
            }
            throw fail("insert ITAM asset", failure);
        }
    }

    @Override
    public void update(Asset asset, long expectedVersion, AssetCustodyEvent custodyEvent) {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(custodyEvent, "custodyEvent");
        String sql = "UPDATE " + assetTable() + " SET lifecycle_status=?,current_custodian_kind=?,current_custodian_id=?,"
                + "version=?,updated_at=?,updated_by=?,last_reason=? WHERE id=? AND version=?";
        try {
            Connection connection = transaction.requireCurrentConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, asset.lifecycleStatus().name());
                statement.setString(2, asset.custodian().kind().name());
                dialect.bindNullableIdentifier(statement, 3, asset.custodian().referenceId());
                statement.setLong(4, asset.version());
                JdbcTemporal.bindInstant(statement, 5, asset.updatedAt());
                dialect.bindIdentifier(statement, 6, asset.updatedBy());
                statement.setString(7, asset.lastReason());
                dialect.bindIdentifier(statement, 8, asset.id());
                statement.setLong(9, expectedVersion);
                if (statement.executeUpdate() != 1) {
                    throw new AssetConflictException("VERSION_CONFLICT", "asset version changed");
                }
            }
            insertCustody(connection, custodyEvent);
        } catch (SQLException failure) {
            throw fail("update ITAM asset lifecycle", failure);
        }
    }

    @Override
    public AssetPage search(AssetSearchCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria");
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM ").append(assetTable()).append(" a WHERE 1=1");
        List<Binder> binders = new ArrayList<>();
        if (criteria.owningOrganizationId() != null) {
            sql.append(" AND a.owning_organization_id=?");
            binders.add((statement, index) -> dialect.bindIdentifier(statement, index, criteria.owningOrganizationId()));
        }
        if (criteria.assetType() != null) {
            sql.append(" AND a.asset_type=?");
            binders.add((statement, index) -> statement.setString(index, criteria.assetType().name()));
        }
        if (criteria.lifecycleStatus() != null) {
            sql.append(" AND a.lifecycle_status=?");
            binders.add((statement, index) -> statement.setString(index, criteria.lifecycleStatus().name()));
        }
        if (criteria.rsotObjectId() != null) {
            sql.append(" AND a.rsot_object_id=?");
            binders.add((statement, index) -> dialect.bindIdentifier(statement, index, criteria.rsotObjectId()));
        }
        if (criteria.afterId() != null) {
            sql.append(" AND a.id>?");
            binders.add((statement, index) -> dialect.bindIdentifier(statement, index, criteria.afterId()));
        }
        sql.append(" ORDER BY a.id");
        sql.append(dialect == JdbcDatabaseDialect.POSTGRESQL ? " LIMIT ?" : " FETCH NEXT ? ROWS ONLY");
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (Binder binder : binders) binder.bind(statement, index++);
            statement.setInt(index, criteria.limit() + 1);
            List<Asset> items = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) items.add(readAsset(resultSet));
            }
            boolean hasMore = items.size() > criteria.limit();
            if (hasMore) items = new ArrayList<>(items.subList(0, criteria.limit()));
            DomainIdentifier next = hasMore && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
            return new AssetPage(items, next);
        } catch (SQLException failure) {
            throw fail("search ITAM assets", failure);
        }
    }

    @Override
    public List<AssetCustodyEvent> custodyHistory(DomainIdentifier assetId, long afterSequence, int limit) {
        Objects.requireNonNull(assetId, "assetId");
        String sql = "SELECT event_id,asset_id,sequence_no,event_type,from_status,to_status,custodian_kind,custodian_id,"
                + "occurred_at,actor_id,correlation_id,reason,evidence_reference FROM " + custodyTable()
                + " WHERE asset_id=? AND sequence_no>? ORDER BY sequence_no"
                + (dialect == JdbcDatabaseDialect.POSTGRESQL ? " LIMIT ?" : " FETCH NEXT ? ROWS ONLY");
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, assetId);
            statement.setLong(2, afterSequence);
            statement.setInt(3, limit);
            List<AssetCustodyEvent> result = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) result.add(readCustody(resultSet));
            }
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw fail("read ITAM asset custody history", failure);
        }
    }

    private Optional<Asset> findById(Connection connection, DomainIdentifier id) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM " + assetTable() + " WHERE id=?")) {
            dialect.bindIdentifier(statement, 1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readAsset(resultSet)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw fail("find ITAM asset", failure);
        }
    }

    private Asset readAsset(ResultSet resultSet) throws SQLException {
        return Asset.restore(
                dialect.readIdentifier(resultSet, "id"), dialect.readIdentifier(resultSet, "rsot_object_id"),
                AssetType.valueOf(resultSet.getString("asset_type")), dialect.readIdentifier(resultSet, "owning_organization_id"),
                nullableIdentifier(resultSet, "owning_subdivision_id"), resultSet.getDate("acquisition_date").toLocalDate(),
                new AssetValue(resultSet.getBigDecimal("acquisition_value"), resultSet.getString("currency_code")),
                nullableIdentifier(resultSet, "acquired_from_partner_id"), AssetLifecycleStatus.valueOf(resultSet.getString("lifecycle_status")),
                new AssetCustodian(AssetCustodianKind.valueOf(resultSet.getString("current_custodian_kind")),
                        nullableIdentifier(resultSet, "current_custodian_id")),
                resultSet.getLong("version"), JdbcTemporal.readRequired(resultSet, "created_at"),
                JdbcTemporal.readRequired(resultSet, "updated_at"), dialect.readIdentifier(resultSet, "created_by"),
                dialect.readIdentifier(resultSet, "updated_by"), resultSet.getString("last_reason"));
    }

    private void bindAsset(PreparedStatement statement, Asset asset) throws SQLException {
        int index = 1;
        dialect.bindIdentifier(statement, index++, asset.id());
        dialect.bindIdentifier(statement, index++, asset.rsotObjectId());
        statement.setString(index++, asset.assetType().name());
        dialect.bindIdentifier(statement, index++, asset.owningOrganizationId());
        dialect.bindNullableIdentifier(statement, index++, asset.owningSubdivisionId());
        statement.setDate(index++, Date.valueOf(asset.acquisitionDate()));
        statement.setBigDecimal(index++, asset.acquisitionValue().amount());
        statement.setString(index++, asset.acquisitionValue().currencyCode());
        dialect.bindNullableIdentifier(statement, index++, asset.acquiredFromPartnerId());
        statement.setString(index++, asset.lifecycleStatus().name());
        statement.setString(index++, asset.custodian().kind().name());
        dialect.bindNullableIdentifier(statement, index++, asset.custodian().referenceId());
        statement.setLong(index++, asset.version());
        JdbcTemporal.bindInstant(statement, index++, asset.createdAt());
        JdbcTemporal.bindInstant(statement, index++, asset.updatedAt());
        dialect.bindIdentifier(statement, index++, asset.createdBy());
        dialect.bindIdentifier(statement, index++, asset.updatedBy());
        statement.setString(index, asset.lastReason());
    }

    private void insertCustody(Connection connection, AssetCustodyEvent event) throws SQLException {
        String sql = "INSERT INTO " + custodyTable()
                + " (event_id,asset_id,sequence_no,event_type,from_status,to_status,custodian_kind,custodian_id,"
                + "occurred_at,actor_id,correlation_id,reason,evidence_reference) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            dialect.bindIdentifier(statement, index++, event.eventId());
            dialect.bindIdentifier(statement, index++, event.assetId());
            statement.setLong(index++, event.sequence());
            statement.setString(index++, event.eventType().name());
            if (event.fromStatus() == null) statement.setNull(index++, Types.VARCHAR);
            else statement.setString(index++, event.fromStatus().name());
            statement.setString(index++, event.toStatus().name());
            statement.setString(index++, event.custodian().kind().name());
            dialect.bindNullableIdentifier(statement, index++, event.custodian().referenceId());
            JdbcTemporal.bindInstant(statement, index++, event.occurredAt());
            dialect.bindIdentifier(statement, index++, event.actorId());
            dialect.bindIdentifier(statement, index++, event.correlationId());
            statement.setString(index++, event.reason());
            statement.setString(index, event.evidenceReference());
            if (statement.executeUpdate() != 1) throw new SQLException("asset custody insert affected unexpected rows");
        }
    }

    private AssetCustodyEvent readCustody(ResultSet resultSet) throws SQLException {
        String from = resultSet.getString("from_status");
        return new AssetCustodyEvent(
                dialect.readIdentifier(resultSet, "event_id"), dialect.readIdentifier(resultSet, "asset_id"),
                resultSet.getLong("sequence_no"), AssetCustodyEventType.valueOf(resultSet.getString("event_type")),
                from == null ? null : AssetLifecycleStatus.valueOf(from), AssetLifecycleStatus.valueOf(resultSet.getString("to_status")),
                new AssetCustodian(AssetCustodianKind.valueOf(resultSet.getString("custodian_kind")),
                        nullableIdentifier(resultSet, "custodian_id")),
                JdbcTemporal.readRequired(resultSet, "occurred_at"), dialect.readIdentifier(resultSet, "actor_id"),
                dialect.readIdentifier(resultSet, "correlation_id"), resultSet.getString("reason"),
                resultSet.getString("evidence_reference"));
    }

    private Connection currentConnectionOrNull() {
        try { return transaction.requireCurrentConnection(); }
        catch (IllegalStateException noTransaction) { return null; }
    }

    private DomainIdentifier nullableIdentifier(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column) == null ? null : dialect.readIdentifier(resultSet, column);
    }

    private String assetTable() {
        return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_itam.asset" : "INFRANEXUM_ITAM_ASSET";
    }

    private String custodyTable() {
        return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_itam.asset_custody_event" : "INFRANEXUM_ITAM_ASSET_CUSTODY";
    }

    private static JdbcPersistenceException fail(String operation, SQLException failure) {
        return new JdbcPersistenceException(operation, failure);
    }

    @FunctionalInterface
    private interface Binder { void bind(PreparedStatement statement, int index) throws SQLException; }
}
