package io.infranexum.adapters.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.infranexum.identity.access.domain.SeparationOfDutyConstraint;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Deterministic PostgreSQL/Oracle coverage for the ABAC PRP and static SoD JDBC repository. */
class JdbcAccessPolicyRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-14T09:00:00Z");
    private static final DomainIdentifier POLICY = id(1);
    private static final DomainIdentifier POLICY_TWO = id(2);
    private static final DomainIdentifier OWNER = id(3);
    private static final DomainIdentifier APPROVER = id(4);
    private static final DomainIdentifier ORGANIZATION = id(5);
    private static final DomainIdentifier SUBDIVISION = id(6);
    private static final DomainIdentifier RULE = id(7);
    private static final DomainIdentifier ROLE_ONE = id(8);
    private static final DomainIdentifier ROLE_TWO = id(9);
    private static final DomainIdentifier ACTOR = id(10);

    @Test
    void constructorRejectsMissingDependenciesAndVersionAllocationHandlesPlatformAndOrganizationScopes() {
        ScriptedConnection pg = connection(query(List.of(Map.of("next_version", 4L))));
        DataSource dataSource = dataSource(pg.connection());
        JdbcConnectionAccess noTransaction = noTransaction();
        assertThrows(NullPointerException.class,
                () -> new JdbcAccessPolicyRepository(null, noTransaction, JdbcDatabaseDialect.POSTGRESQL));
        assertThrows(NullPointerException.class,
                () -> new JdbcAccessPolicyRepository(dataSource, null, JdbcDatabaseDialect.POSTGRESQL));
        assertThrows(NullPointerException.class,
                () -> new JdbcAccessPolicyRepository(dataSource, noTransaction, null));

        JdbcAccessPolicyRepository platform =
                new JdbcAccessPolicyRepository(dataSource, noTransaction, JdbcDatabaseDialect.POSTGRESQL);
        assertEquals(4L, platform.nextVersion(null, "security.access"));
        assertTrue(pg.sql().getFirst().contains("organization_id IS NULL"));
        assertEquals("security.access", pg.parameters().getFirst().get(1));

        ScriptedConnection oracle = connection(query(List.of(Map.of("next_version", 9L))));
        JdbcAccessPolicyRepository scoped = new JdbcAccessPolicyRepository(
                dataSource(oracle.connection()), noTransaction(), JdbcDatabaseDialect.ORACLE);
        assertEquals(9L, scoped.nextVersion(ORGANIZATION, "security.access"));
        assertTrue(oracle.sql().getFirst().contains("INFRANEXUM_IAM_ACCESS_POLICY"));
        assertEquals("security.access", oracle.parameters().getFirst().get(1));
        assertEquals(ORGANIZATION.toString(), oracle.parameters().getFirst().get(2));
    }

    @Test
    void findPolicyMapsLifecycleRulesConditionsAndObligations() {
        Map<String, Object> policy = policyRow(JdbcDatabaseDialect.POSTGRESQL, POLICY, ORGANIZATION,
                AuthorizationScope.subdivision(ORGANIZATION, SUBDIVISION), PolicyState.ACTIVE);
        ScriptedConnection source = connection(
                query(List.of(policy)),
                query(List.of(ruleRow(JdbcDatabaseDialect.POSTGRESQL, RULE, 1, "DENY", "iam.role.assign",
                        "iam.role", "REQUIRE_JUSTIFICATION,STEP_UP_MFA", "review", true))));
        JdbcAccessPolicyRepository repository = new JdbcAccessPolicyRepository(
                dataSource(source.connection()), noTransaction(), JdbcDatabaseDialect.POSTGRESQL);

        AccessPolicy observed = repository.findPolicy(POLICY).orElseThrow();
        assertEquals(POLICY, observed.id());
        assertEquals(ORGANIZATION, observed.organizationId());
        assertEquals(AuthorizationScope.subdivision(ORGANIZATION, SUBDIVISION), observed.scope());
        assertEquals(PolicyState.ACTIVE, observed.state());
        assertEquals(APPROVER, observed.approvedBy());
        assertEquals(NOW.plusSeconds(20), observed.activatedAt());
        assertEquals(1, observed.rules().size());
        PolicyRule rule = observed.rules().getFirst();
        assertEquals(PolicyEffect.DENY, rule.effect());
        assertEquals(Set.of(PolicyObligation.REQUIRE_JUSTIFICATION, PolicyObligation.STEP_UP_MFA), rule.obligations());
        assertEquals("review", rule.advice());
        assertEquals(new PolicyCondition(PolicyAttributeSource.SUBJECT, "department", PolicyOperator.EQUALS, "security"),
                rule.conditions().getFirst());
        assertTrue(source.sql().getFirst().contains("infranexum_iam.access_policy"));
        assertEquals(POLICY.value(), source.parameters().getFirst().get(1));
    }

    @Test
    void findAbsentAndListPoliciesExercisePostgresqlAndOraclePagination() {
        ScriptedConnection absent = connection(query(List.of()));
        JdbcAccessPolicyRepository missing = new JdbcAccessPolicyRepository(
                dataSource(absent.connection()), noTransaction(), JdbcDatabaseDialect.POSTGRESQL);
        assertTrue(missing.findPolicy(POLICY).isEmpty());

        Map<String, Object> first = policyRow(JdbcDatabaseDialect.POSTGRESQL, POLICY, ORGANIZATION,
                AuthorizationScope.organization(ORGANIZATION), PolicyState.DRAFT);
        Map<String, Object> second = policyRow(JdbcDatabaseDialect.POSTGRESQL, POLICY_TWO, ORGANIZATION,
                AuthorizationScope.organization(ORGANIZATION), PolicyState.DRAFT);
        second.put("code", "security.second");
        second.put("policy_version", 2L);
        ScriptedConnection pg = connection(
                query(List.of(first, second)),
                query(List.of(ruleRow(JdbcDatabaseDialect.POSTGRESQL, RULE, 1, "PERMIT", "iam.group.read",
                        "iam.group", "", null, true))),
                query(List.of(ruleRow(JdbcDatabaseDialect.POSTGRESQL, id(70), 1, "PERMIT", "iam.role.read",
                        "iam.role", "", "", true))));
        List<AccessPolicy> policies = new JdbcAccessPolicyRepository(
                dataSource(pg.connection()), noTransaction(), JdbcDatabaseDialect.POSTGRESQL)
                .listPolicies(ORGANIZATION, 5, 25);
        assertEquals(2, policies.size());
        assertTrue(pg.sql().getFirst().contains("LIMIT ? OFFSET ?"));
        assertEquals(ORGANIZATION.value(), pg.parameters().getFirst().get(1));
        assertEquals(25, pg.parameters().getFirst().get(2));
        assertEquals(5, pg.parameters().getFirst().get(3));
        assertEquals(Set.of(), policies.getFirst().rules().getFirst().obligations());
        assertEquals("", policies.getLast().rules().getFirst().advice());

        Map<String, Object> oraclePolicy = policyRow(JdbcDatabaseDialect.ORACLE, POLICY, ORGANIZATION,
                AuthorizationScope.organization(ORGANIZATION), PolicyState.DRAFT);
        ScriptedConnection oracle = connection(
                query(List.of(oraclePolicy)),
                query(List.of(ruleRow(JdbcDatabaseDialect.ORACLE, RULE, 1, "PERMIT", "iam.group.read",
                        "iam.group", "", null, true))));
        assertEquals(1, new JdbcAccessPolicyRepository(
                dataSource(oracle.connection()), noTransaction(), JdbcDatabaseDialect.ORACLE)
                .listPolicies(ORGANIZATION, 7, 30).size());
        assertTrue(oracle.sql().getFirst().contains("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"));
        assertEquals(ORGANIZATION.toString(), oracle.parameters().getFirst().get(1));
        assertEquals(7, oracle.parameters().getFirst().get(2));
        assertEquals(30, oracle.parameters().getFirst().get(3));
    }

    @Test
    void activePoliciesLoadPlatformAndMatchingScopedPoliciesAndFilterNarrowerScopesInDomain() {
        Map<String, Object> platform = joinedPolicyRow(
                JdbcDatabaseDialect.POSTGRESQL, POLICY, null, AuthorizationScope.platform(), 100,
                RULE, "PERMIT", "*", "*", "RBAC", "permitted", "EQUALS", "true");
        Map<String, Object> organization = joinedPolicyRow(
                JdbcDatabaseDialect.POSTGRESQL, POLICY_TWO, ORGANIZATION, AuthorizationScope.organization(ORGANIZATION), 50,
                id(71), "DENY", "iam.role.assign", "iam.role", "SUBJECT", "department", "EQUALS", "finance");
        Map<String, Object> otherSubdivision = joinedPolicyRow(
                JdbcDatabaseDialect.POSTGRESQL, id(72), ORGANIZATION,
                AuthorizationScope.subdivision(ORGANIZATION, id(999)), 40,
                id(73), "DENY", "iam.role.assign", "iam.role", "SUBJECT", "department", "EQUALS", "ops");
        ScriptedConnection pg = connection(query(List.of(platform, organization, otherSubdivision)));
        AuthorizationScope requested = AuthorizationScope.subdivision(ORGANIZATION, SUBDIVISION);
        List<AccessPolicy> observed = new JdbcAccessPolicyRepository(
                dataSource(pg.connection()), noTransaction(), JdbcDatabaseDialect.POSTGRESQL)
                .activePolicies(requested, NOW.plusSeconds(30));
        assertEquals(List.of(POLICY, POLICY_TWO), observed.stream().map(AccessPolicy::id).toList());
        assertTrue(pg.sql().getFirst().contains("p.organization_id IS NULL OR p.organization_id=?"));
        assertTrue(pg.sql().getFirst().contains("p.priority DESC"));
        assertEquals(ORGANIZATION.value(), pg.parameters().getFirst().get(2));

        Map<String, Object> platformOnly = joinedPolicyRow(
                JdbcDatabaseDialect.ORACLE, POLICY, null, AuthorizationScope.platform(), 100,
                RULE, "PERMIT", "*", "*", "RBAC", "permitted", "EQUALS", "true");
        ScriptedConnection oracle = connection(query(List.of(platformOnly)));
        List<AccessPolicy> global = new JdbcAccessPolicyRepository(
                dataSource(oracle.connection()), noTransaction(), JdbcDatabaseDialect.ORACLE)
                .activePolicies(AuthorizationScope.platform(), NOW.plusSeconds(30));
        assertEquals(1, global.size());
        assertFalse(oracle.sql().getFirst().contains("p.organization_id=?"));
        assertEquals(1, oracle.parameters().getFirst().size());
    }

    @Test
    void insertPolicyPersistsPolicyRulesAndConditionsInsideCurrentTransaction() {
        AccessPolicy policy = draftPolicy();
        ScriptedConnection tx = connection(update(1), batch(), batch());
        JdbcAccessPolicyRepository repository = new JdbcAccessPolicyRepository(
                dataSource(connection().connection()), transaction(tx.connection()), JdbcDatabaseDialect.POSTGRESQL);

        repository.insertPolicy(policy);

        assertEquals(3, tx.sql().size());
        assertTrue(tx.sql().get(0).contains("infranexum_iam.access_policy"));
        assertTrue(tx.sql().get(1).contains("infranexum_iam.access_policy_rule"));
        assertTrue(tx.sql().get(2).contains("infranexum_iam.access_policy_condition"));
        assertEquals(POLICY.value(), tx.parameters().get(0).get(1));
        assertEquals(ORGANIZATION.value(), tx.parameters().get(0).get(2));
        assertEquals("security.access", tx.parameters().get(0).get(3));
        assertEquals(1L, tx.parameters().get(0).get(4));
        assertEquals(1, tx.batches().get(1).size());
        assertEquals(1, tx.batches().get(2).size());
        assertEquals("REQUIRE_JUSTIFICATION", tx.batches().get(1).getFirst().get(7));
        assertEquals("SUBJECT", tx.batches().get(2).getFirst().get(3));
    }

    @Test
    void policyUniqueConflictIsSpecificButNestedRuleUniquenessRemainsPersistenceFailure() {
        AccessPolicy policy = draftPolicy();
        SQLException pgUnique = new SQLException("duplicate policy", "23505");
        ScriptedConnection policyConflict = connection(updateFailure(pgUnique));
        IdentityAccessException conflict = assertThrows(IdentityAccessException.class, () -> new JdbcAccessPolicyRepository(
                dataSource(connection().connection()), transaction(policyConflict.connection()), JdbcDatabaseDialect.POSTGRESQL)
                .insertPolicy(policy));
        assertEquals("IAM_POLICY_VERSION_CONFLICT", conflict.code());

        SQLException nestedUnique = new SQLException("duplicate rule", "23505");
        ScriptedConnection nested = connection(update(1), batchFailure(nestedUnique), batch());
        JdbcPersistenceException nestedFailure = assertThrows(JdbcPersistenceException.class, () -> new JdbcAccessPolicyRepository(
                dataSource(connection().connection()), transaction(nested.connection()), JdbcDatabaseDialect.POSTGRESQL)
                .insertPolicy(policy));
        assertEquals(nestedUnique, nestedFailure.getCause());
        assertTrue(nestedFailure.getMessage().contains("insert IAM policy rules"));

        SQLException oracleUnique = new SQLException("duplicate policy", "23000", 1);
        ScriptedConnection oracle = connection(updateFailure(oracleUnique));
        assertEquals("IAM_POLICY_VERSION_CONFLICT", assertThrows(IdentityAccessException.class, () ->
                new JdbcAccessPolicyRepository(dataSource(connection().connection()), transaction(oracle.connection()),
                        JdbcDatabaseDialect.ORACLE).insertPolicy(platformDraftPolicy())).code());
    }

    @Test
    void stateTransitionsDeprecationAndSodWritesUseCorrectNullAndScopedBindings() {
        AccessPolicy active = activePolicy();
        SeparationOfDutyConstraint constraint = new SeparationOfDutyConstraint(
                id(80), POLICY, ORGANIZATION, ROLE_TWO, ROLE_ONE, "four eyes", NOW, ACTOR);
        ScriptedConnection tx = connection(update(1), update(2), update(0), update(1));
        JdbcAccessPolicyRepository repository = new JdbcAccessPolicyRepository(
                dataSource(connection().connection()), transaction(tx.connection()), JdbcDatabaseDialect.POSTGRESQL);

        repository.updatePolicyState(active);
        repository.deprecateActiveVersions(ORGANIZATION, active.code(), active.id(), NOW.plusSeconds(60));
        repository.deprecateActiveVersions(null, "system.rbac-bridge", id(99), NOW.plusSeconds(61));
        repository.insertSeparationOfDutyConstraint(constraint);

        assertEquals("ACTIVE", tx.parameters().get(0).get(1));
        assertEquals(APPROVER.value(), tx.parameters().get(0).get(2));
        assertEquals(POLICY.value(), tx.parameters().get(0).get(8));
        assertTrue(tx.sql().get(1).contains("organization_id=?"));
        assertEquals(ORGANIZATION.value(), tx.parameters().get(1).get(4));
        assertEquals(POLICY.value(), tx.parameters().get(1).get(5));
        assertTrue(tx.sql().get(2).contains("organization_id IS NULL"));
        assertEquals(id(99).value(), tx.parameters().get(2).get(4));
        assertEquals(ROLE_ONE.value(), tx.parameters().get(3).get(4));
        assertEquals(ROLE_TWO.value(), tx.parameters().get(3).get(5));
    }

    @Test
    void sodUniqueConflictAndActiveQueriesCoverOrganizationPlatformAndScopeConsistency() {
        SeparationOfDutyConstraint constraint = new SeparationOfDutyConstraint(
                id(80), POLICY, ORGANIZATION, ROLE_ONE, ROLE_TWO, "four eyes", NOW, ACTOR);
        SQLException unique = new SQLException("duplicate sod", "23505");
        ScriptedConnection conflictConnection = connection(updateFailure(unique));
        IdentityAccessException conflict = assertThrows(IdentityAccessException.class, () ->
                new JdbcAccessPolicyRepository(dataSource(connection().connection()), transaction(conflictConnection.connection()),
                        JdbcDatabaseDialect.POSTGRESQL).insertSeparationOfDutyConstraint(constraint));
        assertEquals("IAM_SOD_CONSTRAINT_CONFLICT", conflict.code());

        Map<String, Object> row = sodRow(JdbcDatabaseDialect.POSTGRESQL, ORGANIZATION);
        ScriptedConnection pg = connection(query(List.of(row)));
        List<SeparationOfDutyConstraint> constraints = new JdbcAccessPolicyRepository(
                dataSource(pg.connection()), noTransaction(), JdbcDatabaseDialect.POSTGRESQL)
                .activeSeparationOfDutyConstraints(ORGANIZATION, ROLE_ONE, NOW.plusSeconds(30));
        assertEquals(1, constraints.size());
        assertEquals(ROLE_TWO, constraints.getFirst().conflictingRole(ROLE_ONE));
        assertTrue(pg.sql().getFirst().contains("p.organization_id=s.organization_id"));
        assertEquals(ORGANIZATION.value(), pg.parameters().getFirst().get(1));
        assertEquals(ROLE_ONE.value(), pg.parameters().getFirst().get(2));
        assertEquals(ROLE_ONE.value(), pg.parameters().getFirst().get(3));

        Map<String, Object> platform = sodRow(JdbcDatabaseDialect.ORACLE, null);
        ScriptedConnection oracle = connection(query(List.of(platform)));
        assertEquals(1, new JdbcAccessPolicyRepository(dataSource(oracle.connection()), noTransaction(), JdbcDatabaseDialect.ORACLE)
                .activeSeparationOfDutyConstraints(null, ROLE_TWO, NOW.plusSeconds(30)).size());
        assertTrue(oracle.sql().getFirst().contains("s.organization_id IS NULL"));
        assertEquals(ROLE_TWO.toString(), oracle.parameters().getFirst().get(1));
        assertEquals(ROLE_TWO.toString(), oracle.parameters().getFirst().get(2));
    }

    @Test
    void readOperationsReuseActiveTransactionAndEverySqlFailurePreservesItsCause() {
        ScriptedConnection tx = connection(query(List.of(Map.of("next_version", 2L))));
        JdbcAccessPolicyRepository inTransaction = new JdbcAccessPolicyRepository(
                failingDataSource(new SQLException("must not be used")), transaction(tx.connection()), JdbcDatabaseDialect.POSTGRESQL);
        assertEquals(2L, inTransaction.nextVersion(null, "security.access"));

        assertFailure("allocate IAM policy version", repositoryForFailure(queryFailure(new SQLException("offline", "08006")))
                , repository -> repository.nextVersion(null, "security.access"));
        assertFailure("find IAM policy", repositoryForFailure(queryFailure(new SQLException("offline", "08006")))
                , repository -> repository.findPolicy(POLICY));
        assertFailure("list IAM policies", repositoryForFailure(queryFailure(new SQLException("offline", "08006")))
                , repository -> repository.listPolicies(null, 0, 10));
        assertFailure("load active IAM policies", repositoryForFailure(queryFailure(new SQLException("offline", "08006")))
                , repository -> repository.activePolicies(AuthorizationScope.platform(), NOW));
        assertFailure("load active IAM SoD constraints", repositoryForFailure(queryFailure(new SQLException("offline", "08006")))
                , repository -> repository.activeSeparationOfDutyConstraints(null, ROLE_ONE, NOW));

        assertThrows(IllegalStateException.class, () -> new JdbcAccessPolicyRepository(
                dataSource(connection().connection()), noTransaction(), JdbcDatabaseDialect.POSTGRESQL).insertPolicy(draftPolicy()));
    }

    @Test
    void updateFailuresAndUnexpectedAffectedRowsAreWrappedWithoutLeakingSql() {
        SQLException offline = new SQLException("offline", "08006");
        assertWriteFailure("update IAM policy state", updateFailure(offline), repository -> repository.updatePolicyState(activePolicy()));
        assertWriteFailure("deprecate active IAM policy versions", updateFailure(offline), repository ->
                repository.deprecateActiveVersions(ORGANIZATION, "security.access", POLICY, NOW));
        assertWriteFailure("insert IAM SoD constraint", updateFailure(offline), repository ->
                repository.insertSeparationOfDutyConstraint(new SeparationOfDutyConstraint(
                        id(80), POLICY, ORGANIZATION, ROLE_ONE, ROLE_TWO, "four eyes", NOW, ACTOR)));
        assertWriteFailure("insert IAM policy", update(0), repository -> repository.insertPolicy(draftPolicy()));
        assertWriteFailure("update IAM policy state", update(0), repository -> repository.updatePolicyState(activePolicy()));
    }

    private static JdbcAccessPolicyRepository repositoryForFailure(StatementScript script) {
        return new JdbcAccessPolicyRepository(dataSource(connection(script).connection()), noTransaction(), JdbcDatabaseDialect.POSTGRESQL);
    }

    private static void assertFailure(String operation, JdbcAccessPolicyRepository repository,
            RepositoryAction action) {
        JdbcPersistenceException failure = assertThrows(JdbcPersistenceException.class, () -> action.run(repository));
        assertInstanceOf(SQLException.class, failure.getCause());
        assertTrue(failure.getMessage().contains(operation));
        assertFalse(failure.getMessage().toLowerCase().contains("select "));
    }

    private static void assertWriteFailure(String operation, StatementScript script, RepositoryAction action) {
        ScriptedConnection tx = connection(script);
        JdbcAccessPolicyRepository repository = new JdbcAccessPolicyRepository(
                dataSource(connection().connection()), transaction(tx.connection()), JdbcDatabaseDialect.POSTGRESQL);
        JdbcPersistenceException failure = assertThrows(JdbcPersistenceException.class, () -> action.run(repository));
        assertTrue(failure.getMessage().contains(operation));
        assertInstanceOf(SQLException.class, failure.getCause());
    }


    @Test
    void emptyVersionRuleObligationsAndConditionsAreMappedFailClosed() {
        ScriptedConnection noVersion = connection(query(List.of()));
        JdbcAccessPolicyRepository versionRepository = new JdbcAccessPolicyRepository(
                dataSource(noVersion.connection()), noTransaction(), JdbcDatabaseDialect.POSTGRESQL);
        assertThrows(JdbcPersistenceException.class, () -> versionRepository.nextVersion(null, "security.access"));

        Map<String, Object> policy = policyRow(JdbcDatabaseDialect.POSTGRESQL, POLICY, ORGANIZATION,
                AuthorizationScope.organization(ORGANIZATION), PolicyState.DRAFT);
        ScriptedConnection source = connection(
                query(List.of(policy)),
                query(List.of(ruleRow(JdbcDatabaseDialect.POSTGRESQL, RULE, 1, "PERMIT", "iam.role.read",
                        "iam.role", null, null, true))));
        AccessPolicy observed = new JdbcAccessPolicyRepository(
                dataSource(source.connection()), noTransaction(), JdbcDatabaseDialect.POSTGRESQL)
                .findPolicy(POLICY).orElseThrow();
        assertTrue(observed.rules().getFirst().obligations().isEmpty());

        ScriptedConnection corrupt = connection(
                query(List.of(policy)),
                query(List.of(ruleRow(JdbcDatabaseDialect.POSTGRESQL, RULE, 1, "PERMIT", "iam.role.read",
                        "iam.role", "", null, false))));
        assertThrows(IllegalArgumentException.class, () -> new JdbcAccessPolicyRepository(
                dataSource(corrupt.connection()), noTransaction(), JdbcDatabaseDialect.POSTGRESQL)
                .findPolicy(POLICY));
    }

    private static AccessPolicy draftPolicy() {
        return new AccessPolicy(POLICY, ORGANIZATION, "security.access", 1, OWNER, "protect role assignments", 100,
                AuthorizationScope.organization(ORGANIZATION), PolicyState.DRAFT, NOW, null, null, null, null, null,
                NOW, NOW, List.of(new PolicyRule(RULE, 1, PolicyEffect.DENY, "iam.role.assign", "iam.role",
                        List.of(new PolicyCondition(PolicyAttributeSource.SUBJECT, "department", PolicyOperator.EQUALS, "finance")),
                        Set.of(PolicyObligation.REQUIRE_JUSTIFICATION), "review")));
    }

    private static AccessPolicy platformDraftPolicy() {
        return new AccessPolicy(POLICY, null, "security.platform", 1, OWNER, "platform policy", 100,
                AuthorizationScope.platform(), PolicyState.DRAFT, NOW, null, null, null, null, null,
                NOW, NOW, List.of(new PolicyRule(RULE, 1, PolicyEffect.PERMIT, "*", "*",
                        List.of(new PolicyCondition(PolicyAttributeSource.RBAC, "permitted", PolicyOperator.EQUALS, "true")),
                        Set.of(), "")));
    }

    private static AccessPolicy activePolicy() {
        return new AccessPolicy(POLICY, ORGANIZATION, "security.access", 1, OWNER, "protect role assignments", 100,
                AuthorizationScope.organization(ORGANIZATION), PolicyState.ACTIVE, NOW, APPROVER, NOW.plusSeconds(10),
                NOW.plusSeconds(20), null, null, NOW, NOW.plusSeconds(20), draftPolicy().rules());
    }

    private static Map<String, Object> policyRow(JdbcDatabaseDialect dialect, DomainIdentifier policyId,
            DomainIdentifier organizationId, AuthorizationScope scope, PolicyState state) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", jdbcId(dialect, policyId));
        row.put("organization_id", jdbcId(dialect, organizationId));
        row.put("code", "security.access");
        row.put("policy_version", 1L);
        row.put("owner_id", jdbcId(dialect, OWNER));
        row.put("purpose", "protect role assignments");
        row.put("priority", 100);
        row.put("scope_kind", scope.kind().name());
        row.put("subdivision_id", jdbcId(dialect, scope.subdivisionId()));
        row.put("state", state.name());
        row.put("effective_from", NOW);
        if (state.ordinal() >= PolicyState.APPROVED.ordinal()) {
            row.put("approved_by", jdbcId(dialect, APPROVER));
            row.put("approved_at", NOW.plusSeconds(10));
        } else {
            row.put("approved_by", null);
            row.put("approved_at", null);
        }
        if (state == PolicyState.ACTIVE || state == PolicyState.DEPRECATED || state == PolicyState.RETIRED) {
            row.put("activated_at", NOW.plusSeconds(20));
        } else {
            row.put("activated_at", null);
        }
        row.put("deprecated_at", state == PolicyState.DEPRECATED || state == PolicyState.RETIRED ? NOW.plusSeconds(30) : null);
        row.put("retired_at", state == PolicyState.RETIRED ? NOW.plusSeconds(40) : null);
        row.put("created_at", NOW);
        row.put("updated_at", state == PolicyState.DRAFT ? NOW : NOW.plusSeconds(20));
        return row;
    }

    private static Map<String, Object> ruleRow(JdbcDatabaseDialect dialect, DomainIdentifier ruleId, int position,
            String effect, String action, String resource, String obligations, String advice, boolean withCondition) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rule_id", jdbcId(dialect, ruleId));
        row.put("position_no", position);
        row.put("effect", effect);
        row.put("action_selector", action);
        row.put("resource_type", resource);
        row.put("obligations_csv", obligations);
        row.put("advice", advice);
        if (withCondition) {
            row.put("condition_position", 1);
            row.put("source_name", "SUBJECT");
            row.put("attribute_name", "department");
            row.put("operator_name", "EQUALS");
            row.put("expected_value", "security");
        } else {
            row.put("condition_position", null);
        }
        return row;
    }

    private static Map<String, Object> joinedPolicyRow(JdbcDatabaseDialect dialect, DomainIdentifier policyId,
            DomainIdentifier organizationId, AuthorizationScope scope, int priority, DomainIdentifier ruleId,
            String effect, String action, String resource, String source, String attribute, String operator, String expected) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("policy_id", jdbcId(dialect, policyId));
        row.put("p_organization_id", jdbcId(dialect, organizationId));
        row.put("p_code", organizationId == null ? "system.rbac-bridge" : "security.access");
        row.put("p_version", 1L);
        row.put("p_owner_id", jdbcId(dialect, organizationId == null ? AccessPolicy.SYSTEM_OWNER_ID : OWNER));
        row.put("p_purpose", "active policy");
        row.put("p_priority", priority);
        row.put("p_scope_kind", scope.kind().name());
        row.put("p_subdivision_id", jdbcId(dialect, scope.subdivisionId()));
        row.put("p_state", "ACTIVE");
        row.put("p_effective_from", NOW);
        row.put("p_approved_by", jdbcId(dialect, APPROVER));
        row.put("p_approved_at", NOW.plusSeconds(10));
        row.put("p_activated_at", NOW.plusSeconds(20));
        row.put("p_deprecated_at", null);
        row.put("p_retired_at", null);
        row.put("p_created_at", NOW);
        row.put("p_updated_at", NOW.plusSeconds(20));
        row.put("rule_id", jdbcId(dialect, ruleId));
        row.put("rule_position", 1);
        row.put("rule_effect", effect);
        row.put("rule_action", action);
        row.put("rule_resource", resource);
        row.put("rule_obligations", "");
        row.put("rule_advice", null);
        row.put("condition_position", 1);
        row.put("source_name", source);
        row.put("attribute_name", attribute);
        row.put("operator_name", operator);
        row.put("expected_value", expected);
        return row;
    }

    private static Map<String, Object> sodRow(JdbcDatabaseDialect dialect, DomainIdentifier organizationId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", jdbcId(dialect, id(80)));
        row.put("policy_id", jdbcId(dialect, POLICY));
        row.put("organization_id", jdbcId(dialect, organizationId));
        row.put("first_role_id", jdbcId(dialect, ROLE_ONE));
        row.put("second_role_id", jdbcId(dialect, ROLE_TWO));
        row.put("reason", "four eyes");
        row.put("created_at", NOW);
        row.put("created_by", jdbcId(dialect, ACTOR));
        return row;
    }

    private static Object jdbcId(JdbcDatabaseDialect dialect, DomainIdentifier identifier) {
        if (identifier == null) return null;
        return dialect == JdbcDatabaseDialect.POSTGRESQL ? identifier.value() : identifier.toString();
    }

    private static DomainIdentifier id(int sequence) {
        return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(sequence)));
    }

    private static JdbcConnectionAccess noTransaction() {
        return () -> { throw new IllegalStateException("no active JDBC unit of work"); };
    }

    private static JdbcConnectionAccess transaction(Connection connection) {
        return () -> connection;
    }

    private static DataSource dataSource(Connection connection) {
        return (DataSource) Proxy.newProxyInstance(
                JdbcAccessPolicyRepositoryTest.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connection;
                    case "isWrapperFor" -> false;
                    case "unwrap" -> throw new SQLException("not a wrapper");
                    case "getLogWriter" -> null;
                    case "setLogWriter", "setLoginTimeout" -> null;
                    case "getLoginTimeout" -> 0;
                    case "getParentLogger" -> java.util.logging.Logger.getGlobal();
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static DataSource failingDataSource(SQLException failure) {
        return (DataSource) Proxy.newProxyInstance(
                JdbcAccessPolicyRepositoryTest.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) throw failure;
                    return defaultValue(method.getReturnType());
                });
    }

    private static ScriptedConnection connection(StatementScript... scripts) {
        return new ScriptedConnection(List.of(scripts));
    }

    private static StatementScript query(List<Map<String, Object>> rows) {
        return new StatementScript(rows, 1, null, null, null);
    }

    private static StatementScript queryFailure(SQLException failure) {
        return new StatementScript(List.of(), 1, failure, null, null);
    }

    private static StatementScript update(int count) {
        return new StatementScript(List.of(), count, null, null, null);
    }

    private static StatementScript updateFailure(SQLException failure) {
        return new StatementScript(List.of(), 1, null, failure, null);
    }

    private static StatementScript batch() {
        return new StatementScript(List.of(), 1, null, null, null);
    }

    private static StatementScript batchFailure(SQLException failure) {
        return new StatementScript(List.of(), 1, null, null, failure);
    }

    private record StatementScript(
            List<Map<String, Object>> rows,
            int updateCount,
            SQLException queryFailure,
            SQLException updateFailure,
            SQLException batchFailure) {}

    private static final class ScriptedConnection {
        private final Queue<StatementScript> scripts;
        private final List<String> sql = new ArrayList<>();
        private final List<Map<Integer, Object>> parameters = new ArrayList<>();
        private final List<List<Map<Integer, Object>>> batches = new ArrayList<>();
        private final Connection connection;

        ScriptedConnection(List<StatementScript> scripts) {
            this.scripts = new ArrayDeque<>(scripts);
            this.connection = (Connection) Proxy.newProxyInstance(
                    JdbcAccessPolicyRepositoryTest.class.getClassLoader(), new Class<?>[] {Connection.class},
                    this::invokeConnection);
        }

        Connection connection() { return connection; }
        List<String> sql() { return sql; }
        List<Map<Integer, Object>> parameters() { return parameters; }
        List<List<Map<Integer, Object>>> batches() { return batches; }

        private Object invokeConnection(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "prepareStatement" -> prepare(String.valueOf(args[0]));
                case "close", "commit", "rollback", "setAutoCommit", "setReadOnly" -> null;
                case "isClosed" -> false;
                case "getAutoCommit" -> false;
                case "isWrapperFor" -> false;
                case "unwrap" -> throw new SQLException("not a wrapper");
                case "toString" -> "ScriptedConnection";
                default -> defaultValue(method.getReturnType());
            };
        }

        private PreparedStatement prepare(String statementSql) throws SQLException {
            if (scripts.isEmpty()) throw new SQLException("unexpected SQL: " + statementSql);
            StatementScript script = scripts.remove();
            int statementIndex = sql.size();
            sql.add(statementSql);
            Map<Integer, Object> current = new LinkedHashMap<>();
            parameters.add(current);
            List<Map<Integer, Object>> statementBatches = new ArrayList<>();
            batches.add(statementBatches);
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setObject", "setString", "setInt", "setLong" -> {
                    current.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "setNull" -> {
                    current.put((Integer) args[0], null);
                    yield null;
                }
                case "addBatch" -> {
                    statementBatches.add(new LinkedHashMap<>(current));
                    yield null;
                }
                case "executeQuery" -> {
                    if (script.queryFailure() != null) throw script.queryFailure();
                    yield resultSet(script.rows());
                }
                case "executeUpdate" -> {
                    if (script.updateFailure() != null) throw script.updateFailure();
                    yield script.updateCount();
                }
                case "executeBatch" -> {
                    if (script.batchFailure() != null) throw script.batchFailure();
                    int[] counts = new int[statementBatches.size()];
                    java.util.Arrays.fill(counts, 1);
                    yield counts;
                }
                case "close", "clearParameters", "clearBatch" -> null;
                case "isClosed" -> false;
                case "getConnection" -> connection;
                case "toString" -> "ScriptedPreparedStatement[" + statementIndex + "]";
                default -> defaultValue(method.getReturnType());
            };
            return (PreparedStatement) Proxy.newProxyInstance(
                    JdbcAccessPolicyRepositoryTest.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, handler);
        }
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        InvocationHandler handler = new InvocationHandler() {
            private int index = -1;

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                return switch (method.getName()) {
                    case "next" -> ++index < rows.size();
                    case "getObject" -> current().get(String.valueOf(args[0]));
                    case "getString" -> {
                        Object value = current().get(String.valueOf(args[0]));
                        yield value == null ? null : value.toString();
                    }
                    case "getLong" -> ((Number) current().get(String.valueOf(args[0]))).longValue();
                    case "getInt" -> ((Number) current().get(String.valueOf(args[0]))).intValue();
                    case "close" -> null;
                    case "isClosed" -> false;
                    case "toString" -> "ScriptedResultSet";
                    default -> defaultValue(method.getReturnType());
                };
            }

            private Map<String, Object> current() throws SQLException {
                if (index < 0 || index >= rows.size()) throw new SQLException("result set cursor is not on a row");
                return rows.get(index);
            }
        };
        return (ResultSet) Proxy.newProxyInstance(
                JdbcAccessPolicyRepositoryTest.class.getClassLoader(), new Class<?>[] {ResultSet.class}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        throw new IllegalArgumentException("unsupported primitive: " + type);
    }

    @FunctionalInterface
    private interface RepositoryAction {
        void run(JdbcAccessPolicyRepository repository);
    }
}
