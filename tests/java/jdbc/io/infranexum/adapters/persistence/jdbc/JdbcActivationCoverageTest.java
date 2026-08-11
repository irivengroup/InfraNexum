package io.infranexum.adapters.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.entitlements.EntitlementRuntimePhase;
import io.infranexum.core.entitlements.EntitlementRuntimeStatus;
import io.infranexum.core.entitlements.InstallationIdentity;
import io.infranexum.core.entitlements.IntegrityProof;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Regression coverage for durable activation/revocation JDBC read, rollback and corruption branches. */
class JdbcActivationCoverageTest {
    private static final Instant NOW = Instant.parse("2026-08-11T14:30:00Z");

    @Test
    void readsPersistedIdentitySequenceProofStateAndManifestAcrossJdbcRepresentations() {
        InstallationIdentity expectedIdentity = identity(1);
        DomainIdentifier activationId = id(2);
        Map<String, List<Map<String, Object>>> rows = new HashMap<>();
        rows.put("SELECT installation_id,fingerprint_version", List.of(Map.of(
                "installation_id", expectedIdentity.installationId().value(),
                "fingerprint_version", expectedIdentity.fingerprintVersion(),
                "fingerprint", expectedIdentity.fingerprint(),
                "created_at", OffsetDateTime.ofInstant(NOW.minusSeconds(100), ZoneOffset.UTC))));
        rows.put("SELECT max_activation_sequence", List.of(Map.of(
                "max_activation_sequence", 7L,
                "accepted_activation_id", activationId.value())));
        rows.put("SELECT installation_id,fingerprint,evaluation_started_at", List.of(proofRow(expectedIdentity, activationId)));
        rows.put("SELECT profile,allocation_tier", List.of(Map.ofEntries(
                Map.entry("profile", "PRO"),
                Map.entry("allocation_tier", "ADVANCED"),
                Map.entry("evaluation_started_at", NullValue.INSTANCE),
                Map.entry("last_reliable_at", Timestamp.from(NOW.plusSeconds(1))),
                Map.entry("time_generation", 3L),
                Map.entry("max_activation_sequence", 7L),
                Map.entry("accepted_activation_id", activationId.toString()),
                Map.entry("activation_state", "ACTIVE"),
                Map.entry("valid_until", NOW.plusSeconds(3600)),
                Map.entry("grace_until", OffsetDateTime.ofInstant(NOW.plusSeconds(7200), ZoneOffset.UTC)),
                Map.entry("updated_at", Timestamp.from(NOW.plusSeconds(2))))));
        rows.put("SELECT jsonb_build_object", List.of(Map.of("#1", new StringReader("{\"schema\":\"ok\"}"))));

        JdbcActivationOperationalRepository repository = new JdbcActivationOperationalRepository(
                new RoutingQueryDataSource(rows), JdbcDatabaseDialect.POSTGRESQL);

        assertEquals(expectedIdentity, repository.installationIdentity().orElseThrow());
        var sequence = repository.acceptedSequence(expectedIdentity);
        assertEquals(7, sequence.value());
        assertEquals(activationId, sequence.activationId());
        IntegrityProof proof = repository.databaseProof(expectedIdentity).orElseThrow();
        assertEquals(expectedIdentity.installationId(), proof.installationId());
        var state = repository.entitlementState(expectedIdentity).orElseThrow();
        assertEquals(InstallationProfile.PRO, state.profile());
        assertEquals(AllocationTier.ADVANCED, state.allocationTier());
        assertNull(state.evaluationStartedAt());
        assertEquals(EntitlementRuntimePhase.ACTIVE, state.phase());
        assertEquals("{\"schema\":\"ok\"}", repository.acceptedManifestDocument(expectedIdentity).orElseThrow());
    }

    @Test
    void readOperationsRejectDuplicateIdentityAndCorruptRuntimeRows() {
        InstallationIdentity identity = identity(10);
        Map<String, Object> identityRow = Map.of(
                "installation_id", identity.installationId().toString(),
                "fingerprint_version", identity.fingerprintVersion(),
                "fingerprint", identity.fingerprint(),
                "created_at", NOW);
        JdbcActivationOperationalRepository duplicate = new JdbcActivationOperationalRepository(
                new RoutingQueryDataSource(Map.of("SELECT installation_id,fingerprint_version", List.of(identityRow, identityRow))),
                JdbcDatabaseDialect.ORACLE);
        JdbcPersistenceException duplicateFailure = assertThrows(
                JdbcPersistenceException.class, duplicate::installationIdentity);
        assertInstanceOf(SQLException.class, duplicateFailure.getCause());

        Map<String, Object> corruptState = new HashMap<>();
        corruptState.put("profile", "NOT_A_PROFILE");
        corruptState.put("allocation_tier", "STANDARD");
        corruptState.put("evaluation_started_at", NOW);
        corruptState.put("last_reliable_at", NOW);
        corruptState.put("time_generation", 1L);
        corruptState.put("max_activation_sequence", 0L);
        corruptState.put("accepted_activation_id", null);
        corruptState.put("activation_state", "EVALUATION");
        corruptState.put("valid_until", null);
        corruptState.put("grace_until", null);
        corruptState.put("updated_at", NOW);
        JdbcActivationOperationalRepository corrupt = new JdbcActivationOperationalRepository(
                new RoutingQueryDataSource(Map.of("SELECT profile,allocation_tier", List.of(corruptState))),
                JdbcDatabaseDialect.POSTGRESQL);
        JdbcPersistenceException corruptFailure = assertThrows(
                JdbcPersistenceException.class, () -> corrupt.entitlementState(identity));
        assertInstanceOf(IllegalArgumentException.class, corruptFailure.getCause());
    }

    @Test
    void acceptedSequenceMapsNullAndUuidActivationIdentifiers() {
        InstallationIdentity identity = identity(15);
        JdbcActivationOperationalRepository none = new JdbcActivationOperationalRepository(
                new RoutingQueryDataSource(Map.of("SELECT max_activation_sequence", List.of(Map.of(
                        "max_activation_sequence", 0L,
                        "accepted_activation_id", NullValue.INSTANCE)))),
                JdbcDatabaseDialect.POSTGRESQL);
        var empty = none.acceptedSequence(identity);
        assertEquals(0, empty.value());
        assertNull(empty.activationId());

        DomainIdentifier activation = id(16);
        JdbcActivationOperationalRepository uuid = new JdbcActivationOperationalRepository(
                new RoutingQueryDataSource(Map.of("SELECT max_activation_sequence", List.of(Map.of(
                        "max_activation_sequence", 8L,
                        "accepted_activation_id", activation.value())))),
                JdbcDatabaseDialect.POSTGRESQL);
        var accepted = uuid.acceptedSequence(identity);
        assertEquals(8, accepted.value());
        assertEquals(activation, accepted.activationId());
    }

    @Test
    void updateRuntimeStateFailsClosedOnMissingRowAndPreservesRollbackFailure() {
        InstallationIdentity identity = identity(20);
        IntegrityProof proof = proof(identity, NOW, 2);
        EntitlementRuntimeStatus status = new EntitlementRuntimeStatus(
                identity.installationId(), InstallationProfile.LITE, AllocationTier.STANDARD,
                EntitlementRuntimePhase.EVALUATION, NOW, NOW.minusSeconds(10),
                NOW.plusSeconds(100), NOW.plusSeconds(200), null, null, 0, null,
                Set.of(), Map.of(), true, true);

        TransactionDataSource missing = new TransactionDataSource();
        missing.updateByMarker.put("UPDATE core_entitlement_state", 0);
        JdbcPersistenceException missingFailure = assertThrows(JdbcPersistenceException.class,
                () -> new JdbcActivationOperationalRepository(missing, JdbcDatabaseDialect.POSTGRESQL)
                        .updateRuntimeState(identity, status, proof, NOW));
        assertInstanceOf(SQLException.class, missingFailure.getCause());
        assertTrue(missing.rolledBack);
        assertTrue(missing.autoCommitRestored);

        TransactionDataSource rollbackFails = new TransactionDataSource();
        rollbackFails.updateFailureMarker = "UPDATE core_entitlement_state";
        rollbackFails.rollbackFailure = new SQLException("rollback unavailable");
        JdbcPersistenceException wrapped = assertThrows(JdbcPersistenceException.class,
                () -> new JdbcActivationOperationalRepository(rollbackFails, JdbcDatabaseDialect.POSTGRESQL)
                        .updateRuntimeState(identity, status, proof, NOW));
        assertEquals(1, wrapped.getCause().getSuppressed().length);
        assertEquals("rollback unavailable", wrapped.getCause().getSuppressed()[0].getMessage());
    }

    @Test
    void revocationRegistryCoversMissingFutureEffectiveAndActiveRowsForBothKinds() {
        DomainIdentifier activationId = id(30);
        SequencedQueryDataSource data = new SequencedQueryDataSource(List.of(
                List.of(),
                List.of(Map.of("effective_at", NOW.plusSeconds(1))),
                List.of(Map.of("effective_at", NOW.minusSeconds(1))),
                List.of(Map.of("effective_at", Timestamp.from(NOW.minusSeconds(5))))));
        JdbcRevocationRegistry registry = new JdbcRevocationRegistry(data, JdbcDatabaseDialect.ORACLE);
        assertFalse(registry.isKeyRevoked("key-missing", NOW));
        assertFalse(registry.isKeyRevoked("key-future", NOW));
        assertTrue(registry.isKeyRevoked("key-active", NOW));
        assertTrue(registry.isActivationRevoked(activationId, NOW));
        assertEquals(List.of("KEY", "KEY", "KEY", "ACTIVATION"), data.boundTypes);
        assertEquals(activationId.toString(), data.boundKeys.get(3));

        assertThrows(NullPointerException.class, () -> registry.isKeyRevoked(null, NOW));
        assertThrows(NullPointerException.class, () -> registry.isActivationRevoked(null, NOW));
        assertThrows(NullPointerException.class, () -> registry.isKeyRevoked("key", null));
        assertThrows(NullPointerException.class, () -> new JdbcRevocationRegistry(null, JdbcDatabaseDialect.POSTGRESQL));
        assertThrows(NullPointerException.class, () -> new JdbcRevocationRegistry(data, null));
    }


    @Test
    void activationReadFailuresAndManifestIoErrorsRemainFailClosed() {
        InstallationIdentity identity = identity(40);
        SQLException databaseFailure = new SQLException("database unavailable");
        DataSource failing = failingDataSource(databaseFailure);
        JdbcActivationOperationalRepository repository =
                new JdbcActivationOperationalRepository(failing, JdbcDatabaseDialect.POSTGRESQL);

        assertEquals(databaseFailure, assertThrows(JdbcPersistenceException.class, repository::installationIdentity).getCause());
        assertEquals(databaseFailure, assertThrows(JdbcPersistenceException.class, () -> repository.acceptedSequence(identity)).getCause());
        assertEquals(databaseFailure, assertThrows(JdbcPersistenceException.class, () -> repository.databaseProof(identity)).getCause());
        assertEquals(databaseFailure, assertThrows(JdbcPersistenceException.class, () -> repository.entitlementState(identity)).getCause());
        assertEquals(databaseFailure, assertThrows(JdbcPersistenceException.class, () -> repository.acceptedManifestDocument(identity)).getCause());
        assertThrows(NullPointerException.class, () -> new JdbcActivationOperationalRepository(null, JdbcDatabaseDialect.POSTGRESQL));
        assertThrows(NullPointerException.class, () -> new JdbcActivationOperationalRepository(failing, null));

        Reader brokenReader = new Reader() {
            @Override public int read(char[] buffer, int offset, int length) throws IOException {
                throw new IOException("stream failure");
            }
            @Override public void close() {}
        };
        JdbcActivationOperationalRepository brokenDocument = new JdbcActivationOperationalRepository(
                new RoutingQueryDataSource(Map.of("SELECT JSON_OBJECT", List.of(Map.of("#1", brokenReader)))),
                JdbcDatabaseDialect.ORACLE);
        JdbcPersistenceException streamFailure = assertThrows(
                JdbcPersistenceException.class, () -> brokenDocument.acceptedManifestDocument(identity));
        assertInstanceOf(IOException.class, streamFailure.getCause());
    }

    @Test
    void transactionInfrastructureFailuresAreWrappedAndAutoCommitIsAlwaysRestored() {
        InstallationIdentity identity = identity(50);
        IntegrityProof proof = proof(identity, NOW, 1);
        EntitlementRuntimeStatus status = new EntitlementRuntimeStatus(
                identity.installationId(), InstallationProfile.LITE, AllocationTier.STANDARD,
                EntitlementRuntimePhase.EVALUATION, NOW, NOW.minusSeconds(10),
                NOW.plusSeconds(100), NOW.plusSeconds(200), null, null, 0, null,
                Set.of(), Map.of(), true, true);

        TransactionDataSource commitFails = new TransactionDataSource();
        commitFails.commitFailure = new SQLException("commit unavailable");
        JdbcPersistenceException commitFailure = assertThrows(JdbcPersistenceException.class,
                () -> new JdbcActivationOperationalRepository(commitFails, JdbcDatabaseDialect.ORACLE)
                        .updateRuntimeState(identity, status, proof, NOW));
        assertEquals("commit unavailable", commitFailure.getCause().getMessage());
        assertTrue(commitFails.rolledBack);
        assertTrue(commitFails.autoCommitRestored);

        TransactionDataSource restoreFails = new TransactionDataSource();
        restoreFails.restoreFailure = new SQLException("autocommit restore failed");
        JdbcPersistenceException restoreFailure = assertThrows(JdbcPersistenceException.class,
                () -> new JdbcActivationOperationalRepository(restoreFails, JdbcDatabaseDialect.POSTGRESQL)
                        .initializeLite(identity, proof, NOW));
        assertEquals("autocommit restore failed", restoreFailure.getCause().getMessage());

        TransactionDataSource setupFails = new TransactionDataSource();
        setupFails.disableAutoCommitFailure = new SQLException("cannot begin transaction");
        JdbcPersistenceException setupFailure = assertThrows(JdbcPersistenceException.class,
                () -> new JdbcActivationOperationalRepository(setupFails, JdbcDatabaseDialect.POSTGRESQL)
                        .initializeLite(identity, proof, NOW));
        assertEquals("cannot begin transaction", setupFailure.getCause().getMessage());

        TransactionDataSource runtimeFails = new TransactionDataSource();
        runtimeFails.runtimeFailureMarker = "core_entitlement_state";
        RuntimeException runtimeFailure = assertThrows(RuntimeException.class,
                () -> new JdbcActivationOperationalRepository(runtimeFails, JdbcDatabaseDialect.POSTGRESQL)
                        .initializeLite(identity, proof, NOW));
        assertEquals("forced runtime failure", runtimeFailure.getMessage());
        assertTrue(runtimeFails.rolledBack);
        assertTrue(runtimeFails.autoCommitRestored);
    }

    private static Map<String, Object> proofRow(InstallationIdentity identity, DomainIdentifier ignored) {
        return Map.of(
                "installation_id", identity.installationId().toString(),
                "fingerprint", identity.fingerprint(),
                "evaluation_started_at", NOW.minusSeconds(100),
                "last_reliable_at", NOW,
                "generation", 2L,
                "mac_base64", Base64.getEncoder().encodeToString(new byte[32]));
    }

    private static InstallationIdentity identity(int sequence) {
        return new InstallationIdentity(id(sequence), "v1", "%064x".formatted(sequence), NOW.minusSeconds(100));
    }

    private static IntegrityProof proof(InstallationIdentity identity, Instant reliableAt, long generation) {
        return new IntegrityProof(identity.installationId(), identity.fingerprint(), NOW.minusSeconds(100),
                reliableAt, generation, Base64.getEncoder().encodeToString(new byte[32]));
    }

    private static DomainIdentifier id(int sequence) {
        return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(sequence)));
    }

    private enum NullValue { INSTANCE }

    private static final class RoutingQueryDataSource implements DataSource {
        private final Map<String, List<Map<String, Object>>> rows;
        private RoutingQueryDataSource(Map<String, List<Map<String, Object>>> rows) { this.rows = rows; }
        @Override public Connection getConnection() { return connection(sql -> rows.entrySet().stream()
                .filter(entry -> sql.contains(entry.getKey())).findFirst().map(Map.Entry::getValue).orElse(List.of())); }
        @Override public Connection getConnection(String username, String password) { return getConnection(); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static final class SequencedQueryDataSource implements DataSource {
        private final List<List<Map<String, Object>>> resultSets;
        private int query;
        private final List<String> boundTypes = new ArrayList<>();
        private final List<String> boundKeys = new ArrayList<>();
        private SequencedQueryDataSource(List<List<Map<String, Object>>> resultSets) { this.resultSets = resultSets; }
        @Override public Connection getConnection() {
            return connection(sql -> resultSets.get(query++), (index, value) -> {
                if (index == 1) boundTypes.add(String.valueOf(value));
                if (index == 2) boundKeys.add(String.valueOf(value));
            });
        }
        @Override public Connection getConnection(String username, String password) { return getConnection(); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static final class TransactionDataSource implements DataSource {
        private final Map<String, Integer> updateByMarker = new HashMap<>();
        private String updateFailureMarker;
        private SQLException rollbackFailure;
        private SQLException commitFailure;
        private SQLException restoreFailure;
        private SQLException disableAutoCommitFailure;
        private String runtimeFailureMarker;
        private boolean rolledBack;
        private boolean autoCommitRestored;
        @Override public Connection getConnection() {
            InvocationHandler handler = new InvocationHandler() {
                private boolean autoCommit = true;
                @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    return switch (method.getName()) {
                        case "getAutoCommit" -> autoCommit;
                        case "setAutoCommit" -> {
                            boolean requested = (Boolean) args[0];
                            if (!requested && disableAutoCommitFailure != null) throw disableAutoCommitFailure;
                            if (requested && restoreFailure != null) throw restoreFailure;
                            autoCommit = requested;
                            if (autoCommit) autoCommitRestored = true;
                            yield null;
                        }
                        case "prepareStatement" -> updateStatement((String) args[0]);
                        case "commit" -> {
                            if (commitFailure != null) throw commitFailure;
                            yield null;
                        }
                        case "close" -> null;
                        case "rollback" -> {
                            rolledBack = true;
                            if (rollbackFailure != null) throw rollbackFailure;
                            yield null;
                        }
                        case "isClosed" -> false;
                        default -> defaultValue(method.getReturnType());
                    };
                }
            };
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, handler);
        }
        private PreparedStatement updateStatement(String sql) {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "executeUpdate" -> {
                    if (runtimeFailureMarker != null && sql.contains(runtimeFailureMarker)) {
                        throw new RuntimeException("forced runtime failure");
                    }
                    if (updateFailureMarker != null && sql.contains(updateFailureMarker)) {
                        throw new SQLException("forced update failure");
                    }
                    yield updateByMarker.entrySet().stream().filter(entry -> sql.contains(entry.getKey()))
                            .findFirst().map(Map.Entry::getValue).orElse(1);
                }
                case "setString", "setLong", "setInt", "setObject", "setNull", "setCharacterStream", "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, handler);
        }
        @Override public Connection getConnection(String username, String password) { return getConnection(); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }


    private static DataSource failingDataSource(SQLException failure) {
        return new DataSource() {
            @Override public Connection getConnection() throws SQLException { throw failure; }
            @Override public Connection getConnection(String username, String password) throws SQLException { throw failure; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) {}
            @Override public void setLoginTimeout(int seconds) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
            @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }

    @FunctionalInterface
    private interface RowProvider { List<Map<String, Object>> rows(String sql); }
    @FunctionalInterface
    private interface Binder { void bind(int index, Object value); }

    private static Connection connection(RowProvider provider) { return connection(provider, (index, value) -> {}); }

    private static Connection connection(RowProvider provider, Binder binder) {
        InvocationHandler connection = (proxy, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> statement(provider.rows((String) args[0]), binder);
            case "close" -> null;
            case "isClosed" -> false;
            default -> defaultValue(method.getReturnType());
        };
        return (Connection) Proxy.newProxyInstance(JdbcActivationCoverageTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, connection);
    }

    private static PreparedStatement statement(List<Map<String, Object>> rows, Binder binder) {
        InvocationHandler statement = (proxy, method, args) -> switch (method.getName()) {
            case "executeQuery" -> resultSet(rows);
            case "setString", "setObject", "setLong", "setInt", "setNull" -> {
                binder.bind((Integer) args[0], args[1]);
                yield null;
            }
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        };
        return (PreparedStatement) Proxy.newProxyInstance(JdbcActivationCoverageTest.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, statement);
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        InvocationHandler handler = new InvocationHandler() {
            int cursor = -1;
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                return switch (method.getName()) {
                    case "next" -> ++cursor < rows.size();
                    case "getObject" -> unwrap(value(args[0]));
                    case "getString" -> {
                        Object raw = unwrap(value(args[0]));
                        yield raw == null ? null : raw.toString();
                    }
                    case "getLong" -> ((Number) unwrap(value(args[0]))).longValue();
                    case "getCharacterStream" -> {
                        Object raw = unwrap(value(args[0]));
                        yield raw instanceof Reader reader ? reader : raw == null ? null : new StringReader(raw.toString());
                    }
                    case "close" -> null;
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                };
            }
            private Object value(Object key) {
                Map<String, Object> row = rows.get(cursor);
                if (key instanceof Integer position) return row.get("#" + position);
                return row.get(String.valueOf(key));
            }
        };
        return (ResultSet) Proxy.newProxyInstance(JdbcActivationCoverageTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, handler);
    }

    private static Object unwrap(Object value) { return value == NullValue.INSTANCE ? null : value; }

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
        throw new IllegalArgumentException("unsupported primitive " + type);
    }
}
