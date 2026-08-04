package io.infranexum.core.entitlements;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.CapabilityCatalog;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.capabilities.QuotaCatalog;
import io.infranexum.core.contracts.DomainIdentifier;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** End-to-end smoke for Lite boundaries and paid activation re-verification at startup. */
public final class EntitlementRuntimeSmoke {
    private EntitlementRuntimeSmoke() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "capability and quota catalog paths are required");
        CapabilityCatalog capabilities = CapabilityCatalog.load("2.0.0-draft.20", java.nio.file.Path.of(args[0]));
        QuotaCatalog quotas = QuotaCatalog.load("2.0.0-draft.20", java.nio.file.Path.of(args[1]));
        liteLifecycle(capabilities, quotas);
        paidLifecycle(capabilities, quotas);
        System.out.println("java-entitlement-runtime-smoke: PASS");
    }

    private static void liteLifecycle(CapabilityCatalog capabilities, QuotaCatalog quotas) {
        Instant started = Instant.parse("2026-01-01T00:00:00Z");
        MutableClock clock = new MutableClock(started);
        InstallationIdentity identity = identity(started);
        MemoryRepository repository = new MemoryRepository(identity);
        MemoryProofStore proofs = new MemoryProofStore();
        EntitlementRuntimeAuthority authority = authority(
                repository, proofs, capabilities, quotas, InstallationProfile.LITE, clock, document -> { throw new IllegalStateException("Lite has no manifest"); });

        EntitlementRuntimeStatus initial = authority.initializeAndRequireStartup(InstallationProfile.LITE);
        require(initial.phase() == EntitlementRuntimePhase.EVALUATION, "Lite did not start in evaluation");
        require(initial.mutationPermitted(), "Lite evaluation must permit mutations");

        clock.set(started.plusSeconds(180L * 86400L));
        EntitlementRuntimeStatus conversion = authority.refresh(InstallationProfile.LITE);
        require(conversion.phase() == EntitlementRuntimePhase.CONVERSION_REQUIRED,
                "Lite did not enter conversion at day 180");
        expect(EntitlementAccessException.class, authority::requireMutation);

        clock.set(started.plusSeconds(210L * 86400L));
        expect(EntitlementAccessException.class,
                () -> authority.initializeAndRequireStartup(InstallationProfile.LITE));
    }

    private static void paidLifecycle(CapabilityCatalog capabilities, QuotaCatalog quotas) throws Exception {
        Instant now = Instant.parse("2026-04-01T00:00:00Z");
        MutableClock clock = new MutableClock(now);
        InstallationIdentity identity = identity(now.minusSeconds(60));
        MemoryRepository repository = new MemoryRepository(identity);
        MemoryProofStore proofs = new MemoryProofStore();
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        TrustedKey trustedKey = new TrustedKey(
                "test-key", pair.getPublic(), now.minusSeconds(3600), now.plusSeconds(86400L * 365));
        TrustedKeyStore keys = new InMemoryTrustedKeyStore(Map.of(trustedKey.keyId(), trustedKey));
        RevocationRegistry revocations = new InMemoryRevocationRegistry(Map.of(), Map.of());
        ActivationContextFactory contexts = contexts(
                capabilities, quotas, InstallationProfile.PRO, identity, keys, revocations);
        SecretKey integrityKey = integrityKey();
        ActivationManifest manifest = signedManifest(identity, capabilities, quotas, pair, now);
        ActivationManifestVerifier verifier = new ActivationManifestVerifier();
        ActivationImportCoordinator importer = new ActivationImportCoordinator(
                repository, proofs, verifier, contexts, new TrustedTimeGuard(), integrityKey, clock);
        importer.importManifest(manifest);

        EntitlementRuntimeAuthority authority = new EntitlementRuntimeAuthority(
                repository, proofs, verifier, contexts,
                document -> {
                    require(document.equals(manifest.canonicalDocument()), "persisted activation document changed");
                    return manifest;
                },
                new TrustedTimeGuard(), new LiteEvaluationPolicy(), new EntitlementGuard(), integrityKey, clock);
        EntitlementRuntimeStatus status = authority.initializeAndRequireStartup(InstallationProfile.PRO);
        require(status.phase() == EntitlementRuntimePhase.ACTIVE, "paid activation is not active");
        require(status.acceptedSequence() == 1, "paid sequence was not preserved");
        authority.requireMutation();

        proofs.delete(identity);
        expect(ClockRollbackException.class,
                () -> authority.initializeAndRequireStartup(InstallationProfile.PRO));
    }

    private static EntitlementRuntimeAuthority authority(
            MemoryRepository repository,
            MemoryProofStore proofs,
            CapabilityCatalog capabilities,
            QuotaCatalog quotas,
            InstallationProfile profile,
            MutableClock clock,
            ActivationManifestCodec codec) {
        TrustedKeyStore keys = new InMemoryTrustedKeyStore(Map.of(
                "unused", trustedKey(clock.instant())));
        ActivationContextFactory contexts = contexts(
                capabilities, quotas, profile, repository.identity, keys,
                new InMemoryRevocationRegistry(Map.of(), Map.of()));
        return new EntitlementRuntimeAuthority(
                repository, proofs, new ActivationManifestVerifier(), contexts, codec,
                new TrustedTimeGuard(), new LiteEvaluationPolicy(), new EntitlementGuard(),
                integrityKey(), clock);
    }

    private static ActivationContextFactory contexts(
            CapabilityCatalog capabilities,
            QuotaCatalog quotas,
            InstallationProfile profile,
            InstallationIdentity identity,
            TrustedKeyStore keys,
            RevocationRegistry revocations) {
        return (actualIdentity, sequence, evaluatedAt) -> new ActivationValidationContext(
                actualIdentity, "customer-1", profile, "2.0.0-draft.20",
                capabilities, quotas, sequence, keys, revocations, evaluatedAt);
    }

    private static ActivationManifest signedManifest(
            InstallationIdentity identity,
            CapabilityCatalog capabilities,
            QuotaCatalog quotas,
            KeyPair pair,
            Instant now) throws Exception {
        Map<String, Long> limits = quotas.allocate(
                InstallationProfile.PRO, AllocationTier.STANDARD, "2.0.0-draft.20", Map.of()).limits();
        Set<String> entitled = capabilities.codes().stream()
                .map(capabilities::find)
                .filter(definition -> definition.allowedProfiles().contains(InstallationProfile.PRO))
                .map(definition -> definition.code().value())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        ActivationManifestPayload payload = new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA,
                DomainIdentifier.parse("0194fd2d-7c5a-7abc-8def-0123456789ac"),
                new CustomerIdentity("customer-1", "InfraNexum Test"),
                new ManifestInstallation(identity.installationId(), identity.fingerprintVersion(), identity.fingerprint()),
                InstallationProfile.PRO,
                AllocationTier.STANDARD,
                "2.0.0-draft.20",
                limits.get("rsot.managed_hosts.max"),
                entitled,
                limits,
                now,
                now.plusSeconds(86400L * 30),
                30,
                now.minusSeconds(1),
                "test-issuer",
                1,
                "test-key");
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(payload.canonicalBytes());
        return new ActivationManifest(payload, Base64.getEncoder().encodeToString(signer.sign()));
    }

    private static InstallationIdentity identity(Instant createdAt) {
        return new InstallationIdentity(
                DomainIdentifier.parse("0194fd2d-7c5a-7abc-8def-0123456789ab"), "v1", "a".repeat(64), createdAt);
    }

    private static SecretKey integrityKey() {
        return new SecretKeySpec(new byte[32], "HmacSHA256");
    }

    private static TrustedKey trustedKey(Instant now) {
        try {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new TrustedKey("unused", pair.getPublic(), now.minusSeconds(60), now.plusSeconds(60));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static <T extends Throwable> void expect(Class<T> type, ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) {
                return;
            }
            throw new AssertionError("unexpected exception", error);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneId.of("UTC").equals(zone)) {
                throw new IllegalArgumentException("only UTC is supported by the smoke clock");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class MemoryProofStore implements IndependentIntegrityProofStore {
        private IntegrityProof proof;

        @Override
        public Optional<IntegrityProof> load(InstallationIdentity identity) {
            return Optional.ofNullable(proof);
        }

        @Override
        public void store(IntegrityProof value) {
            proof = value;
        }

        @Override
        public void delete(InstallationIdentity identity) {
            proof = null;
        }
    }

    private static final class MemoryRepository implements EntitlementRuntimeRepository {
        private final InstallationIdentity identity;
        private IntegrityProof proof;
        private EntitlementStateRecord state;
        private String document;

        MemoryRepository(InstallationIdentity identity) {
            this.identity = identity;
        }

        @Override
        public Optional<InstallationIdentity> installationIdentity() {
            return Optional.of(identity);
        }

        @Override
        public AcceptedSequence acceptedSequence(InstallationIdentity value) {
            return state == null ? AcceptedSequence.none() : state.acceptedSequence();
        }

        @Override
        public Optional<IntegrityProof> databaseProof(InstallationIdentity value) {
            return Optional.ofNullable(proof);
        }

        @Override
        public void accept(
                InstallationIdentity value,
                ActivationManifest manifest,
                ActivationVerificationResult result,
                IntegrityProof databaseProof,
                Instant acceptedAt) {
            proof = databaseProof;
            document = manifest.canonicalDocument();
            state = new EntitlementStateRecord(
                    result.payload().profile(), result.payload().allocationTier(), null,
                    databaseProof.lastReliableAt(), databaseProof.generation(),
                    new AcceptedSequence(result.payload().sequence(), result.payload().activationId()),
                    EntitlementRuntimePhase.ACTIVE, result.payload().validUntil(), result.graceUntil(), acceptedAt);
        }

        @Override
        public Optional<EntitlementStateRecord> entitlementState(InstallationIdentity value) {
            return Optional.ofNullable(state);
        }

        @Override
        public Optional<String> acceptedManifestDocument(InstallationIdentity value) {
            return Optional.ofNullable(document);
        }

        @Override
        public void initializeLite(
                InstallationIdentity value, IntegrityProof databaseProof, Instant initializedAt) {
            proof = databaseProof;
            state = new EntitlementStateRecord(
                    InstallationProfile.LITE, AllocationTier.STANDARD,
                    databaseProof.evaluationStartedAt(), databaseProof.lastReliableAt(),
                    databaseProof.generation(), AcceptedSequence.none(),
                    EntitlementRuntimePhase.EVALUATION, null, null, initializedAt);
        }

        @Override
        public void updateRuntimeState(
                InstallationIdentity value,
                EntitlementRuntimeStatus status,
                IntegrityProof databaseProof,
                Instant updatedAt) {
            proof = databaseProof;
            state = new EntitlementStateRecord(
                    status.profile(), status.allocationTier(), status.evaluationStartedAt(),
                    databaseProof.lastReliableAt(), databaseProof.generation(),
                    new AcceptedSequence(status.acceptedSequence(), status.acceptedActivationId()),
                    status.phase(), status.validUntil(), status.graceUntil(), updatedAt);
        }
    }
}
