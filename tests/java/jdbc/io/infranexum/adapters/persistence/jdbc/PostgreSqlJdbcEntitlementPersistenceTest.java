package io.infranexum.adapters.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.capabilities.QuotaAllocationPlan;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.entitlements.AcceptedSequence;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Live PostgreSQL contracts for activation, runtime-state and revocation persistence. */
class PostgreSqlJdbcEntitlementPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final String CATALOG = "2.0.0-draft.21";

    private PGSimpleDataSource dataSource;
    private JdbcActivationOperationalRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        String url = System.getenv("INFRANEXUM_POSTGRESQL_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "PostgreSQL integration URL is not configured");
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(requiredEnvironment("INFRANEXUM_POSTGRESQL_TEST_USERNAME"));
        dataSource.setPassword(requiredEnvironment("INFRANEXUM_POSTGRESQL_TEST_PASSWORD"));
        repository = new JdbcActivationOperationalRepository(dataSource, JdbcDatabaseDialect.POSTGRESQL);
        truncate();
    }

    @AfterEach
    void cleanUp() throws SQLException {
        if (dataSource != null) {
            truncate();
        }
    }

    @Test
    void liteLifecyclePersistsProofStateAndSequence() throws SQLException {
        InstallationIdentity identity = identity(1);
        insertIdentity(identity);
        assertEquals(identity, repository.installationIdentity().orElseThrow());
        assertEquals(AcceptedSequence.none(), repository.acceptedSequence(identity));
        assertTrue(repository.databaseProof(identity).isEmpty());
        assertTrue(repository.entitlementState(identity).isEmpty());
        assertTrue(repository.acceptedManifestDocument(identity).isEmpty());

        IntegrityProof proof = proof(identity, NOW, 1);
        repository.initializeLite(identity, proof, NOW.plusSeconds(1));

        assertEquals(proof, repository.databaseProof(identity).orElseThrow());
        var state = repository.entitlementState(identity).orElseThrow();
        assertEquals(InstallationProfile.LITE, state.profile());
        assertEquals(EntitlementRuntimePhase.EVALUATION, state.phase());
        assertEquals(AcceptedSequence.none(), state.acceptedSequence());

        EntitlementRuntimeStatus conversion = new EntitlementRuntimeStatus(
                identity.installationId(), InstallationProfile.LITE, AllocationTier.STANDARD,
                EntitlementRuntimePhase.CONVERSION_REQUIRED, NOW.plusSeconds(10), NOW,
                NOW.plusSeconds(10), NOW.plusSeconds(20), null, null, 0, null,
                Set.of(), Map.of(), true, false);
        IntegrityProof updatedProof = proof(identity, NOW.plusSeconds(10), 2);
        repository.updateRuntimeState(identity, conversion, updatedProof, NOW.plusSeconds(10));
        assertEquals(EntitlementRuntimePhase.CONVERSION_REQUIRED,
                repository.entitlementState(identity).orElseThrow().phase());
        assertEquals(2, repository.databaseProof(identity).orElseThrow().generation());
    }

    @Test
    void paidAcceptancePersistsManifestAndAuthoritativeState() throws SQLException {
        InstallationIdentity identity = identity(2);
        insertIdentity(identity);
        ActivationManifestPayload payload = paidPayload(identity, 2);
        ActivationManifest manifest = new ActivationManifest(payload, signature());
        var plan = new QuotaAllocationPlan(CATALOG, InstallationProfile.PRO, AllocationTier.STANDARD,
                Map.of("rsot.managed_hosts.max", 10L));
        var result = new ActivationVerificationResult(
                ActivationUsageState.ACTIVE, payload, plan, payload.capabilities(),
                payload.validUntil().plus(30, ChronoUnit.DAYS));
        IntegrityProof proof = proof(identity, NOW.plusSeconds(2), 1);

        repository.accept(identity, manifest, result, proof, NOW.plusSeconds(3));

        AcceptedSequence sequence = repository.acceptedSequence(identity);
        assertEquals(2, sequence.value());
        assertEquals(payload.activationId(), sequence.activationId());
        assertEquals(proof, repository.databaseProof(identity).orElseThrow());
        var state = repository.entitlementState(identity).orElseThrow();
        assertEquals(InstallationProfile.PRO, state.profile());
        assertEquals(EntitlementRuntimePhase.ACTIVE, state.phase());
        assertEquals(payload.validUntil(), state.validUntil());
        String persisted = repository.acceptedManifestDocument(identity).orElseThrow();
        assertTrue(persisted.contains(payload.activationId().toString()));
        assertTrue(persisted.contains("customer-1"));
        assertTrue(persisted.contains("signature"));
    }

    @Test
    void revocationsBecomeEffectiveAtTheirConfiguredInstant() throws SQLException {
        InstallationIdentity identity = identity(3);
        insertIdentity(identity);
        DomainIdentifier activationId = id(303);
        insertRevocation("KEY", "key-3", NOW.plusSeconds(10));
        insertRevocation("ACTIVATION", activationId.toString(), NOW.plusSeconds(20));
        JdbcRevocationRegistry registry = new JdbcRevocationRegistry(dataSource, JdbcDatabaseDialect.POSTGRESQL);

        assertFalse(registry.isKeyRevoked("key-3", NOW.plusSeconds(9)));
        assertTrue(registry.isKeyRevoked("key-3", NOW.plusSeconds(10)));
        assertFalse(registry.isActivationRevoked(activationId, NOW.plusSeconds(19)));
        assertTrue(registry.isActivationRevoked(activationId, NOW.plusSeconds(20)));
        assertFalse(registry.isKeyRevoked("missing", NOW.plusSeconds(100)));
    }

    @Test
    void failedRuntimeUpdateRollsBackProofAtomically() throws SQLException {
        InstallationIdentity identity = identity(4);
        insertIdentity(identity);
        IntegrityProof proof = proof(identity, NOW.plusSeconds(30), 1);
        EntitlementRuntimeStatus status = new EntitlementRuntimeStatus(
                identity.installationId(), InstallationProfile.LITE, AllocationTier.STANDARD,
                EntitlementRuntimePhase.EVALUATION, NOW.plusSeconds(30), NOW.plusSeconds(30),
                NOW.plusSeconds(40), NOW.plusSeconds(50), null, null, 0, null,
                Set.of(), Map.of(), true, true);

        JdbcPersistenceException failure = assertThrows(JdbcPersistenceException.class,
                () -> repository.updateRuntimeState(identity, status, proof, NOW.plusSeconds(30)));
        assertTrue(failure.getCause() instanceof SQLException);
        assertTrue(repository.databaseProof(identity).isEmpty(), "proof insert must roll back with state update");
    }

    @Test
    void installationIdentityFailsClosedWhenDatabaseContainsMultipleRows() throws SQLException {
        insertIdentity(identity(5));
        insertIdentity(identity(6));
        JdbcPersistenceException failure = assertThrows(JdbcPersistenceException.class, repository::installationIdentity);
        assertTrue(failure.getCause() instanceof SQLException);
        assertTrue(failure.getCause().getMessage().contains("multiple installation identities"));
    }

    private void insertIdentity(InstallationIdentity identity) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO core_installation_identity "
                                + "(installation_id,fingerprint_version,fingerprint,created_at) VALUES (?,?,?,?)")) {
            statement.setObject(1, identity.installationId().value());
            statement.setString(2, identity.fingerprintVersion());
            statement.setString(3, identity.fingerprint());
            JdbcTemporal.bindInstant(statement, 4, identity.createdAt());
            statement.executeUpdate();
        }
    }

    private void insertRevocation(String type, String key, Instant effectiveAt) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO core_activation_revocation "
                                + "(revocation_type,revocation_key,effective_at,reason,recorded_at) VALUES (?,?,?,?,?)")) {
            statement.setString(1, type);
            statement.setString(2, key);
            JdbcTemporal.bindInstant(statement, 3, effectiveAt);
            statement.setString(4, "coverage contract");
            JdbcTemporal.bindInstant(statement, 5, NOW);
            statement.executeUpdate();
        }
    }

    private void truncate() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "TRUNCATE TABLE core_activation_revocation, core_entitlement_state, "
                                + "core_entitlement_integrity_proof, core_activation_manifest, "
                                + "core_installation_identity CASCADE")) {
            statement.executeUpdate();
        }
    }

    private static InstallationIdentity identity(int sequence) {
        return new InstallationIdentity(id(sequence), "v1", "%064x".formatted(sequence), NOW);
    }

    private static IntegrityProof proof(InstallationIdentity identity, Instant reliableAt, long generation) {
        return new IntegrityProof(identity.installationId(), identity.fingerprint(), NOW,
                reliableAt, generation, Base64.getEncoder().encodeToString(new byte[32]));
    }

    private static ActivationManifestPayload paidPayload(InstallationIdentity identity, long sequence) {
        Instant validFrom = NOW.plusSeconds(60);
        return new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA, id(100 + (int) sequence),
                new CustomerIdentity("customer-1", "Customer One"),
                new ManifestInstallation(identity.installationId(), identity.fingerprintVersion(), identity.fingerprint()),
                InstallationProfile.PRO, AllocationTier.STANDARD, CATALOG, 10,
                Set.of("iam.local-auth"), Map.of("rsot.managed_hosts.max", 10L), validFrom,
                validFrom.plus(365, ChronoUnit.DAYS), 30, NOW, "InfraNexum Licensing", sequence, "key-1");
    }

    private static DomainIdentifier id(int sequence) {
        return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(sequence)));
    }

    private static String signature() {
        return Base64.getEncoder().encodeToString(new byte[64]);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
