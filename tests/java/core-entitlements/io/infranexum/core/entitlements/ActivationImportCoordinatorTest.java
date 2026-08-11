package io.infranexum.core.entitlements;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Regression coverage for durable activation import, temporal evidence and compensation. */
class ActivationImportCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final String CATALOG = "2.0.0-draft.20";
    private InstallationIdentity identity;
    private ActivationManifest manifest;
    private ActivationValidationContext context;
    private SecretKey integrityKey;

    @BeforeEach
    void setUp() throws Exception {
        identity = new InstallationIdentity(idAt(NOW), "v1", "a".repeat(64), NOW.minusSeconds(60));
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        TrustedKey key = new TrustedKey("key-1", pair.getPublic(), NOW.minusSeconds(60), NOW.plus(400, ChronoUnit.DAYS));
        var capabilities = io.infranexum.core.capabilities.CapabilityCatalog.loadEmbedded(CATALOG);
        var quotas = io.infranexum.core.capabilities.QuotaCatalog.loadEmbedded(CATALOG);
        var plan = quotas.allocate(InstallationProfile.PRO, AllocationTier.STANDARD, CATALOG, Map.of());
        var enabled = capabilities.codes().stream()
                .filter(code -> capabilities.find(code).allowedProfiles().contains(InstallationProfile.PRO))
                .map(Object::toString).collect(java.util.stream.Collectors.toUnmodifiableSet());
        ActivationManifestPayload payload = new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA, idAt(NOW.plusSeconds(1)),
                new CustomerIdentity("customer-1", "Customer One"),
                new ManifestInstallation(identity.installationId(), identity.fingerprintVersion(), identity.fingerprint()),
                InstallationProfile.PRO, AllocationTier.STANDARD, CATALOG,
                plan.limit("rsot.managed_hosts.max"), enabled, plan.limits(),
                NOW.minusSeconds(1), NOW.plus(365, ChronoUnit.DAYS), 30, NOW.minusSeconds(1), "issuer", 1, key.keyId());
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(payload.canonicalBytes());
        manifest = new ActivationManifest(payload, Base64.getEncoder().encodeToString(signer.sign()));
        context = new ActivationValidationContext(identity, "customer-1", InstallationProfile.PRO, CATALOG,
                capabilities, quotas, AcceptedSequence.none(), new InMemoryTrustedKeyStore(Map.of(key.keyId(), key)),
                new InMemoryRevocationRegistry(Map.of(), Map.of()), NOW);
        integrityKey = new SecretKeySpec(new byte[32], "HmacSHA256");
    }

    @Test
    void importInitializesEvidenceAndPersistsVerifiedManifest() {
        FakeRepository repository = new FakeRepository(identity);
        MemoryProofStore store = new MemoryProofStore();
        ActivationImportCoordinator coordinator = coordinator(repository, store);

        ActivationImportResult result = coordinator.importManifest(manifest);

        assertEquals(ActivationUsageState.ACTIVE, result.state());
        assertEquals(1L, result.sequence());
        assertNotNull(result.graceUntil());
        assertNotNull(repository.proof);
        assertSame(manifest, repository.acceptedManifest);
        assertEquals(1L, repository.acceptedResult.payload().sequence());
        assertTrue(store.load(identity).isPresent());
    }

    @Test
    void secondImportAdvancesExistingTemporalEvidence() {
        TrustedTimeGuard guard = new TrustedTimeGuard();
        IntegrityProof initial = guard.initialize(identity, NOW.minusSeconds(10), integrityKey).databaseProof();
        FakeRepository repository = new FakeRepository(identity);
        repository.proof = initial;
        MemoryProofStore store = new MemoryProofStore();
        store.store(initial);

        coordinator(repository, store).importManifest(manifest);

        assertEquals(2L, repository.proof.generation());
        assertEquals(NOW, repository.proof.lastReliableAt());
        assertEquals(repository.proof, store.load(identity).orElseThrow());
    }

    @Test
    void incompleteTemporalEvidenceFailsClosed() {
        FakeRepository repository = new FakeRepository(identity);
        repository.proof = new TrustedTimeGuard().initialize(identity, NOW.minusSeconds(1), integrityKey).databaseProof();
        MemoryProofStore store = new MemoryProofStore();

        assertThrows(ClockRollbackException.class, () -> coordinator(repository, store).importManifest(manifest));
    }

    @Test
    void missingInstallationIdentityIsRejectedBeforeVerification() {
        FakeRepository repository = new FakeRepository(null);
        assertThrows(IllegalStateException.class,
                () -> coordinator(repository, new MemoryProofStore()).importManifest(manifest));
    }

    @Test
    void repositoryFailureRestoresPreviousIndependentProof() {
        TrustedTimeGuard guard = new TrustedTimeGuard();
        IntegrityProof previous = guard.initialize(identity, NOW.minusSeconds(10), integrityKey).databaseProof();
        FakeRepository repository = new FakeRepository(identity);
        repository.proof = previous;
        repository.acceptFailure = new IllegalStateException("db failed");
        MemoryProofStore store = new MemoryProofStore();
        store.store(previous);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> coordinator(repository, store).importManifest(manifest));

        assertEquals("db failed", failure.getMessage());
        assertEquals(previous, store.load(identity).orElseThrow());
    }

    @Test
    void initialRepositoryFailureDeletesNewIndependentProof() {
        FakeRepository repository = new FakeRepository(identity);
        repository.acceptFailure = new IllegalStateException("db failed");
        MemoryProofStore store = new MemoryProofStore();

        assertThrows(IllegalStateException.class, () -> coordinator(repository, store).importManifest(manifest));
        assertTrue(store.load(identity).isEmpty());
    }

    @Test
    void compensationFailureIsReportedWithOriginalCauseAndSuppressedFailure() {
        FakeRepository repository = new FakeRepository(identity);
        repository.acceptFailure = new IllegalArgumentException("db failed");
        MemoryProofStore store = new MemoryProofStore();
        store.deleteFailure = new IllegalStateException("store failed");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> coordinator(repository, store).importManifest(manifest));

        assertEquals("activation import failed and temporal evidence compensation failed", failure.getMessage());
        assertSame(repository.acceptFailure, failure.getCause());
        assertEquals(1, failure.getCause().getSuppressed().length);
    }

    private ActivationImportCoordinator coordinator(FakeRepository repository, MemoryProofStore store) {
        return new ActivationImportCoordinator(repository, store, new ActivationManifestVerifier(),
                (ignoredIdentity, ignoredSequence, ignoredNow) -> context,
                new TrustedTimeGuard(), integrityKey, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DomainIdentifier idAt(Instant instant) {
        return new UuidV7Generator(Clock.fixed(instant, ZoneOffset.UTC), new java.security.SecureRandom()).next();
    }

    private static final class FakeRepository implements ActivationOperationalRepository {
        private final InstallationIdentity identity;
        private IntegrityProof proof;
        private ActivationManifest acceptedManifest;
        private ActivationVerificationResult acceptedResult;
        private RuntimeException acceptFailure;

        private FakeRepository(InstallationIdentity identity) { this.identity = identity; }
        @Override public Optional<InstallationIdentity> installationIdentity() { return Optional.ofNullable(identity); }
        @Override public AcceptedSequence acceptedSequence(InstallationIdentity ignored) { return AcceptedSequence.none(); }
        @Override public Optional<IntegrityProof> databaseProof(InstallationIdentity ignored) { return Optional.ofNullable(proof); }
        @Override public void accept(InstallationIdentity ignored, ActivationManifest value,
                ActivationVerificationResult result, IntegrityProof databaseProof, Instant acceptedAt) {
            if (acceptFailure != null) throw acceptFailure;
            acceptedManifest = value;
            acceptedResult = result;
            proof = databaseProof;
        }
    }

    private static final class MemoryProofStore implements IndependentIntegrityProofStore {
        private IntegrityProof proof;
        private RuntimeException deleteFailure;
        @Override public Optional<IntegrityProof> load(InstallationIdentity ignored) { return Optional.ofNullable(proof); }
        @Override public void store(IntegrityProof value) { proof = value; }
        @Override public void delete(InstallationIdentity ignored) {
            if (deleteFailure != null) throw deleteFailure;
            proof = null;
        }
    }
}
