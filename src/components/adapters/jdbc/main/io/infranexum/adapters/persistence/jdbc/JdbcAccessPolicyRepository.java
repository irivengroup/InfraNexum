package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.domain.AccessPolicy;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.IdentityAccessException;
import io.infranexum.identity.access.domain.PolicyAttributeSource;
import io.infranexum.identity.access.domain.PolicyCondition;
import io.infranexum.identity.access.domain.PolicyEffect;
import io.infranexum.identity.access.domain.PolicyObligation;
import io.infranexum.identity.access.domain.PolicyOperator;
import io.infranexum.identity.access.domain.PolicyRule;
import io.infranexum.identity.access.domain.PolicyState;
import io.infranexum.identity.access.domain.ScopeKind;
import io.infranexum.identity.access.domain.SeparationOfDutyConstraint;
import io.infranexum.identity.access.ports.AccessPolicyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;

/** JDBC PRP for immutable ABAC policy versions and static SoD constraints. */
public final class JdbcAccessPolicyRepository implements AccessPolicyRepository {
    private final DataSource dataSource;
    private final JdbcConnectionAccess transaction;
    private final JdbcDatabaseDialect dialect;

    public JdbcAccessPolicyRepository(DataSource dataSource, JdbcConnectionAccess transaction, JdbcDatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public long nextVersion(DomainIdentifier organizationId, String code) {
        Objects.requireNonNull(code, "code");
        return withRead(connection -> {
            String sql = "SELECT COALESCE(MAX(policy_version),0)+1 AS next_version FROM " + policyTable()
                    + " WHERE code=? AND " + organizationPredicate(organizationId);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, code);
                if (organizationId != null) dialect.bindIdentifier(statement, 2, organizationId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new SQLException("policy version query returned no row");
                    return rows.getLong("next_version");
                }
            }
        }, "allocate IAM policy version");
    }

    @Override
    public void insertPolicy(AccessPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        String sql = "INSERT INTO " + policyTable()
                + " (id,organization_id,code,policy_version,owner_id,purpose,priority,scope_kind,subdivision_id,state,effective_from,approved_by,approved_at,activated_at,deprecated_at,retired_at,created_at,updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = writeConnection().prepareStatement(sql)) {
            bindPolicy(statement, policy);
            requireOne(statement.executeUpdate(), "IAM policy insert");
        } catch (SQLException failure) {
            if (dialect.isUniqueViolation(failure)) throw conflict("IAM_POLICY_VERSION_CONFLICT", "policy version already exists");
            throw fail("insert IAM policy", failure);
        }
        try {
            insertRules(policy);
        } catch (SQLException failure) {
            throw fail("insert IAM policy rules", failure);
        }
    }

    @Override
    public void updatePolicyState(AccessPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        String sql = "UPDATE " + policyTable()
                + " SET state=?,approved_by=?,approved_at=?,activated_at=?,deprecated_at=?,retired_at=?,updated_at=? WHERE id=?";
        try (PreparedStatement statement = writeConnection().prepareStatement(sql)) {
            statement.setString(1, policy.state().name());
            dialect.bindNullableIdentifier(statement, 2, policy.approvedBy());
            bindNullableInstant(statement, 3, policy.approvedAt());
            bindNullableInstant(statement, 4, policy.activatedAt());
            bindNullableInstant(statement, 5, policy.deprecatedAt());
            bindNullableInstant(statement, 6, policy.retiredAt());
            JdbcTemporal.bindInstant(statement, 7, policy.updatedAt());
            dialect.bindIdentifier(statement, 8, policy.id());
            requireOne(statement.executeUpdate(), "IAM policy state update");
        } catch (SQLException failure) {
            throw fail("update IAM policy state", failure);
        }
    }

    @Override
    public Optional<AccessPolicy> findPolicy(DomainIdentifier policyId) {
        Objects.requireNonNull(policyId, "policyId");
        return withRead(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(basePolicySelect() + " WHERE id=?")) {
                dialect.bindIdentifier(statement, 1, policyId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return Optional.empty();
                    return Optional.of(readPolicy(rows, loadRules(connection, policyId)));
                }
            }
        }, "find IAM policy");
    }

    @Override
    public List<AccessPolicy> listPolicies(DomainIdentifier organizationId, int offset, int limit) {
        return withRead(connection -> {
            String sql = basePolicySelect() + " WHERE " + organizationPredicate(organizationId)
                    + " ORDER BY code,policy_version DESC " + pagination();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int first = 1;
                if (organizationId != null) dialect.bindIdentifier(statement, first++, organizationId);
                bindPage(statement, offset, limit, first);
                try (ResultSet rows = statement.executeQuery()) {
                    List<AccessPolicy> result = new ArrayList<>();
                    while (rows.next()) {
                        DomainIdentifier id = dialect.readIdentifier(rows, "id");
                        result.add(readPolicy(rows, loadRules(connection, id)));
                    }
                    return List.copyOf(result);
                }
            }
        }, "list IAM policies");
    }

    @Override
    public List<AccessPolicy> activePolicies(AuthorizationScope scope, Instant at) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(at, "at");
        return withRead(connection -> loadActivePolicies(connection, scope, at), "load active IAM policies");
    }

    @Override
    public void deprecateActiveVersions(DomainIdentifier organizationId, String code, DomainIdentifier exceptPolicyId, Instant at) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(exceptPolicyId, "exceptPolicyId");
        Objects.requireNonNull(at, "at");
        String sql = "UPDATE " + policyTable()
                + " SET state='DEPRECATED',deprecated_at=?,updated_at=? WHERE code=? AND "
                + organizationPredicate(organizationId) + " AND state='ACTIVE' AND id<>?";
        try (PreparedStatement statement = writeConnection().prepareStatement(sql)) {
            JdbcTemporal.bindInstant(statement, 1, at);
            JdbcTemporal.bindInstant(statement, 2, at);
            statement.setString(3, code);
            int index = 4;
            if (organizationId != null) dialect.bindIdentifier(statement, index++, organizationId);
            dialect.bindIdentifier(statement, index, exceptPolicyId);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw fail("deprecate active IAM policy versions", failure);
        }
    }

    @Override
    public void insertSeparationOfDutyConstraint(SeparationOfDutyConstraint constraint) {
        Objects.requireNonNull(constraint, "constraint");
        String sql = "INSERT INTO " + sodTable()
                + " (id,policy_id,organization_id,first_role_id,second_role_id,reason,created_at,created_by) VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = writeConnection().prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, constraint.id());
            dialect.bindIdentifier(statement, 2, constraint.policyId());
            dialect.bindNullableIdentifier(statement, 3, constraint.organizationId());
            dialect.bindIdentifier(statement, 4, constraint.firstRoleId());
            dialect.bindIdentifier(statement, 5, constraint.secondRoleId());
            statement.setString(6, constraint.reason());
            JdbcTemporal.bindInstant(statement, 7, constraint.createdAt());
            dialect.bindIdentifier(statement, 8, constraint.createdBy());
            requireOne(statement.executeUpdate(), "IAM SoD constraint insert");
        } catch (SQLException failure) {
            if (dialect.isUniqueViolation(failure)) throw conflict("IAM_SOD_CONSTRAINT_CONFLICT", "duplicate SoD role pair in policy");
            throw fail("insert IAM SoD constraint", failure);
        }
    }

    @Override
    public List<SeparationOfDutyConstraint> activeSeparationOfDutyConstraints(
            DomainIdentifier organizationId, DomainIdentifier roleId, Instant at) {
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(at, "at");
        return withRead(connection -> {
            String sql = "SELECT s.id,s.policy_id,s.organization_id,s.first_role_id,s.second_role_id,s.reason,s.created_at,s.created_by"
                    + " FROM " + sodTable() + " s JOIN " + policyTable() + " p ON p.id=s.policy_id"
                    + " WHERE " + prefixedOrganizationPredicate("s", organizationId)
                    + " AND ((p.organization_id IS NULL AND s.organization_id IS NULL) OR p.organization_id=s.organization_id)"
                    + " AND (s.first_role_id=? OR s.second_role_id=?) AND p.state='ACTIVE' AND p.effective_from<=?"
                    + " ORDER BY s.id";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                if (organizationId != null) dialect.bindIdentifier(statement, index++, organizationId);
                dialect.bindIdentifier(statement, index++, roleId);
                dialect.bindIdentifier(statement, index++, roleId);
                JdbcTemporal.bindInstant(statement, index, at);
                try (ResultSet rows = statement.executeQuery()) {
                    List<SeparationOfDutyConstraint> result = new ArrayList<>();
                    while (rows.next()) result.add(readSod(rows));
                    return List.copyOf(result);
                }
            }
        }, "load active IAM SoD constraints");
    }

    private List<AccessPolicy> loadActivePolicies(Connection connection, AuthorizationScope requested, Instant at) throws SQLException {
        String sql = joinedPolicySelect() + " WHERE p.state='ACTIVE' AND p.effective_from<=? AND (p.organization_id IS NULL"
                + (requested.organizationId() == null ? ")" : " OR p.organization_id=?)")
                + " ORDER BY p.priority DESC,p.code,p.policy_version,r.position_no,c.position_no";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcTemporal.bindInstant(statement, 1, at);
            if (requested.organizationId() != null) dialect.bindIdentifier(statement, 2, requested.organizationId());
            try (ResultSet rows = statement.executeQuery()) {
                LinkedHashMap<DomainIdentifier, PolicyAccumulator> policies = new LinkedHashMap<>();
                while (rows.next()) {
                    DomainIdentifier policyId = dialect.readIdentifier(rows, "policy_id");
                    PolicyAccumulator accumulator = policies.computeIfAbsent(policyId, ignored -> readAccumulator(rows));
                    accumulator.accept(rows);
                }
                List<AccessPolicy> result = new ArrayList<>();
                for (PolicyAccumulator accumulator : policies.values()) {
                    AccessPolicy policy = accumulator.build();
                    if (policy.scope().covers(requested)) result.add(policy);
                }
                return List.copyOf(result);
            }
        }
    }

    private PolicyAccumulator readAccumulator(ResultSet rows) {
        try {
            return new PolicyAccumulator(
                    dialect.readIdentifier(rows, "policy_id"), nullableIdentifier(rows, "p_organization_id"),
                    rows.getString("p_code"), rows.getLong("p_version"), dialect.readIdentifier(rows, "p_owner_id"),
                    rows.getString("p_purpose"), rows.getInt("p_priority"), readScope(rows, "p_"),
                    PolicyState.valueOf(rows.getString("p_state")), JdbcTemporal.readRequired(rows, "p_effective_from"),
                    nullableIdentifier(rows, "p_approved_by"), JdbcTemporal.readNullable(rows, "p_approved_at"),
                    JdbcTemporal.readNullable(rows, "p_activated_at"), JdbcTemporal.readNullable(rows, "p_deprecated_at"),
                    JdbcTemporal.readNullable(rows, "p_retired_at"), JdbcTemporal.readRequired(rows, "p_created_at"),
                    JdbcTemporal.readRequired(rows, "p_updated_at"));
        } catch (SQLException failure) {
            throw fail("read active IAM policy", failure);
        }
    }

    private AccessPolicy readPolicy(ResultSet rows, List<PolicyRule> rules) throws SQLException {
        return new AccessPolicy(dialect.readIdentifier(rows, "id"), nullableIdentifier(rows, "organization_id"),
                rows.getString("code"), rows.getLong("policy_version"), dialect.readIdentifier(rows, "owner_id"),
                rows.getString("purpose"), rows.getInt("priority"), readScope(rows, ""),
                PolicyState.valueOf(rows.getString("state")), JdbcTemporal.readRequired(rows, "effective_from"),
                nullableIdentifier(rows, "approved_by"), JdbcTemporal.readNullable(rows, "approved_at"),
                JdbcTemporal.readNullable(rows, "activated_at"), JdbcTemporal.readNullable(rows, "deprecated_at"),
                JdbcTemporal.readNullable(rows, "retired_at"), JdbcTemporal.readRequired(rows, "created_at"),
                JdbcTemporal.readRequired(rows, "updated_at"), rules);
    }

    private List<PolicyRule> loadRules(Connection connection, DomainIdentifier policyId) throws SQLException {
        String sql = "SELECT r.id AS rule_id,r.position_no,r.effect,r.action_selector,r.resource_type,r.obligations_csv,r.advice,"
                + "c.position_no AS condition_position,c.source_name,c.attribute_name,c.operator_name,c.expected_value"
                + " FROM " + ruleTable() + " r LEFT JOIN " + conditionTable() + " c ON c.rule_id=r.id"
                + " WHERE r.policy_id=? ORDER BY r.position_no,c.position_no";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, policyId);
            try (ResultSet rows = statement.executeQuery()) {
                LinkedHashMap<DomainIdentifier, RuleAccumulator> rules = new LinkedHashMap<>();
                while (rows.next()) {
                    DomainIdentifier ruleId = dialect.readIdentifier(rows, "rule_id");
                    RuleAccumulator accumulator = rules.computeIfAbsent(ruleId, ignored -> readRuleAccumulator(rows));
                    accumulator.acceptCondition(rows);
                }
                return rules.values().stream().map(RuleAccumulator::build).toList();
            }
        }
    }

    private RuleAccumulator readRuleAccumulator(ResultSet rows) {
        try {
            return new RuleAccumulator(dialect.readIdentifier(rows, "rule_id"), rows.getInt("position_no"),
                    PolicyEffect.valueOf(rows.getString("effect")), rows.getString("action_selector"),
                    rows.getString("resource_type"), parseObligations(rows.getString("obligations_csv")),
                    rows.getString("advice"));
        } catch (SQLException failure) {
            throw fail("read IAM policy rule", failure);
        }
    }

    private void insertRules(AccessPolicy policy) throws SQLException {
        String ruleSql = "INSERT INTO " + ruleTable()
                + " (id,policy_id,position_no,effect,action_selector,resource_type,obligations_csv,advice) VALUES (?,?,?,?,?,?,?,?)";
        String conditionSql = "INSERT INTO " + conditionTable()
                + " (rule_id,position_no,source_name,attribute_name,operator_name,expected_value) VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ruleStatement = writeConnection().prepareStatement(ruleSql);
             PreparedStatement conditionStatement = writeConnection().prepareStatement(conditionSql)) {
            for (PolicyRule rule : policy.rules()) {
                dialect.bindIdentifier(ruleStatement, 1, rule.id());
                dialect.bindIdentifier(ruleStatement, 2, policy.id());
                ruleStatement.setInt(3, rule.position());
                ruleStatement.setString(4, rule.effect().name());
                ruleStatement.setString(5, rule.action());
                ruleStatement.setString(6, rule.resourceType());
                ruleStatement.setString(7, obligationsCsv(rule.obligations()));
                ruleStatement.setString(8, rule.advice());
                ruleStatement.addBatch();
                for (int index = 0; index < rule.conditions().size(); index++) {
                    PolicyCondition condition = rule.conditions().get(index);
                    dialect.bindIdentifier(conditionStatement, 1, rule.id());
                    conditionStatement.setInt(2, index + 1);
                    conditionStatement.setString(3, condition.source().name());
                    conditionStatement.setString(4, condition.attribute());
                    conditionStatement.setString(5, condition.operator().name());
                    conditionStatement.setString(6, condition.expectedValue());
                    conditionStatement.addBatch();
                }
            }
            ruleStatement.executeBatch();
            conditionStatement.executeBatch();
        }
    }

    private void bindPolicy(PreparedStatement statement, AccessPolicy policy) throws SQLException {
        dialect.bindIdentifier(statement, 1, policy.id());
        dialect.bindNullableIdentifier(statement, 2, policy.organizationId());
        statement.setString(3, policy.code());
        statement.setLong(4, policy.version());
        dialect.bindIdentifier(statement, 5, policy.ownerId());
        statement.setString(6, policy.purpose());
        statement.setInt(7, policy.priority());
        statement.setString(8, policy.scope().kind().name());
        dialect.bindNullableIdentifier(statement, 9, policy.scope().subdivisionId());
        statement.setString(10, policy.state().name());
        JdbcTemporal.bindInstant(statement, 11, policy.effectiveFrom());
        dialect.bindNullableIdentifier(statement, 12, policy.approvedBy());
        bindNullableInstant(statement, 13, policy.approvedAt());
        bindNullableInstant(statement, 14, policy.activatedAt());
        bindNullableInstant(statement, 15, policy.deprecatedAt());
        bindNullableInstant(statement, 16, policy.retiredAt());
        JdbcTemporal.bindInstant(statement, 17, policy.createdAt());
        JdbcTemporal.bindInstant(statement, 18, policy.updatedAt());
    }

    private SeparationOfDutyConstraint readSod(ResultSet rows) throws SQLException {
        return new SeparationOfDutyConstraint(dialect.readIdentifier(rows, "id"), dialect.readIdentifier(rows, "policy_id"),
                nullableIdentifier(rows, "organization_id"), dialect.readIdentifier(rows, "first_role_id"),
                dialect.readIdentifier(rows, "second_role_id"), rows.getString("reason"),
                JdbcTemporal.readRequired(rows, "created_at"), dialect.readIdentifier(rows, "created_by"));
    }

    private AuthorizationScope readScope(ResultSet rows, String prefix) throws SQLException {
        ScopeKind kind = ScopeKind.valueOf(rows.getString(prefix + "scope_kind"));
        DomainIdentifier organizationId = nullableIdentifier(rows, prefix + "organization_id");
        DomainIdentifier subdivisionId = nullableIdentifier(rows, prefix + "subdivision_id");
        return switch (kind) {
            case PLATFORM -> AuthorizationScope.platform();
            case ORGANIZATION -> AuthorizationScope.organization(Objects.requireNonNull(organizationId));
            case SUBDIVISION -> AuthorizationScope.subdivision(Objects.requireNonNull(organizationId), Objects.requireNonNull(subdivisionId));
        };
    }

    private static String obligationsCsv(Set<PolicyObligation> obligations) {
        return obligations.stream().map(Enum::name).sorted().reduce((left, right) -> left + "," + right).orElse("");
    }

    private static Set<PolicyObligation> parseObligations(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        TreeSet<PolicyObligation> result = new TreeSet<>();
        for (String token : csv.split(",")) result.add(PolicyObligation.valueOf(token));
        return Set.copyOf(result);
    }

    private String organizationPredicate(DomainIdentifier organizationId) {
        return organizationId == null ? "organization_id IS NULL" : "organization_id=?";
    }

    private String prefixedOrganizationPredicate(String alias, DomainIdentifier organizationId) {
        return organizationId == null ? alias + ".organization_id IS NULL" : alias + ".organization_id=?";
    }

    private String basePolicySelect() {
        return "SELECT id,organization_id,code,policy_version,owner_id,purpose,priority,scope_kind,subdivision_id,state,effective_from,approved_by,approved_at,activated_at,deprecated_at,retired_at,created_at,updated_at FROM " + policyTable();
    }

    private String joinedPolicySelect() {
        return "SELECT p.id AS policy_id,p.organization_id AS p_organization_id,p.code AS p_code,p.policy_version AS p_version,"
                + "p.owner_id AS p_owner_id,p.purpose AS p_purpose,p.priority AS p_priority,p.scope_kind AS p_scope_kind,"
                + "p.subdivision_id AS p_subdivision_id,p.state AS p_state,p.effective_from AS p_effective_from,"
                + "p.approved_by AS p_approved_by,p.approved_at AS p_approved_at,p.activated_at AS p_activated_at,"
                + "p.deprecated_at AS p_deprecated_at,p.retired_at AS p_retired_at,p.created_at AS p_created_at,p.updated_at AS p_updated_at,"
                + "r.id AS rule_id,r.position_no AS rule_position,r.effect AS rule_effect,r.action_selector AS rule_action,"
                + "r.resource_type AS rule_resource,r.obligations_csv AS rule_obligations,r.advice AS rule_advice,"
                + "c.position_no AS condition_position,c.source_name,c.attribute_name,c.operator_name,c.expected_value"
                + " FROM " + policyTable() + " p JOIN " + ruleTable() + " r ON r.policy_id=p.id"
                + " LEFT JOIN " + conditionTable() + " c ON c.rule_id=r.id";
    }

    private String policyTable() { return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_iam.access_policy" : "INFRANEXUM_IAM_ACCESS_POLICY"; }
    private String ruleTable() { return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_iam.access_policy_rule" : "INFRANEXUM_IAM_ACCESS_POLICY_RULE"; }
    private String conditionTable() { return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_iam.access_policy_condition" : "INFRANEXUM_IAM_ACCESS_POLICY_CONDITION"; }
    private String sodTable() { return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_iam.sod_constraint" : "INFRANEXUM_IAM_SOD_CONSTRAINT"; }
    private String pagination() { return dialect == JdbcDatabaseDialect.POSTGRESQL ? "LIMIT ? OFFSET ?" : "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"; }

    private void bindPage(PreparedStatement statement, int offset, int limit, int first) throws SQLException {
        if (dialect == JdbcDatabaseDialect.POSTGRESQL) {
            statement.setInt(first, limit); statement.setInt(first + 1, offset);
        } else {
            statement.setInt(first, offset); statement.setInt(first + 1, limit);
        }
    }

    private <T> T withRead(SqlWork<T> work, String operation) {
        Connection current = currentConnectionOrNull();
        if (current != null) {
            try { return work.run(current); } catch (SQLException failure) { throw fail(operation, failure); }
        }
        try (Connection connection = dataSource.getConnection()) { return work.run(connection); }
        catch (SQLException failure) { throw fail(operation, failure); }
    }

    private Connection writeConnection() { return transaction.requireCurrentConnection(); }
    private Connection currentConnectionOrNull() { try { return transaction.requireCurrentConnection(); } catch (IllegalStateException absent) { return null; } }
    private DomainIdentifier nullableIdentifier(ResultSet rows, String column) throws SQLException { return rows.getObject(column) == null ? null : dialect.readIdentifier(rows, column); }
    private static void bindNullableInstant(PreparedStatement statement, int index, Instant value) throws SQLException { if (value == null) statement.setNull(index, java.sql.Types.TIMESTAMP_WITH_TIMEZONE); else JdbcTemporal.bindInstant(statement, index, value); }
    private static void requireOne(int count, String operation) throws SQLException { if (count != 1) throw new SQLException(operation + " affected unexpected rows: " + count); }
    private static IdentityAccessException conflict(String code, String message) { return new IdentityAccessException(code, message); }
    private static JdbcPersistenceException fail(String operation, SQLException failure) { return new JdbcPersistenceException(operation, failure); }
    @FunctionalInterface private interface SqlWork<T> { T run(Connection connection) throws SQLException; }

    private final class PolicyAccumulator {
        private final DomainIdentifier id;
        private final DomainIdentifier organizationId;
        private final String code;
        private final long version;
        private final DomainIdentifier ownerId;
        private final String purpose;
        private final int priority;
        private final AuthorizationScope scope;
        private final PolicyState state;
        private final Instant effectiveFrom;
        private final DomainIdentifier approvedBy;
        private final Instant approvedAt;
        private final Instant activatedAt;
        private final Instant deprecatedAt;
        private final Instant retiredAt;
        private final Instant createdAt;
        private final Instant updatedAt;
        private final LinkedHashMap<DomainIdentifier, RuleAccumulator> rules = new LinkedHashMap<>();

        PolicyAccumulator(DomainIdentifier id, DomainIdentifier organizationId, String code, long version,
                DomainIdentifier ownerId, String purpose, int priority, AuthorizationScope scope, PolicyState state,
                Instant effectiveFrom, DomainIdentifier approvedBy, Instant approvedAt, Instant activatedAt,
                Instant deprecatedAt, Instant retiredAt, Instant createdAt, Instant updatedAt) {
            this.id=id; this.organizationId=organizationId; this.code=code; this.version=version; this.ownerId=ownerId;
            this.purpose=purpose; this.priority=priority; this.scope=scope; this.state=state; this.effectiveFrom=effectiveFrom;
            this.approvedBy=approvedBy; this.approvedAt=approvedAt; this.activatedAt=activatedAt;
            this.deprecatedAt=deprecatedAt; this.retiredAt=retiredAt; this.createdAt=createdAt; this.updatedAt=updatedAt;
        }

        void accept(ResultSet rows) throws SQLException {
            DomainIdentifier ruleId = dialect.readIdentifier(rows, "rule_id");
            RuleAccumulator rule = rules.computeIfAbsent(ruleId, ignored -> {
                try {
                    return new RuleAccumulator(ruleId, rows.getInt("rule_position"), PolicyEffect.valueOf(value(rows, "rule_effect")),
                            value(rows, "rule_action"), value(rows, "rule_resource"), parseObligations(value(rows, "rule_obligations")),
                            nullable(rows, "rule_advice"));
                } catch (SQLException failure) {
                    throw fail("read active IAM policy rule", failure);
                }
            });
            rule.acceptCondition(rows);
        }

        AccessPolicy build() {
            return new AccessPolicy(id, organizationId, code, version, ownerId, purpose, priority, scope, state, effectiveFrom,
                    approvedBy, approvedAt, activatedAt, deprecatedAt, retiredAt, createdAt, updatedAt,
                    rules.values().stream().map(RuleAccumulator::build).toList());
        }
    }

    private final class RuleAccumulator {
        private final DomainIdentifier id;
        private final int position;
        private final PolicyEffect effect;
        private final String action;
        private final String resourceType;
        private final Set<PolicyObligation> obligations;
        private final String advice;
        private final List<PolicyCondition> conditions = new ArrayList<>();

        RuleAccumulator(DomainIdentifier id, int position, PolicyEffect effect, String action, String resourceType,
                Set<PolicyObligation> obligations, String advice) {
            this.id=id; this.position=position; this.effect=effect; this.action=action; this.resourceType=resourceType;
            this.obligations=obligations; this.advice=advice == null ? "" : advice;
        }

        void acceptCondition(ResultSet rows) throws SQLException {
            if (rows.getObject("condition_position") == null) return;
            conditions.add(new PolicyCondition(PolicyAttributeSource.valueOf(rows.getString("source_name")),
                    rows.getString("attribute_name"), PolicyOperator.valueOf(rows.getString("operator_name")),
                    rows.getString("expected_value")));
        }

        PolicyRule build() { return new PolicyRule(id, position, effect, action, resourceType, conditions, obligations, advice); }
    }

    private static String value(ResultSet rows, String column) throws SQLException { return Objects.requireNonNull(rows.getString(column), column); }
    private static String nullable(ResultSet rows, String column) throws SQLException { return rows.getString(column); }
}
