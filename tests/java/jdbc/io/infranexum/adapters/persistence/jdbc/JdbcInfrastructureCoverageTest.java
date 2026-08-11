package io.infranexum.adapters.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.capabilities.QuotaAllocationPlan;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.entitlements.ActivationManifest;
import io.infranexum.core.entitlements.ActivationManifestPayload;
import io.infranexum.core.entitlements.ActivationUsageState;
import io.infranexum.core.entitlements.ActivationVerificationResult;
import io.infranexum.core.entitlements.CustomerIdentity;
import io.infranexum.core.entitlements.EntitlementRuntimePhase;
import io.infranexum.core.entitlements.EntitlementRuntimeStatus;
import io.infranexum.core.entitlements.InstallationIdentity;
import io.infranexum.core.entitlements.IntegrityProof;
import io.infranexum.core.entitlements.ManifestInstallation;
import io.infranexum.core.events.EventType;
import io.infranexum.core.events.InboxKey;
import io.infranexum.core.events.InboxReservation;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Deterministic branch contracts for JDBC infrastructure that do not require a live database. */
class JdbcInfrastructureCoverageTest {
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

    @Test
    void independentIntegrityProofStoreRoundTripsDeletesAndRejectsCorruption() throws Exception {
        Path directory = Files.createTempDirectory("infranexum-proof-");
        try {
            InstallationIdentity identity = identity(1);
            IntegrityProof proof = proof(identity, NOW.plusSeconds(1), 1);
            FileIntegrityProofStore store = new FileIntegrityProofStore(directory);
            assertTrue(store.load(identity).isEmpty());
            store.store(proof);
            assertEquals(proof, store.load(identity).orElseThrow());
            Path file = directory.resolve(identity.installationId() + ".proof");

            Files.write(file, new byte[] {1}, StandardOpenOption.APPEND);
            assertThrows(JdbcPersistenceException.class, () -> store.load(identity));

            store.store(proof);
            byte[] bytes = Files.readAllBytes(file);
            ByteBuffer.wrap(bytes).putInt(0, 0x01020304);
            Files.write(file, bytes);
            assertThrows(JdbcPersistenceException.class, () -> store.load(identity));

            store.store(proof);
            bytes = Files.readAllBytes(file);
            ByteBuffer.wrap(bytes).putInt(4, 99);
            Files.write(file, bytes);
            assertThrows(JdbcPersistenceException.class, () -> store.load(identity));

            store.store(proof);
            store.delete(identity);
            assertTrue(store.load(identity).isEmpty());
            store.delete(identity);
        } finally {
            deleteRecursively(directory);
        }

        Path notDirectory = Files.createTempFile("infranexum-proof-parent-", ".tmp");
        try {
            FileIntegrityProofStore impossible = new FileIntegrityProofStore(notDirectory);
            assertThrows(JdbcPersistenceException.class, () -> impossible.store(proof(identity(2), NOW, 1)));
        } finally {
            Files.deleteIfExists(notDirectory);
        }
    }

    @Test
    void temporalMappingAcceptsAllSupportedJdbcRepresentationsAndRejectsOthers() throws Exception {
        RecordingStatement statement = new RecordingStatement();
        PreparedStatement prepared = statement.proxy();
        JdbcTemporal.bindInstant(prepared, 1, null);
        assertEquals(java.sql.Types.TIMESTAMP_WITH_TIMEZONE, statement.values.get(1));
        JdbcTemporal.bindInstant(prepared, 2, NOW);
        assertEquals(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), statement.values.get(2));

        assertNull(JdbcTemporal.readNullable(resultSet(Map.of("value", NullValue.INSTANCE)), "value"));
        assertEquals(NOW, JdbcTemporal.readNullable(resultSet(Map.of("value", NOW)), "value"));
        assertEquals(NOW, JdbcTemporal.readNullable(
                resultSet(Map.of("value", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))), "value"));
        assertEquals(NOW, JdbcTemporal.readNullable(resultSet(Map.of("value", Timestamp.from(NOW))), "value"));
        assertThrows(SQLException.class, () -> JdbcTemporal.readNullable(resultSet(Map.of("value", 12)), "value"));
        assertThrows(SQLException.class,
                () -> JdbcTemporal.readRequired(resultSet(Map.of("value", NullValue.INSTANCE)), "value"));
    }

    @Test
    void databaseDialectsCoverIdentifiersSqlAndUniqueViolationContracts() throws Exception {
        DomainIdentifier identifier = id(10);
        RecordingStatement pgStatement = new RecordingStatement();
        JdbcDatabaseDialect.POSTGRESQL.bindIdentifier(pgStatement.proxy(), 1, identifier);
        assertEquals(identifier.value(), pgStatement.values.get(1));
        JdbcDatabaseDialect.POSTGRESQL.bindNullableIdentifier(pgStatement.proxy(), 2, null);
        assertEquals(java.sql.Types.OTHER, pgStatement.values.get(2));
        JdbcDatabaseDialect.POSTGRESQL.bindNullableIdentifier(pgStatement.proxy(), 3, identifier);
        assertEquals(identifier.value(), pgStatement.values.get(3));

        RecordingStatement oracleStatement = new RecordingStatement();
        JdbcDatabaseDialect.ORACLE.bindIdentifier(oracleStatement.proxy(), 1, identifier);
        assertEquals(identifier.toString(), oracleStatement.values.get(1));
        JdbcDatabaseDialect.ORACLE.bindNullableIdentifier(oracleStatement.proxy(), 2, null);
        assertEquals(java.sql.Types.CHAR, oracleStatement.values.get(2));

        assertEquals(identifier, JdbcDatabaseDialect.POSTGRESQL.readIdentifier(
                resultSet(Map.of("id", identifier.value())), "id"));
        assertEquals(identifier, JdbcDatabaseDialect.ORACLE.readIdentifier(
                resultSet(Map.of("id", identifier.toString().toUpperCase(java.util.Locale.ROOT))), "id"));
        assertThrows(SQLException.class, () -> JdbcDatabaseDialect.POSTGRESQL.readIdentifier(
                resultSet(Map.of("id", 42)), "id"));
        assertTrue(JdbcDatabaseDialect.POSTGRESQL.isUniqueViolation(new SQLException("duplicate", "23505")));
        assertFalse(JdbcDatabaseDialect.POSTGRESQL.isUniqueViolation(new SQLException("other", "22000")));
        assertTrue(JdbcDatabaseDialect.ORACLE.isUniqueViolation(new SQLException("duplicate", "23000", 1)));
        assertFalse(JdbcDatabaseDialect.ORACLE.isUniqueViolation(new SQLException("other", "23000", 2)));

        assertTrue(JdbcDatabaseDialect.POSTGRESQL.insertOutboxSql().contains("JSONB"));
        assertTrue(JdbcDatabaseDialect.ORACLE.insertOutboxSql().contains("INFRANEXUM_CORE_OUTBOX_EVENT"));
        assertTrue(JdbcDatabaseDialect.POSTGRESQL.claimReturningSql().contains("SKIP LOCKED"));
        assertThrows(UnsupportedOperationException.class, JdbcDatabaseDialect.ORACLE::claimReturningSql);
        assertTrue(JdbcDatabaseDialect.POSTGRESQL.supportsClaimReturning());
        assertFalse(JdbcDatabaseDialect.ORACLE.supportsClaimReturning());
        for (JdbcDatabaseDialect dialect : JdbcDatabaseDialect.values()) {
            assertFalse(dialect.selectClaimCandidatesSql().isBlank());
            assertFalse(dialect.claimOneSql().isBlank());
            assertFalse(dialect.publishSql().isBlank());
            assertFalse(dialect.selectLeaseSql().isBlank());
            assertFalse(dialect.failSql().isBlank());
            assertFalse(dialect.completeInboxSql().isBlank());
            assertFalse(dialect.inboxStatusSql().isBlank());
        }
    }

    @Test
    void oracleActivationRepositoryExercisesOracleSqlBranchesWithoutExternalDatabase() {
        NoOpDataSource dataSource = new NoOpDataSource();
        JdbcActivationOperationalRepository repository =
                new JdbcActivationOperationalRepository(dataSource, JdbcDatabaseDialect.ORACLE);
        InstallationIdentity identity = identity(20);
        IntegrityProof proof = proof(identity, NOW.plusSeconds(1), 1);

        assertTrue(repository.installationIdentity().isEmpty());
        assertEquals(0, repository.acceptedSequence(identity).value());
        assertTrue(repository.databaseProof(identity).isEmpty());
        assertTrue(repository.entitlementState(identity).isEmpty());
        assertTrue(repository.acceptedManifestDocument(identity).isEmpty());

        repository.initializeLite(identity, proof, NOW.plusSeconds(2));
        EntitlementRuntimeStatus status = new EntitlementRuntimeStatus(
                identity.installationId(), InstallationProfile.LITE, AllocationTier.STANDARD,
                EntitlementRuntimePhase.EVALUATION, NOW.plusSeconds(3), NOW,
                NOW.plusSeconds(10), NOW.plusSeconds(20), null, null, 0, null,
                Set.of(), Map.of(), true, true);
        repository.updateRuntimeState(identity, status, proof(identity, NOW.plusSeconds(3), 2), NOW.plusSeconds(3));

        ActivationManifestPayload payload = paidPayload(identity, 1);
        ActivationManifest manifest = new ActivationManifest(payload, signature());
        ActivationVerificationResult result = new ActivationVerificationResult(
                ActivationUsageState.ACTIVE, payload,
                new QuotaAllocationPlan("2.0.0-draft.20", InstallationProfile.PRO, AllocationTier.STANDARD,
                        Map.of("rsot.managed_hosts.max", 10L)),
                payload.capabilities(), payload.validUntil().plus(30, ChronoUnit.DAYS));
        repository.accept(identity, manifest, result, proof(identity, NOW.plusSeconds(4), 3), NOW.plusSeconds(4));

        assertTrue(dataSource.sql.stream().anyMatch(value -> value.contains("MERGE INTO CORE_ENTITLEMENT_STATE")));
        assertTrue(dataSource.sql.stream().anyMatch(value -> value.contains("MERGE INTO CORE_ENTITLEMENT_INTEGRITY_PROOF")));
        assertTrue(dataSource.sql.stream().anyMatch(value -> value.contains("JSON_OBJECT")));
    }


    @Test
    void acceptedManifestDocumentRejectsNullAndOversizedLobValues() {
        InstallationIdentity identity = identity(30);
        JdbcActivationOperationalRepository nullDocument = new JdbcActivationOperationalRepository(
                queryDataSource(characterResultSet(null)), JdbcDatabaseDialect.ORACLE);
        JdbcPersistenceException nullFailure = assertThrows(JdbcPersistenceException.class,
                () -> nullDocument.acceptedManifestDocument(identity));
        assertInstanceOf(SQLException.class, nullFailure.getCause());

        String oversized = "x".repeat(4 * 1024 * 1024 + 1);
        JdbcActivationOperationalRepository oversizedDocument = new JdbcActivationOperationalRepository(
                queryDataSource(characterResultSet(new StringReader(oversized))), JdbcDatabaseDialect.ORACLE);
        JdbcPersistenceException sizeFailure = assertThrows(JdbcPersistenceException.class,
                () -> oversizedDocument.acceptedManifestDocument(identity));
        assertInstanceOf(SQLException.class, sizeFailure.getCause());
    }

    @Test
    void jdbcFailuresPreserveOperationAndCause() {
        SQLException cause = new SQLException("boom");
        JdbcPersistenceException error = new JdbcPersistenceException("load-state", cause);
        assertEquals("JDBC persistence operation failed: load-state", error.getMessage());
        assertEquals(cause, error.getCause());

        DataSource failing = failingDataSource(cause);
        JdbcRevocationRegistry revocations = new JdbcRevocationRegistry(failing, JdbcDatabaseDialect.POSTGRESQL);
        JdbcPersistenceException wrapped = assertThrows(JdbcPersistenceException.class,
                () -> revocations.isKeyRevoked("key-1", NOW));
        assertEquals(cause, wrapped.getCause());
    }

    private static ActivationManifestPayload paidPayload(InstallationIdentity identity, long sequence) {
        Instant validFrom = NOW.plusSeconds(60);
        return new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA, id(100 + (int) sequence),
                new CustomerIdentity("customer-1", "Customer One"),
                new ManifestInstallation(identity.installationId(), identity.fingerprintVersion(), identity.fingerprint()),
                InstallationProfile.PRO, AllocationTier.STANDARD, "2.0.0-draft.20", 10,
                Set.of("iam.local-auth"), Map.of("rsot.managed_hosts.max", 10L), validFrom,
                validFrom.plus(365, ChronoUnit.DAYS), 30, NOW, "InfraNexum Licensing", sequence, "key-1");
    }

    private static InstallationIdentity identity(int sequence) {
        return new InstallationIdentity(id(sequence), "v1", "%064x".formatted(sequence), NOW);
    }

    private static IntegrityProof proof(InstallationIdentity identity, Instant reliableAt, long generation) {
        return new IntegrityProof(identity.installationId(), identity.fingerprint(), NOW,
                reliableAt, generation, Base64.getEncoder().encodeToString(new byte[32]));
    }

    private static DomainIdentifier id(int sequence) {
        return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(sequence)));
    }

    private static String signature() {
        return Base64.getEncoder().encodeToString(new byte[64]);
    }

    private static ResultSet resultSet(Map<String, Object> values) {
        InvocationHandler handler = new InvocationHandler() {
            private boolean first = true;
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                return switch (method.getName()) {
                    case "next" -> { boolean value = first; first = false; yield value; }
                    case "getObject" -> unwrapNull(values.get(String.valueOf(args[0])));
                    case "getString" -> {
                        Object value = unwrapNull(values.get(String.valueOf(args[0])));
                        yield value == null ? null : value.toString();
                    }
                    case "getLong" -> ((Number) values.get(String.valueOf(args[0]))).longValue();
                    case "close" -> null;
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                };
            }
        };
        return (ResultSet) Proxy.newProxyInstance(
                JdbcInfrastructureCoverageTest.class.getClassLoader(), new Class<?>[] {ResultSet.class}, handler);
    }

    private static Object unwrapNull(Object value) {
        return value == NullValue.INSTANCE ? null : value;
    }

    private enum NullValue { INSTANCE }

    private static final class RecordingStatement implements InvocationHandler {
        private final Map<Integer, Object> values = new HashMap<>();
        PreparedStatement proxy() {
            return (PreparedStatement) Proxy.newProxyInstance(
                    JdbcInfrastructureCoverageTest.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class}, this);
        }
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("setNull")) {
                values.put((Integer) args[0], args[1]);
                return null;
            }
            if (method.getName().startsWith("set") && args != null && args.length >= 2
                    && args[0] instanceof Integer index) {
                values.put(index, args[1]);
                return null;
            }
            return defaultValue(method.getReturnType());
        }
    }

    private static final class NoOpDataSource implements DataSource {
        private final List<String> sql = new ArrayList<>();
        @Override public Connection getConnection() { return connection(); }
        @Override public Connection getConnection(String username, String password) { return connection(); }
        private Connection connection() {
            InvocationHandler handler = new InvocationHandler() {
                private boolean autoCommit = true;
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    return switch (method.getName()) {
                        case "prepareStatement" -> {
                            String statementSql = (String) args[0];
                            sql.add(statementSql);
                            yield noOpStatement();
                        }
                        case "getAutoCommit" -> autoCommit;
                        case "setAutoCommit" -> { autoCommit = (Boolean) args[0]; yield null; }
                        case "commit", "rollback", "close" -> null;
                        case "isClosed" -> false;
                        default -> defaultValue(method.getReturnType());
                    };
                }
            };
            return (Connection) Proxy.newProxyInstance(
                    JdbcInfrastructureCoverageTest.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
        }
        private PreparedStatement noOpStatement() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "executeUpdate" -> 1;
                case "executeQuery" -> emptyResultSet();
                case "close", "setString", "setLong", "setInt", "setObject", "setNull" -> null;
                default -> defaultValue(method.getReturnType());
            };
            return (PreparedStatement) Proxy.newProxyInstance(
                    JdbcInfrastructureCoverageTest.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class}, handler);
        }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }


    private static ResultSet characterResultSet(Reader reader) {
        InvocationHandler handler = new InvocationHandler() {
            private boolean first = true;
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                return switch (method.getName()) {
                    case "next" -> { boolean value = first; first = false; yield value; }
                    case "getCharacterStream" -> reader;
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                };
            }
        };
        return (ResultSet) Proxy.newProxyInstance(
                JdbcInfrastructureCoverageTest.class.getClassLoader(), new Class<?>[] {ResultSet.class}, handler);
    }

    private static DataSource queryDataSource(ResultSet resultSet) {
        InvocationHandler statementHandler = (proxy, method, args) -> switch (method.getName()) {
            case "executeQuery" -> resultSet;
            case "close", "setString", "setLong", "setInt", "setObject", "setNull" -> null;
            default -> defaultValue(method.getReturnType());
        };
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                JdbcInfrastructureCoverageTest.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, statementHandler);
        InvocationHandler connectionHandler = (proxy, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> statement;
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        };
        Connection connection = (Connection) Proxy.newProxyInstance(
                JdbcInfrastructureCoverageTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, connectionHandler);
        return new DataSource() {
            @Override public Connection getConnection() { return connection; }
            @Override public Connection getConnection(String username, String password) { return connection; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) {}
            @Override public void setLoginTimeout(int seconds) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
            @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }

    private static ResultSet emptyResultSet() {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "next" -> false;
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        };
        return (ResultSet) Proxy.newProxyInstance(
                JdbcInfrastructureCoverageTest.class.getClassLoader(), new Class<?>[] {ResultSet.class}, handler);
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

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var stream = Files.walk(directory)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
