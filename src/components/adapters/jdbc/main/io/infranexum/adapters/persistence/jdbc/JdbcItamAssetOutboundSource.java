package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.integrations.ConnectorOutboundPage;
import io.infranexum.integrations.ConnectorOutboundRecord;
import io.infranexum.integrations.ConnectorOutboundSource;
import io.infranexum.integrations.ConnectorSyncBatchContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Incremental ITAM asset source for outbound connector synchronization.
 *
 * <p>The keyset cursor is {@code updated_at + UUID}. It is stable across PostgreSQL and Oracle and
 * avoids OFFSET scans. Cursor values are internal-only and checkpointed by the synchronization
 * runtime; public APIs expose only their SHA-256 digest.</p>
 */
public final class JdbcItamAssetOutboundSource implements ConnectorOutboundSource {
    private static final Map<String, FieldKind> FIELDS = Map.ofEntries(
            Map.entry("id", FieldKind.IDENTIFIER),
            Map.entry("rsot_object_id", FieldKind.IDENTIFIER),
            Map.entry("asset_type", FieldKind.TEXT),
            Map.entry("owning_organization_id", FieldKind.IDENTIFIER),
            Map.entry("owning_subdivision_id", FieldKind.NULLABLE_IDENTIFIER),
            Map.entry("acquisition_date", FieldKind.DATE),
            Map.entry("acquisition_value", FieldKind.DECIMAL),
            Map.entry("currency_code", FieldKind.TEXT),
            Map.entry("acquired_from_partner_id", FieldKind.NULLABLE_IDENTIFIER),
            Map.entry("producer_partner_id", FieldKind.NULLABLE_IDENTIFIER),
            Map.entry("lifecycle_status", FieldKind.TEXT),
            Map.entry("current_custodian_kind", FieldKind.TEXT),
            Map.entry("current_custodian_id", FieldKind.NULLABLE_IDENTIFIER),
            Map.entry("version", FieldKind.LONG),
            Map.entry("updated_at", FieldKind.INSTANT));

    private final DataSource dataSource;
    private final JdbcDatabaseDialect dialect;
    private final int batchSize;

    public JdbcItamAssetOutboundSource(DataSource dataSource, JdbcDatabaseDialect dialect, int batchSize) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        if (batchSize < 1 || batchSize > 200) throw new IllegalArgumentException("batchSize must be between 1 and 200");
        this.batchSize = batchSize;
    }

    @Override
    public ConnectorOutboundPage read(ConnectorSyncBatchContext context) {
        Objects.requireNonNull(context, "context");
        List<String> fields = context.fields().stream().sorted().toList();
        if (fields.isEmpty()) throw new IllegalArgumentException("outbound ITAM synchronization requires governed fields");
        for (String field : fields) {
            if (!FIELDS.containsKey(field)) throw new IllegalArgumentException("unsupported ITAM outbound field: " + field);
        }
        Cursor cursor = Cursor.parse(context.cursor());
        String sql = sql(fields, cursor != null);
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (cursor != null) {
                JdbcTemporal.bindInstant(statement, index++, cursor.updatedAt());
                JdbcTemporal.bindInstant(statement, index++, cursor.updatedAt());
                dialect.bindIdentifier(statement, index++, cursor.id());
            }
            statement.setInt(index, batchSize + 1);
            List<Row> rows = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) rows.add(readRow(resultSet, fields));
            }
            boolean hasMore = rows.size() > batchSize;
            if (hasMore) rows = new ArrayList<>(rows.subList(0, batchSize));
            List<ConnectorOutboundRecord> records = rows.stream().map(Row::record).toList();
            String nextCursor = rows.isEmpty() ? context.cursor() : rows.getLast().cursor().encode();
            return new ConnectorOutboundPage(records, nextCursor, !hasMore);
        } catch (SQLException failure) {
            throw new JdbcPersistenceException("read ITAM outbound synchronization page", failure);
        }
    }

    private String sql(List<String> fields, boolean afterCursor) {
        List<String> projection = new ArrayList<>(fields);
        if (!projection.contains("id")) projection.add("id");
        if (!projection.contains("lifecycle_status")) projection.add("lifecycle_status");
        if (!projection.contains("updated_at")) projection.add("updated_at");
        projection.sort(Comparator.naturalOrder());
        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(",", projection))
                .append(" FROM ").append(assetTable());
        if (afterCursor) sql.append(" WHERE (updated_at>? OR (updated_at=? AND id>?))");
        sql.append(" ORDER BY updated_at,id");
        sql.append(dialect == JdbcDatabaseDialect.POSTGRESQL ? " LIMIT ?" : " FETCH NEXT ? ROWS ONLY");
        return sql.toString();
    }

    private Row readRow(ResultSet resultSet, List<String> fields) throws SQLException {
        DomainIdentifier id = dialect.readIdentifier(resultSet, "id");
        Instant updatedAt = JdbcTemporal.readRequired(resultSet, "updated_at");
        boolean deleted = "DISPOSED".equals(resultSet.getString("lifecycle_status"));
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : fields) {
            String value = value(resultSet, field, FIELDS.get(field));
            if (value != null) values.put(field, value);
        }
        return new Row(new ConnectorOutboundRecord(id.toString(), values, deleted), new Cursor(updatedAt, id));
    }

    private String value(ResultSet resultSet, String field, FieldKind kind) throws SQLException {
        return switch (kind) {
            case IDENTIFIER -> dialect.readIdentifier(resultSet, field).toString();
            case NULLABLE_IDENTIFIER -> resultSet.getObject(field) == null ? null : dialect.readIdentifier(resultSet, field).toString();
            case DATE -> resultSet.getDate(field) == null ? null : resultSet.getDate(field).toLocalDate().toString();
            case DECIMAL -> {
                BigDecimal decimal = resultSet.getBigDecimal(field);
                yield decimal == null ? null : decimal.toPlainString();
            }
            case LONG -> Long.toString(resultSet.getLong(field));
            case INSTANT -> JdbcTemporal.readRequired(resultSet, field).toString();
            case TEXT -> resultSet.getString(field);
        };
    }

    private String assetTable() {
        return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_itam.asset" : "INFRANEXUM_ITAM_ASSET";
    }

    private enum FieldKind { IDENTIFIER, NULLABLE_IDENTIFIER, DATE, DECIMAL, LONG, INSTANT, TEXT }
    private record Row(ConnectorOutboundRecord record, Cursor cursor) {}

    private record Cursor(Instant updatedAt, DomainIdentifier id) {
        private String encode() { return updatedAt + "|" + id; }

        private static Cursor parse(String value) {
            if (value == null) return null;
            int separator = value.indexOf('|');
            if (separator < 1 || separator != value.lastIndexOf('|') || separator == value.length() - 1) {
                throw new IllegalArgumentException("invalid ITAM synchronization cursor");
            }
            try {
                return new Cursor(Instant.parse(value.substring(0, separator)), DomainIdentifier.parse(value.substring(separator + 1)));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("invalid ITAM synchronization cursor", invalid);
            }
        }
    }
}
