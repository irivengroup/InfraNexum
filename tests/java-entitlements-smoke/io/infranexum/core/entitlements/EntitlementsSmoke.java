package io.infranexum.core.entitlements;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.CapabilityCatalog;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.capabilities.QuotaAllocationPlan;
import io.infranexum.core.capabilities.QuotaCatalog;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.spec.SecretKeySpec;

/** Dependency-free executable acceptance test for activation and Lite lifecycle contracts. */
public final class EntitlementsSmoke {
    private EntitlementsSmoke() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("expected capability and quota catalogue paths");
        }
        String catalogVersion = "2.0.0-draft.21";
        CapabilityCatalog capabilityCatalog = CapabilityCatalog.load(catalogVersion, Path.of(args[0]));
        QuotaCatalog quotaCatalog = QuotaCatalog.load(catalogVersion, Path.of(args[1]));
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        DomainIdentifier installationId = new UuidV7Generator(
                        Clock.fixed(t0, ZoneOffset.UTC), new SecureRandom(new byte[] {1, 2, 3, 4}))
                .next();
        String fingerprint = InstallationFingerprint.compute(
                "v1", Map.of("machine-id", "host-001", "product-uuid", "product-001"));
        assert fingerprint.equals(InstallationFingerprint.compute(
                "v1", Map.of("product-uuid", "product-001", "machine-id", "host-001")));
        InstallationIdentity identity = new InstallationIdentity(installationId, "v1", fingerprint, t0);

        SecretKeySpec integrityKey = new SecretKeySpec(new byte[32], "HmacSHA256");
        TrustedTimeGuard timeGuard = new TrustedTimeGuard();
        IntegrityProofPair initial = timeGuard.initialize(identity, t0, integrityKey);
        IntegrityProofPair observed = timeGuard.observe(initial, identity, t0.plus(1, ChronoUnit.DAYS), integrityKey);
        assert observed.databaseProof().generation() == 2;
        expect(ClockRollbackException.class,
                () -> timeGuard.observe(observed, identity, t0, integrityKey));
        IntegrityProof diverged = new IntegrityProof(
                observed.independentProof().installationId(), observed.independentProof().fingerprint(),
                observed.independentProof().evaluationStartedAt(), observed.independentProof().lastReliableAt(),
                observed.independentProof().generation() + 1, observed.independentProof().mac());
        expect(ClockRollbackException.class,
                () -> timeGuard.observe(new IntegrityProofPair(observed.databaseProof(), diverged),
                        identity, t0.plus(2, ChronoUnit.DAYS), integrityKey));

        LiteEvaluationPolicy lite = new LiteEvaluationPolicy();
        assert lite.evaluate(t0, t0.plus(180, ChronoUnit.DAYS).minusSeconds(1)).state()
                == LiteUsageState.EVALUATION;
        LiteEvaluation conversion = lite.evaluate(t0, t0.plus(180, ChronoUnit.DAYS));
        assert conversion.state() == LiteUsageState.CONVERSION_REQUIRED;
        assert !conversion.permitsMutation() && conversion.permitsServiceStartup();
        LiteEvaluation stopped = lite.evaluate(t0, t0.plus(210, ChronoUnit.DAYS));
        assert stopped.state() == LiteUsageState.HARD_STOPPED && !stopped.permitsServiceStartup();
        EntitlementGuard entitlementGuard = new EntitlementGuard();
        entitlementGuard.requireServiceStartup(conversion);
        expect(EntitlementAccessException.class, () -> entitlementGuard.requireMutation(conversion));
        expect(EntitlementAccessException.class, () -> entitlementGuard.requireServiceStartup(stopped));
        expect(ClockRollbackException.class, () -> lite.evaluate(t0, t0.minusSeconds(1)));

        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        TrustedKey trustedKey = new TrustedKey(
                "commercial-2026-01", keyPair.getPublic(), t0.minus(1, ChronoUnit.DAYS),
                t0.plus(1000, ChronoUnit.DAYS));
        TrustedKeyStore keyStore = new InMemoryTrustedKeyStore(Map.of(trustedKey.keyId(), trustedKey));
        QuotaAllocationPlan quotaPlan = quotaCatalog.allocate(
                InstallationProfile.PRO, AllocationTier.STANDARD, catalogVersion, Map.of());
        Set<String> capabilities = capabilityCatalog.codes().stream()
                .filter(code -> capabilityCatalog.find(code).allowedProfiles().contains(InstallationProfile.PRO))
                .map(Object::toString)
                .collect(Collectors.toUnmodifiableSet());
        DomainIdentifier activationId = new UuidV7Generator(
                        Clock.fixed(t0.plusSeconds(1), ZoneOffset.UTC), new SecureRandom(new byte[] {8, 7, 6, 5}))
                .next();
        ActivationManifestPayload payload = new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA,
                activationId,
                new CustomerIdentity("customer-001", "Example Industries"),
                new ManifestInstallation(identity.installationId(), identity.fingerprintVersion(), identity.fingerprint()),
                InstallationProfile.PRO,
                AllocationTier.STANDARD,
                catalogVersion,
                quotaPlan.limit("rsot.managed_hosts.max"),
                capabilities,
                quotaPlan.limits(),
                t0,
                t0.plus(365, ChronoUnit.DAYS),
                30,
                t0,
                "InfraNexum Commercial Authority",
                1,
                trustedKey.keyId());
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload.canonicalBytes());
        ActivationManifest manifest = new ActivationManifest(
                payload, Base64.getEncoder().encodeToString(signer.sign()));
        ActivationManifestVerifier verifier = new ActivationManifestVerifier();
        ActivationVerificationResult active = verifier.verify(manifest, context(
                identity, capabilityCatalog, quotaCatalog, keyStore, t0.plus(30, ChronoUnit.DAYS),
                AcceptedSequence.none(), new InMemoryRevocationRegistry(Map.of(), Map.of())));
        assert active.state() == ActivationUsageState.ACTIVE && active.permitsMutation();
        assert active.capabilityActivationState().permitsProtectedCapabilities();
        ActivationVerificationResult grace = verifier.verify(manifest, context(
                identity, capabilityCatalog, quotaCatalog, keyStore, payload.validUntil(),
                new AcceptedSequence(1, activationId), new InMemoryRevocationRegistry(Map.of(), Map.of())));
        assert grace.state() == ActivationUsageState.GRACE;
        ActivationVerificationResult hardStopped = verifier.verify(manifest, context(
                identity, capabilityCatalog, quotaCatalog, keyStore, active.graceUntil(),
                new AcceptedSequence(1, activationId), new InMemoryRevocationRegistry(Map.of(), Map.of())));
        assert hardStopped.state() == ActivationUsageState.HARD_STOPPED && !hardStopped.permitsServiceStartup();
        entitlementGuard.requireMutation(active);
        entitlementGuard.requireMutation(grace);
        expect(EntitlementAccessException.class, () -> entitlementGuard.requireMutation(hardStopped));
        expect(EntitlementAccessException.class, () -> entitlementGuard.requireServiceStartup(hardStopped));

        byte[] tampered = manifest.signatureBytes();
        tampered[0] ^= 1;
        expect(ActivationValidationException.class, () -> verifier.verify(
                new ActivationManifest(payload, Base64.getEncoder().encodeToString(tampered)),
                context(identity, capabilityCatalog, quotaCatalog, keyStore, t0.plusSeconds(1),
                        AcceptedSequence.none(), new InMemoryRevocationRegistry(Map.of(), Map.of()))));
        expect(ActivationValidationException.class, () -> verifier.verify(manifest, context(
                identity, capabilityCatalog, quotaCatalog, keyStore, t0.plusSeconds(1),
                new AcceptedSequence(2, activationId), new InMemoryRevocationRegistry(Map.of(), Map.of()))));
        expect(ActivationValidationException.class, () -> verifier.verify(manifest, context(
                identity, capabilityCatalog, quotaCatalog, keyStore, t0.plusSeconds(1),
                AcceptedSequence.none(), new InMemoryRevocationRegistry(Map.of(), Map.of(activationId, t0)))));
        expect(IllegalArgumentException.class, () -> CanonicalJson.string(Map.of("bad", 1.5d)));
        expect(IllegalArgumentException.class, () -> new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA, activationId,
                new CustomerIdentity("c", "n"), payload.installation(), InstallationProfile.LITE,
                AllocationTier.STANDARD, catalogVersion, 0, Set.of(), Map.of(), t0,
                t0.plusSeconds(1), 30, t0, "issuer", 1, "key"));

        assert io.infranexum.core.capabilities.ActivationState.NOT_REQUIRED.permitsProtectedCapabilities();
        System.out.printf(
                "entitlements-smoke: lite=%s/%s active=%s grace=%s hard=%s capabilities=%d quotas=%d%n",
                conversion.state(), stopped.state(), active.state(), grace.state(), hardStopped.state(),
                capabilities.size(), quotaPlan.limits().size());
    }

    private static ActivationValidationContext context(
            InstallationIdentity identity,
            CapabilityCatalog capabilityCatalog,
            QuotaCatalog quotaCatalog,
            TrustedKeyStore keyStore,
            Instant now,
            AcceptedSequence acceptedSequence,
            RevocationRegistry revocations) {
        return new ActivationValidationContext(
                identity, "customer-001", InstallationProfile.PRO, "2.0.0-draft.21",
                capabilityCatalog, quotaCatalog, acceptedSequence, keyStore, revocations, now);
    }

    private static void expect(Class<? extends Throwable> type, ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) {
                return;
            }
            throw new AssertionError("unexpected exception type", error);
        }
        throw new AssertionError("expected exception " + type.getName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
