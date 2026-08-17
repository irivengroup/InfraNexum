package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.partner.application.PartnerPage;
import io.infranexum.itam.partner.application.PartnerSearchCriteria;
import io.infranexum.itam.partner.domain.Partner;
import io.infranexum.itam.partner.domain.PartnerAccreditation;
import io.infranexum.itam.partner.domain.PartnerAuthorizationStatus;
import io.infranexum.itam.partner.domain.PartnerCode;
import io.infranexum.itam.partner.domain.PartnerConflictException;
import io.infranexum.itam.partner.domain.PartnerContact;
import io.infranexum.itam.partner.domain.PartnerExternalId;
import io.infranexum.itam.partner.domain.PartnerRole;
import io.infranexum.itam.partner.ports.PartnerRepository;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

/** PostgreSQL/Oracle Partner repository with bounded, batch-hydrated catalogue reads. */
public final class JdbcPartnerRepository implements PartnerRepository {
    private static final String CORE_COLUMNS = "id,governing_organization_id,governing_subdivision_id,code,"
            + "legal_name,display_name,country_code,authorization_status,valid_from,valid_until,official_website,"
            + "support_portal,version,created_at,updated_at,created_by,updated_by,last_reason";

    private final DataSource dataSource;
    private final JdbcConnectionAccess transaction;
    private final JdbcDatabaseDialect dialect;

    public JdbcPartnerRepository(DataSource dataSource, JdbcConnectionAccess transaction, JdbcDatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public long count() {
        Connection current = transaction.requireCurrentConnection();
        try (PreparedStatement statement = current.prepareStatement("SELECT COUNT(*) FROM " + partnerTable());
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("partner count returned no row");
            }
            return resultSet.getLong(1);
        } catch (SQLException failure) {
            throw fail("count ITAM partners", failure);
        }
    }

    @Override
    public boolean existsByCode(DomainIdentifier organizationId, PartnerCode code) {
        Objects.requireNonNull(organizationId, "organizationId"); Objects.requireNonNull(code, "code");
        String sql = "SELECT 1 FROM " + partnerTable() + " WHERE governing_organization_id=? AND code=?";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, organizationId);
            statement.setString(2, code.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException failure) {
            throw fail("check ITAM partner code", failure);
        }
    }

    @Override
    public boolean hasIdentityTokenCollision(DomainIdentifier organizationId, Set<String> identityTokens) {
        Objects.requireNonNull(organizationId, "organizationId"); Objects.requireNonNull(identityTokens, "identityTokens");
        if (identityTokens.isEmpty()) return false;
        String placeholders = String.join(",", java.util.Collections.nCopies(identityTokens.size(), "?"));
        String sql = "SELECT 1 FROM " + identityTable()
                + " WHERE governing_organization_id=? AND identity_token IN (" + placeholders + ")";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            int index = 1;
            dialect.bindIdentifier(statement, index++, organizationId);
            for (String token : identityTokens) {
                statement.setString(index++, token);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException failure) {
            throw fail("check ITAM partner duplicate identity", failure);
        }
    }

    @Override
    public Optional<Partner> findById(DomainIdentifier id) {
        Objects.requireNonNull(id, "id");
        Connection current = currentConnectionOrNull();
        if (current != null) {
            return findById(current, id);
        }
        try (Connection connection = dataSource.getConnection()) {
            return findById(connection, id);
        } catch (SQLException failure) {
            throw fail("find ITAM partner", failure);
        }
    }

    @Override
    public void insert(Partner partner) {
        Objects.requireNonNull(partner, "partner");
        Connection connection = transaction.requireCurrentConnection();
        String sql = "INSERT INTO " + partnerTable() + " (" + CORE_COLUMNS + ",legal_name_normalized)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindCore(statement, partner); statement.setString(19, partner.normalizedLegalName());
            if (statement.executeUpdate() != 1) throw new SQLException("partner insert affected unexpected rows");
            insertChildren(connection, partner);
        } catch (SQLException failure) {
            if (dialect.isUniqueViolation(failure)) {
                throw new PartnerConflictException("PARTNER_DUPLICATE", "partner code or identity was committed concurrently");
            }
            throw fail("insert ITAM partner", failure);
        }
    }

    @Override
    public void updateLifecycle(Partner partner, long expectedVersion) {
        Objects.requireNonNull(partner, "partner");
        String sql = "UPDATE " + partnerTable()
                + " SET authorization_status=?,version=?,updated_at=?,updated_by=?,last_reason=? WHERE id=? AND version=?";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            statement.setString(1, partner.authorizationStatus().name());
            statement.setLong(2, partner.version());
            JdbcTemporal.bindInstant(statement, 3, partner.updatedAt());
            dialect.bindIdentifier(statement, 4, partner.updatedBy());
            statement.setString(5, partner.lastReason());
            dialect.bindIdentifier(statement, 6, partner.id());
            statement.setLong(7, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new PartnerConflictException("VERSION_CONFLICT", "partner version changed");
            }
        } catch (SQLException failure) {
            throw fail("update ITAM partner lifecycle", failure);
        }
    }

    @Override
    public PartnerPage search(PartnerSearchCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria");
        StringBuilder sql = new StringBuilder("SELECT ").append(CORE_COLUMNS).append(" FROM ")
                .append(partnerTable()).append(" p WHERE 1=1");
        List<Binder> binders = new ArrayList<>();
        if (criteria.governingOrganizationId() != null) {
            sql.append(" AND p.governing_organization_id=?");
            binders.add((statement, index) -> dialect.bindIdentifier(statement, index, criteria.governingOrganizationId()));
        }
        if (criteria.role() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM ").append(roleTable())
                    .append(" r WHERE r.partner_id=p.id AND r.role_code=?)");
            binders.add((statement, index) -> statement.setString(index, criteria.role().name()));
        }
        if (criteria.authorizationStatus() != null) {
            sql.append(" AND p.authorization_status=?");
            binders.add((statement, index) -> statement.setString(index, criteria.authorizationStatus().name()));
        }
        if (criteria.countryCode() != null) {
            sql.append(" AND p.country_code=?");
            binders.add((statement, index) -> statement.setString(index, criteria.countryCode()));
        }
        if (criteria.accreditation() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM ").append(accreditationTable())
                    .append(" a WHERE a.partner_id=p.id AND UPPER(a.accreditation_code)=UPPER(?))");
            binders.add((statement, index) -> statement.setString(index, criteria.accreditation()));
        }
        if (criteria.effectiveOn() != null) {
            sql.append(" AND p.valid_from<=? AND (p.valid_until IS NULL OR p.valid_until>=?)");
            binders.add((statement, index) -> statement.setDate(index, Date.valueOf(criteria.effectiveOn())));
            binders.add((statement, index) -> statement.setDate(index, Date.valueOf(criteria.effectiveOn())));
        }
        if (criteria.afterId() != null) {
            sql.append(" AND p.id>?");
            binders.add((statement, index) -> dialect.bindIdentifier(statement, index, criteria.afterId()));
        }
        sql.append(" ORDER BY p.id");
        if (dialect == JdbcDatabaseDialect.POSTGRESQL) sql.append(" LIMIT ?");
        else sql.append(" FETCH NEXT ? ROWS ONLY");

        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1; for (Binder binder : binders) binder.bind(statement, index++); statement.setInt(index, criteria.limit() + 1);
            List<Core> cores = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) cores.add(readCore(resultSet));
            }
            boolean hasMore = cores.size() > criteria.limit();
            if (hasMore) cores = new ArrayList<>(cores.subList(0, criteria.limit()));
            List<Partner> items = hydrate(connection, cores);
            DomainIdentifier next = hasMore && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
            return new PartnerPage(items, next);
        } catch (SQLException failure) {
            throw fail("search ITAM partners", failure);
        }
    }

    private List<Partner> hydrate(Connection connection, List<Core> cores) throws SQLException {
        if (cores.isEmpty()) return List.of();
        List<DomainIdentifier> ids = cores.stream().map(Core::id).toList();
        Map<DomainIdentifier, Set<PartnerRole>> roles = loadRoles(connection, ids);
        Map<DomainIdentifier, List<String>> aliases = loadAliases(connection, ids);
        Map<DomainIdentifier, List<PartnerExternalId>> external = loadExternalIds(connection, ids);
        Map<DomainIdentifier, List<PartnerAccreditation>> accreditations = loadAccreditations(connection, ids);
        Map<DomainIdentifier, List<PartnerContact>> contacts = loadContacts(connection, ids);
        List<Partner> result = new ArrayList<>(cores.size());
        for (Core core : cores) {
            result.add(Partner.restore(core.id(), core.organizationId(), core.subdivisionId(), new PartnerCode(core.code()),
                    core.legalName(), core.displayName(), core.countryCode(), roles.getOrDefault(core.id(), Set.of()),
                    PartnerAuthorizationStatus.valueOf(core.status()), core.validFrom(), core.validUntil(),
                    core.officialWebsite(), core.supportPortal(), aliases.getOrDefault(core.id(), List.of()),
                    external.getOrDefault(core.id(), List.of()), accreditations.getOrDefault(core.id(), List.of()),
                    contacts.getOrDefault(core.id(), List.of()), core.version(), core.createdAt(), core.updatedAt(),
                    core.createdBy(), core.updatedBy(), core.lastReason()));
        }
        return List.copyOf(result);
    }

    private Core findCore(Connection connection, DomainIdentifier id) throws SQLException {
        String sql = "SELECT " + CORE_COLUMNS + " FROM " + partnerTable() + " WHERE id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, id);
            try (ResultSet resultSet = statement.executeQuery()) { return resultSet.next() ? readCore(resultSet) : null; }
        }
    }

    private Core readCore(ResultSet resultSet) throws SQLException {
        return new Core(
                dialect.readIdentifier(resultSet, "id"),
                dialect.readIdentifier(resultSet, "governing_organization_id"),
                nullableIdentifier(resultSet, "governing_subdivision_id"), resultSet.getString("code"),
                resultSet.getString("legal_name"), resultSet.getString("display_name"), resultSet.getString("country_code"),
                resultSet.getString("authorization_status"), resultSet.getDate("valid_from").toLocalDate(),
                nullableDate(resultSet, "valid_until"), resultSet.getString("official_website"),
                resultSet.getString("support_portal"), resultSet.getLong("version"),
                JdbcTemporal.readRequired(resultSet, "created_at"), JdbcTemporal.readRequired(resultSet, "updated_at"),
                dialect.readIdentifier(resultSet, "created_by"), dialect.readIdentifier(resultSet, "updated_by"),
                resultSet.getString("last_reason"));
    }

    private void bindCore(PreparedStatement statement, Partner partner) throws SQLException {
        int index = 1;
        dialect.bindIdentifier(statement, index++, partner.id());
        dialect.bindIdentifier(statement, index++, partner.governingOrganizationId());
        dialect.bindNullableIdentifier(statement, index++, partner.governingSubdivisionId());
        statement.setString(index++, partner.code().value()); statement.setString(index++, partner.legalName());
        statement.setString(index++, partner.displayName()); statement.setString(index++, partner.countryCode());
        statement.setString(index++, partner.authorizationStatus().name()); statement.setDate(index++, Date.valueOf(partner.validFrom()));
        bindDate(statement, index++, partner.validUntil()); statement.setString(index++, partner.officialWebsite());
        statement.setString(index++, partner.supportPortal()); statement.setLong(index++, partner.version());
        JdbcTemporal.bindInstant(statement, index++, partner.createdAt()); JdbcTemporal.bindInstant(statement, index++, partner.updatedAt());
        dialect.bindIdentifier(statement, index++, partner.createdBy()); dialect.bindIdentifier(statement, index++, partner.updatedBy());
        statement.setString(index, partner.lastReason());
    }

    private void insertChildren(Connection connection, Partner partner) throws SQLException {
        for (PartnerRole role : partner.roles()) insertRole(connection, partner, role);
        for (String alias : partner.aliases()) insertAlias(connection, partner, alias);
        for (PartnerExternalId externalId : partner.externalIds()) insertExternalId(connection, partner, externalId);
        int position = 1; for (PartnerAccreditation value : partner.accreditations()) insertAccreditation(connection, partner, position++, value);
        position = 1; for (PartnerContact value : partner.contacts()) insertContact(connection, partner, position++, value);
        for (String token : partner.identityTokens()) insertIdentityToken(connection, partner, token);
    }

    private void insertRole(Connection connection, Partner partner, PartnerRole role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + roleTable() + " (partner_id,role_code) VALUES (?,?)")) {
            dialect.bindIdentifier(statement, 1, partner.id()); statement.setString(2, role.name()); statement.executeUpdate();
        }
    }
    private void insertAlias(Connection connection, Partner partner, String alias) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + aliasTable() + " (partner_id,alias_name) VALUES (?,?)")) {
            dialect.bindIdentifier(statement, 1, partner.id()); statement.setString(2, alias); statement.executeUpdate();
        }
    }
    private void insertExternalId(Connection connection, Partner partner, PartnerExternalId value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + externalIdTable() + " (partner_id,authority_code,external_value) VALUES (?,?,?)")) {
            dialect.bindIdentifier(statement, 1, partner.id()); statement.setString(2, value.authority()); statement.setString(3, value.value()); statement.executeUpdate();
        }
    }
    private void insertAccreditation(Connection connection, Partner partner, int position, PartnerAccreditation value) throws SQLException {
        String sql = "INSERT INTO " + accreditationTable() + " (partner_id,position_no,accreditation_code,issuer_name,valid_from,valid_until,evidence_reference) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, partner.id()); statement.setInt(2, position); statement.setString(3, value.code());
            statement.setString(4, value.issuer()); statement.setDate(5, Date.valueOf(value.validFrom())); bindDate(statement, 6, value.validUntil());
            statement.setString(7, value.evidenceReference()); statement.executeUpdate();
        }
    }
    private void insertContact(Connection connection, Partner partner, int position, PartnerContact value) throws SQLException {
        String sql = "INSERT INTO " + contactTable() + " (partner_id,position_no,contact_type,contact_name,email_address,phone_number,contact_uri) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, partner.id()); statement.setInt(2, position); statement.setString(3, value.type());
            statement.setString(4, value.name()); statement.setString(5, value.email()); statement.setString(6, value.phone());
            statement.setString(7, value.uri()); statement.executeUpdate();
        }
    }
    private void insertIdentityToken(Connection connection, Partner partner, String token) throws SQLException {
        String sql = "INSERT INTO " + identityTable() + " (governing_organization_id,identity_token,partner_id) VALUES (?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, partner.governingOrganizationId()); statement.setString(2, token);
            dialect.bindIdentifier(statement, 3, partner.id()); statement.executeUpdate();
        }
    }

    private Map<DomainIdentifier, Set<PartnerRole>> loadRoles(Connection c, List<DomainIdentifier> ids) throws SQLException {
        Map<DomainIdentifier, Set<PartnerRole>> result = new HashMap<>();
        queryChildren(c, roleTable(), "role_code", ids, rs -> {
            DomainIdentifier id = dialect.readIdentifier(rs, "partner_id");
            result.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(PartnerRole.valueOf(rs.getString("role_code")));
        });
        return result;
    }
    private Map<DomainIdentifier, List<String>> loadAliases(Connection c, List<DomainIdentifier> ids) throws SQLException {
        Map<DomainIdentifier, List<String>> result = new LinkedHashMap<>();
        queryChildren(c, aliasTable(), "alias_name", ids, rs -> add(result, dialect.readIdentifier(rs, "partner_id"), rs.getString("alias_name")));
        return result;
    }
    private Map<DomainIdentifier, List<PartnerExternalId>> loadExternalIds(Connection c, List<DomainIdentifier> ids) throws SQLException {
        Map<DomainIdentifier, List<PartnerExternalId>> result = new LinkedHashMap<>();
        queryChildren(c, externalIdTable(), "authority_code,external_value", ids, rs -> add(result,
                dialect.readIdentifier(rs, "partner_id"), new PartnerExternalId(rs.getString("authority_code"), rs.getString("external_value"))));
        return result;
    }
    private Map<DomainIdentifier, List<PartnerAccreditation>> loadAccreditations(Connection c, List<DomainIdentifier> ids) throws SQLException {
        Map<DomainIdentifier, List<PartnerAccreditation>> result = new LinkedHashMap<>();
        queryChildren(c, accreditationTable(), "position_no,accreditation_code,issuer_name,valid_from,valid_until,evidence_reference", ids, rs -> add(result,
                dialect.readIdentifier(rs, "partner_id"), new PartnerAccreditation(rs.getString("accreditation_code"), rs.getString("issuer_name"),
                        rs.getDate("valid_from").toLocalDate(), nullableDate(rs, "valid_until"), rs.getString("evidence_reference"))));
        return result;
    }
    private Map<DomainIdentifier, List<PartnerContact>> loadContacts(Connection c, List<DomainIdentifier> ids) throws SQLException {
        Map<DomainIdentifier, List<PartnerContact>> result = new LinkedHashMap<>();
        queryChildren(c, contactTable(), "position_no,contact_type,contact_name,email_address,phone_number,contact_uri", ids, rs -> add(result,
                dialect.readIdentifier(rs, "partner_id"), new PartnerContact(rs.getString("contact_type"), rs.getString("contact_name"),
                        rs.getString("email_address"), rs.getString("phone_number"), rs.getString("contact_uri"))));
        return result;
    }

    private void queryChildren(Connection connection, String table, String columns, List<DomainIdentifier> ids, RowConsumer consumer) throws SQLException {
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String order = columns.contains("position_no") ? " ORDER BY partner_id,position_no" : " ORDER BY partner_id";
        String sql = "SELECT partner_id," + columns + " FROM " + table + " WHERE partner_id IN (" + placeholders + ")" + order;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1; for (DomainIdentifier id : ids) dialect.bindIdentifier(statement, index++, id);
            try (ResultSet resultSet = statement.executeQuery()) { while (resultSet.next()) consumer.accept(resultSet); }
        }
    }

    private Optional<Partner> findById(Connection connection, DomainIdentifier id) {
        try {
            Core core = findCore(connection, id);
            if (core == null) {
                return Optional.empty();
            }
            return Optional.of(hydrate(connection, List.of(core)).get(0));
        } catch (SQLException failure) {
            throw fail("find ITAM partner", failure);
        }
    }

    private Connection currentConnectionOrNull() {
        try {
            return transaction.requireCurrentConnection();
        } catch (IllegalStateException noTransaction) {
            return null;
        }
    }

    private DomainIdentifier nullableIdentifier(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column) == null ? null : dialect.readIdentifier(resultSet, column);
    }
    private static java.time.LocalDate nullableDate(ResultSet resultSet, String column) throws SQLException {
        Date value = resultSet.getDate(column); return value == null ? null : value.toLocalDate();
    }
    private static void bindDate(PreparedStatement statement, int index, java.time.LocalDate value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.DATE); else statement.setDate(index, Date.valueOf(value));
    }
    private static <T> void add(Map<DomainIdentifier, List<T>> map, DomainIdentifier id, T value) {
        map.computeIfAbsent(id, ignored -> new ArrayList<>()).add(value);
    }

    private String partnerTable() { return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_itam.partner" : "INFRANEXUM_ITAM_PARTNER"; }
    private String roleTable() { return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_itam.partner_role" : "INFRANEXUM_ITAM_PARTNER_ROLE"; }
    private String aliasTable() { return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_itam.partner_alias" : "INFRANEXUM_ITAM_PARTNER_ALIAS"; }
    private String externalIdTable() { return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_itam.partner_external_id" : "INFRANEXUM_ITAM_PARTNER_EXT_ID"; }
    private String accreditationTable() { return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_itam.partner_accreditation" : "INFRANEXUM_ITAM_PARTNER_ACCRED"; }
    private String contactTable() { return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_itam.partner_contact" : "INFRANEXUM_ITAM_PARTNER_CONTACT"; }
    private String identityTable() { return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_itam.partner_identity_token" : "INFRANEXUM_ITAM_PARTNER_IDENT"; }

    private static JdbcPersistenceException fail(String operation, SQLException failure) { return new JdbcPersistenceException(operation, failure); }

    @FunctionalInterface private interface Binder { void bind(PreparedStatement statement, int index) throws SQLException; }
    @FunctionalInterface private interface RowConsumer { void accept(ResultSet resultSet) throws SQLException; }
    private record Core(DomainIdentifier id, DomainIdentifier organizationId, DomainIdentifier subdivisionId,
            String code, String legalName, String displayName, String countryCode, String status,
            java.time.LocalDate validFrom, java.time.LocalDate validUntil, String officialWebsite, String supportPortal,
            long version, java.time.Instant createdAt, java.time.Instant updatedAt, DomainIdentifier createdBy,
            DomainIdentifier updatedBy, String lastReason) {}
}
