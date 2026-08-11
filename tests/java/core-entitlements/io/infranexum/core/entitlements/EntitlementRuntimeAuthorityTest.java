package io.infranexum.core.entitlements;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.CapabilityCatalog;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.capabilities.QuotaAllocationPlan;
import io.infranexum.core.capabilities.QuotaCatalog;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Regression coverage for the authoritative runtime entitlement lifecycle. */
class EntitlementRuntimeAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-04-01T00:00:00Z");
    private InstallationIdentity identity;
    private SecretKey key;

    @BeforeEach
    void setUp() {
        identity = new InstallationIdentity(idAt(NOW.minusSeconds(10)), "v1", "b".repeat(64), NOW.minusSeconds(10));
        key = new SecretKeySpec(new byte[32], "HmacSHA256");
    }

    @Test
    void uninitializedAuthorityFailsClosedForStatusAndMutation() {
        RuntimeRepository repository = new RuntimeRepository(identity);
        EntitlementRuntimeAuthority authority = authority(repository, new MemoryProofStore(), NOW);
        assertThrows(EntitlementRuntimeUnavailableException.class, authority::currentStatus);
        assertThrows(EntitlementRuntimeUnavailableException.class, authority::requireMutation);
    }

    @Test
    void initialLiteStartupPersistsDualEvidenceAndPermitsMutation() {
        RuntimeRepository repository = new RuntimeRepository(identity);
        MemoryProofStore store = new MemoryProofStore();
        EntitlementRuntimeAuthority authority = authority(repository, store, NOW);

        EntitlementRuntimeStatus status = authority.initializeAndRequireStartup(InstallationProfile.LITE);

        assertEquals(EntitlementRuntimePhase.EVALUATION, status.phase());
        assertTrue(status.serviceStartupPermitted());
        assertTrue(status.mutationPermitted());
        assertSame(status, authority.currentStatus());
        assertNotNull(repository.proof);
        assertTrue(store.load(identity).isPresent());
        authority.requireMutation();
    }

    @Test
    void paidProfileWithoutDurableActivationIsRejected() {
        RuntimeRepository repository = new RuntimeRepository(identity);
        EntitlementAccessException failure = assertThrows(EntitlementAccessException.class,
                () -> authority(repository, new MemoryProofStore(), NOW).refresh(InstallationProfile.PRO));
        assertEquals(EntitlementErrorCodes.ACTIVATION_REQUIRED, failure.code());
    }

    @Test
    void durableProfileMustMatchInstalledProfile() {
        RuntimeRepository repository = liteRepository(NOW.minusSeconds(5));
        EntitlementAccessException failure = assertThrows(EntitlementAccessException.class,
                () -> authority(repository, storeWith(repository.proof), NOW).refresh(InstallationProfile.PRO));
        assertEquals(EntitlementErrorCodes.ACTIVATION_INVALID, failure.code());
    }

    @Test
    void missingIndependentOrDatabaseEvidenceIsRejected() {
        RuntimeRepository repository = liteRepository(NOW.minusSeconds(5));
        assertThrows(ClockRollbackException.class,
                () -> authority(repository, new MemoryProofStore(), NOW).refresh(InstallationProfile.LITE));

        RuntimeRepository missingDatabase = liteRepository(NOW.minusSeconds(5));
        IntegrityProof proof = missingDatabase.proof;
        missingDatabase.proof = null;
        assertThrows(ClockRollbackException.class,
                () -> authority(missingDatabase, storeWith(proof), NOW).refresh(InstallationProfile.LITE));
    }

    @Test
    void liteRefreshAdvancesEvidenceAndTransitionsToConversionRequired() {
        Instant started = NOW.minusSeconds(180L * 24 * 3600);
        RuntimeRepository repository = liteRepository(started);
        MemoryProofStore store = storeWith(repository.proof);
        EntitlementRuntimeAuthority authority = authority(repository, store, NOW);

        EntitlementRuntimeStatus status = authority.refresh(InstallationProfile.LITE);
        assertEquals(EntitlementRuntimePhase.CONVERSION_REQUIRED, status.phase());
        assertTrue(status.serviceStartupPermitted());
        assertFalse(status.mutationPermitted());
        EntitlementAccessException mutation = assertThrows(EntitlementAccessException.class, authority::requireMutation);
        assertEquals(EntitlementErrorCodes.LITE_CONVERSION_REQUIRED, mutation.code());
        assertEquals(2L, repository.proof.generation());
    }

    @Test
    void liteHardStopBlocksStartupAndMutation() {
        Instant started = NOW.minusSeconds(211L * 24 * 3600);
        RuntimeRepository repository = liteRepository(started);
        MemoryProofStore store = storeWith(repository.proof);
        EntitlementRuntimeAuthority authority = authority(repository, store, NOW);

        EntitlementRuntimeStatus refreshed = authority.refresh(InstallationProfile.LITE);
        assertEquals(EntitlementRuntimePhase.HARD_STOPPED, refreshed.phase());
        EntitlementAccessException mutation = assertThrows(EntitlementAccessException.class, authority::requireMutation);
        assertEquals(EntitlementErrorCodes.LITE_HARD_STOPPED, mutation.code());

        RuntimeRepository second = liteRepository(started);
        EntitlementAccessException startup = assertThrows(EntitlementAccessException.class,
                () -> authority(second, storeWith(second.proof), NOW).initializeAndRequireStartup(InstallationProfile.LITE));
        assertEquals(EntitlementErrorCodes.LITE_HARD_STOPPED, startup.code());
    }

    @Test
    void liteOriginMismatchIsTreatedAsClockRollback() {
        RuntimeRepository repository = liteRepository(NOW.minusSeconds(5));
        repository.state = new EntitlementStateRecord(InstallationProfile.LITE, AllocationTier.STANDARD,
                NOW.minusSeconds(6), repository.proof.lastReliableAt(), repository.proof.generation(),
                AcceptedSequence.none(), EntitlementRuntimePhase.EVALUATION, null, null, NOW.minusSeconds(5));
        assertThrows(ClockRollbackException.class,
                () -> authority(repository, storeWith(repository.proof), NOW).refresh(InstallationProfile.LITE));
    }

    @Test
    void initializationRepositoryFailureCompensatesIndependentEvidence() {
        RuntimeRepository repository = new RuntimeRepository(identity);
        repository.initializeFailure = new IllegalStateException("db init failed");
        MemoryProofStore store = new MemoryProofStore();
        assertThrows(IllegalStateException.class,
                () -> authority(repository, store, NOW).refresh(InstallationProfile.LITE));
        assertTrue(store.load(identity).isEmpty());
    }

    @Test
    void runtimeUpdateFailureRestoresPreviousIndependentEvidence() {
        RuntimeRepository repository = liteRepository(NOW.minusSeconds(5));
        IntegrityProof previous = repository.proof;
        repository.updateFailure = new IllegalStateException("update failed");
        MemoryProofStore store = storeWith(previous);
        assertThrows(IllegalStateException.class,
                () -> authority(repository, store, NOW).refresh(InstallationProfile.LITE));
        assertEquals(previous, store.load(identity).orElseThrow());
    }

    @Test
    void compensationFailureEscalatesWithoutLosingOriginalFailure() {
        RuntimeRepository repository = new RuntimeRepository(identity);
        repository.initializeFailure = new IllegalArgumentException("db init failed");
        MemoryProofStore store = new MemoryProofStore();
        store.deleteFailure = new IllegalStateException("delete failed");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> authority(repository, store, NOW).refresh(InstallationProfile.LITE));
        assertEquals("entitlement refresh failed and temporal evidence compensation failed", failure.getMessage());
        assertSame(repository.initializeFailure, failure.getCause());
        assertEquals(1, failure.getCause().getSuppressed().length);
    }

    @Test
    void paidRuntimeRevalidatesDurableManifestAcrossActiveGraceAndHardStop() throws Exception {
        PaidFixture active = paidFixture(NOW.plusSeconds(60));
        EntitlementRuntimeAuthority activeAuthority = paidAuthority(active, NOW);
        EntitlementRuntimeStatus activeStatus = activeAuthority.initializeAndRequireStartup(InstallationProfile.PRO);
        assertEquals(EntitlementRuntimePhase.ACTIVE, activeStatus.phase());
        assertTrue(activeStatus.serviceStartupPermitted());
        assertTrue(activeStatus.mutationPermitted());
        activeAuthority.requireMutation();

        PaidFixture grace = paidFixture(NOW.minusSeconds(1));
        EntitlementRuntimeAuthority graceAuthority = paidAuthority(grace, NOW);
        EntitlementRuntimeStatus graceStatus = graceAuthority.initializeAndRequireStartup(InstallationProfile.PRO);
        assertEquals(EntitlementRuntimePhase.GRACE, graceStatus.phase());
        assertTrue(graceStatus.serviceStartupPermitted());
        assertTrue(graceStatus.mutationPermitted());

        PaidFixture stopped = paidFixture(NOW.minus(31, ChronoUnit.DAYS));
        EntitlementRuntimeAuthority stoppedAuthority = paidAuthority(stopped, NOW);
        EntitlementRuntimeStatus stoppedStatus = stoppedAuthority.refresh(InstallationProfile.PRO);
        assertEquals(EntitlementRuntimePhase.HARD_STOPPED, stoppedStatus.phase());
        EntitlementAccessException mutation = assertThrows(EntitlementAccessException.class, stoppedAuthority::requireMutation);
        assertEquals(EntitlementErrorCodes.ACTIVATION_EXPIRED, mutation.code());
        EntitlementAccessException startup = assertThrows(EntitlementAccessException.class,
                () -> paidAuthority(stopped, NOW).initializeAndRequireStartup(InstallationProfile.PRO));
        assertEquals(EntitlementErrorCodes.ACTIVATION_EXPIRED, startup.code());
    }

    @Test
    void paidRuntimeRejectsMissingOrMismatchedDurableManifest() throws Exception {
        PaidFixture missingFixture = paidFixture(NOW.plusSeconds(60));
        missingFixture.repository.document = null;
        EntitlementAccessException missing = assertThrows(EntitlementAccessException.class,
                () -> paidAuthority(missingFixture, NOW).refresh(InstallationProfile.PRO));
        assertEquals(EntitlementErrorCodes.ACTIVATION_INVALID, missing.code());

        PaidFixture mismatched = paidFixture(NOW.plusSeconds(60));
        DomainIdentifier otherActivation = idAt(NOW.plusSeconds(100));
        mismatched.repository.state = new EntitlementStateRecord(InstallationProfile.PRO, AllocationTier.STANDARD,
                null, mismatched.repository.proof.lastReliableAt(), mismatched.repository.proof.generation(),
                new AcceptedSequence(1, otherActivation), EntitlementRuntimePhase.ACTIVE,
                mismatched.manifest.payload().validUntil(), mismatched.manifest.payload().validUntil().plus(30, ChronoUnit.DAYS), NOW);
        EntitlementAccessException mismatch = assertThrows(EntitlementAccessException.class,
                () -> paidAuthority(mismatched, NOW).refresh(InstallationProfile.PRO));
        assertEquals(EntitlementErrorCodes.ACTIVATION_INVALID, mismatch.code());
    }

    @Test
    void preflightReturnsVerifiedPaidManifestWithoutMutatingDurableState() throws Exception {
        PaidFixture paid = paidFixture(NOW.plusSeconds(60));
        EntitlementStateRecord stateBefore = paid.repository.state;
        IntegrityProof proofBefore = paid.repository.proof;
        EntitlementRuntimeAuthority authority = paidAuthority(paid, NOW);

        ActivationVerificationResult result = authority.preflight(paid.manifest);

        assertEquals(ActivationUsageState.ACTIVE, result.state());
        assertEquals(paid.manifest.payload().activationId(), result.payload().activationId());
        assertSame(stateBefore, paid.repository.state);
        assertSame(proofBefore, paid.repository.proof);
    }

    @Test
    void preflightRequiresIdentityAndManifest() {
        RuntimeRepository repository = new RuntimeRepository(null);
        EntitlementRuntimeAuthority authority = authority(repository, new MemoryProofStore(), NOW);
        assertThrows(NullPointerException.class, () -> authority.preflight(null));
        ActivationManifest dummy = new ActivationManifest(newDummyPayload(), java.util.Base64.getEncoder().encodeToString(new byte[64]));
        assertThrows(IllegalStateException.class, () -> authority.preflight(dummy));
    }

    private PaidFixture paidFixture(Instant validUntil) throws Exception {
        String catalogVersion = "2.0.0-draft.20";
        CapabilityCatalog capabilityCatalog = CapabilityCatalog.loadEmbedded(catalogVersion);
        QuotaCatalog quotaCatalog = QuotaCatalog.loadEmbedded(catalogVersion);
        QuotaAllocationPlan plan = quotaCatalog.allocate(InstallationProfile.PRO, AllocationTier.STANDARD, catalogVersion, Map.of());
        Set<String> capabilities = capabilityCatalog.codes().stream()
                .filter(code -> capabilityCatalog.find(code).allowedProfiles().contains(InstallationProfile.PRO))
                .map(Object::toString)
                .collect(Collectors.toUnmodifiableSet());
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        TrustedKey trustedKey = new TrustedKey("runtime-key", keyPair.getPublic(), NOW.minus(1000, ChronoUnit.DAYS), NOW.plus(1000, ChronoUnit.DAYS));
        Instant validFrom = validUntil.minus(365, ChronoUnit.DAYS);
        ActivationManifestPayload payload = new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA, idAt(NOW.minusSeconds(2)),
                new CustomerIdentity("runtime-customer", "Runtime Customer"),
                new ManifestInstallation(identity.installationId(), identity.fingerprintVersion(), identity.fingerprint()),
                InstallationProfile.PRO, AllocationTier.STANDARD, catalogVersion,
                plan.limit("rsot.managed_hosts.max"), capabilities, plan.limits(), validFrom, validUntil, 30,
                validFrom, "runtime-test", 1, trustedKey.keyId());
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload.canonicalBytes());
        ActivationManifest manifest = new ActivationManifest(payload, Base64.getEncoder().encodeToString(signer.sign()));

        IntegrityProof proof = new TrustedTimeGuard().initialize(identity, NOW.minusSeconds(5), key).databaseProof();
        RuntimeRepository repository = new RuntimeRepository(identity);
        repository.proof = proof;
        repository.document = manifest.canonicalDocument();
        repository.state = new EntitlementStateRecord(InstallationProfile.PRO, AllocationTier.STANDARD, null,
                proof.lastReliableAt(), proof.generation(), new AcceptedSequence(payload.sequence(), payload.activationId()),
                EntitlementRuntimePhase.ACTIVE, payload.validUntil(), payload.validUntil().plus(30, ChronoUnit.DAYS), NOW.minusSeconds(5));
        MemoryProofStore store = storeWith(proof);
        ActivationContextFactory contextFactory = (runtimeIdentity, sequence, at) -> new ActivationValidationContext(
                runtimeIdentity, payload.customer().customerId(), InstallationProfile.PRO, catalogVersion,
                capabilityCatalog, quotaCatalog, sequence, new InMemoryTrustedKeyStore(Map.of(trustedKey.keyId(), trustedKey)),
                new InMemoryRevocationRegistry(Map.of(), Map.of()), at);
        return new PaidFixture(repository, store, manifest, contextFactory);
    }

    private EntitlementRuntimeAuthority paidAuthority(PaidFixture fixture, Instant now) {
        return new EntitlementRuntimeAuthority(fixture.repository, fixture.store, new ActivationManifestVerifier(),
                fixture.contextFactory, ignored -> fixture.manifest, new TrustedTimeGuard(),
                new LiteEvaluationPolicy(), new EntitlementGuard(), key, Clock.fixed(now, ZoneOffset.UTC));
    }

    private record PaidFixture(
            RuntimeRepository repository, MemoryProofStore store, ActivationManifest manifest,
            ActivationContextFactory contextFactory) {}

    private ActivationManifestPayload newDummyPayload() {
        return new ActivationManifestPayload(ActivationManifestPayload.SCHEMA, idAt(NOW),
                new CustomerIdentity("c", "Customer"),
                new ManifestInstallation(identity.installationId(), identity.fingerprintVersion(), identity.fingerprint()),
                InstallationProfile.PRO, AllocationTier.STANDARD, "catalog", 0L, java.util.Set.of(), Map.of(),
                NOW, NOW.plusSeconds(10), 30, NOW, "issuer", 1, "key");
    }

    private RuntimeRepository liteRepository(Instant started) {
        TrustedTimeGuard guard = new TrustedTimeGuard();
        IntegrityProof proof = guard.initialize(identity, started, key).databaseProof();
        RuntimeRepository repository = new RuntimeRepository(identity);
        repository.proof = proof;
        repository.state = new EntitlementStateRecord(InstallationProfile.LITE, AllocationTier.STANDARD,
                started, proof.lastReliableAt(), proof.generation(), AcceptedSequence.none(),
                EntitlementRuntimePhase.EVALUATION, null, null, started);
        return repository;
    }

    private EntitlementRuntimeAuthority authority(RuntimeRepository repository, MemoryProofStore store, Instant now) {
        return new EntitlementRuntimeAuthority(repository, store, new ActivationManifestVerifier(),
                (ignoredIdentity, ignoredSequence, ignoredNow) -> { throw new AssertionError("context not expected"); },
                document -> { throw new AssertionError("codec not expected"); },
                new TrustedTimeGuard(), new LiteEvaluationPolicy(), new EntitlementGuard(), key,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static MemoryProofStore storeWith(IntegrityProof proof) {
        MemoryProofStore store = new MemoryProofStore();
        store.store(proof);
        return store;
    }

    private static DomainIdentifier idAt(Instant instant) {
        return new UuidV7Generator(Clock.fixed(instant, ZoneOffset.UTC), new java.security.SecureRandom()).next();
    }

    private static final class RuntimeRepository implements EntitlementRuntimeRepository {
        private final InstallationIdentity identity;
        private IntegrityProof proof;
        private EntitlementStateRecord state;
        private String document;
        private RuntimeException initializeFailure;
        private RuntimeException updateFailure;

        private RuntimeRepository(InstallationIdentity identity) { this.identity = identity; }
        @Override public Optional<InstallationIdentity> installationIdentity() { return Optional.ofNullable(identity); }
        @Override public AcceptedSequence acceptedSequence(InstallationIdentity ignored) {
            return state == null ? AcceptedSequence.none() : state.acceptedSequence();
        }
        @Override public Optional<IntegrityProof> databaseProof(InstallationIdentity ignored) { return Optional.ofNullable(proof); }
        @Override public void accept(InstallationIdentity ignored, ActivationManifest manifest,
                ActivationVerificationResult result, IntegrityProof databaseProof, Instant acceptedAt) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<EntitlementStateRecord> entitlementState(InstallationIdentity ignored) { return Optional.ofNullable(state); }
        @Override public Optional<String> acceptedManifestDocument(InstallationIdentity ignored) { return Optional.ofNullable(document); }
        @Override public void initializeLite(InstallationIdentity ignored, IntegrityProof databaseProof, Instant initializedAt) {
            if (initializeFailure != null) throw initializeFailure;
            proof = databaseProof;
            state = new EntitlementStateRecord(InstallationProfile.LITE, AllocationTier.STANDARD,
                    databaseProof.evaluationStartedAt(), databaseProof.lastReliableAt(), databaseProof.generation(),
                    AcceptedSequence.none(), EntitlementRuntimePhase.EVALUATION, null, null, initializedAt);
        }
        @Override public void updateRuntimeState(InstallationIdentity ignored, EntitlementRuntimeStatus status,
                IntegrityProof databaseProof, Instant updatedAt) {
            if (updateFailure != null) throw updateFailure;
            proof = databaseProof;
            state = new EntitlementStateRecord(status.profile(), status.allocationTier(), status.evaluationStartedAt(),
                    databaseProof.lastReliableAt(), databaseProof.generation(),
                    status.acceptedSequence() == 0 ? AcceptedSequence.none() : new AcceptedSequence(status.acceptedSequence(), status.acceptedActivationId()),
                    status.phase(), status.validUntil(), status.graceUntil(), updatedAt);
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
