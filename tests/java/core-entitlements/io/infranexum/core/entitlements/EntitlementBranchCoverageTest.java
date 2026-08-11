package io.infranexum.core.entitlements;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.capabilities.ActivationState;
import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.capabilities.QuotaAllocationPlan;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.AbstractMap;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Branch-oriented regression tests for security and lifecycle paths that must remain reachable. */
final class EntitlementBranchCoverageTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String FP = "a".repeat(64);

    @Test
    void canonicalJsonCoversPrimitiveAndEscapeBranches() {
        assertEquals("[1,2,3,4,5]", CanonicalJson.string(List.of(
                Byte.valueOf((byte) 1), Short.valueOf((short) 2), Integer.valueOf(3), Long.valueOf(4), BigInteger.valueOf(5))));
        assertEquals("\"\\b\\f\\n\\r\\t\\\"\\\\\\u0001\"", CanonicalJson.string("\b\f\n\r\t\"\\\u0001"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.string(Float.valueOf(1.0f)));

        Map<String, Object> duplicateEntries = new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                Set<Entry<String, Object>> entries = new LinkedHashSet<>();
                entries.add(Map.entry("duplicate", 1));
                entries.add(Map.entry("duplicate", 2));
                return entries;
            }
        };
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.string(duplicateEntries));
    }

    @Test
    void manifestPayloadCoversTierAndValidationBranches() {
        assertEquals(AllocationTier.ADVANCED, payload(InstallationProfile.PRO, AllocationTier.ADVANCED).allocationTier());
        assertEquals(AllocationTier.STANDARD, payload(InstallationProfile.ENTERPRISE, AllocationTier.STANDARD).allocationTier());
        assertEquals(AllocationTier.ULTIMATE, payload(InstallationProfile.ENTERPRISE, AllocationTier.ULTIMATE).allocationTier());
        assertThrows(IllegalArgumentException.class, () -> payload(InstallationProfile.PRO, AllocationTier.ULTIMATE));
        assertThrows(IllegalArgumentException.class, () -> payload(InstallationProfile.ENTERPRISE, AllocationTier.ADVANCED));
        assertThrows(IllegalArgumentException.class, () -> payload(InstallationProfile.LITE, AllocationTier.STANDARD));

        ActivationManifestPayload base = payload(InstallationProfile.PRO, AllocationTier.STANDARD);
        assertThrows(IllegalArgumentException.class, () -> copy(base, " ", base.issuer(), base.keyId(), base.capabilities(), base.quotas(), base.validFrom(), base.validUntil(), base.issuedAt(), base.sequence()));
        assertThrows(IllegalArgumentException.class, () -> copy(base, base.catalogVersion(), " ", base.keyId(), base.capabilities(), base.quotas(), base.validFrom(), base.validUntil(), base.issuedAt(), base.sequence()));
        assertThrows(IllegalArgumentException.class, () -> copy(base, base.catalogVersion(), base.issuer(), " ", base.capabilities(), base.quotas(), base.validFrom(), base.validUntil(), base.issuedAt(), base.sequence()));
        assertThrows(IllegalArgumentException.class, () -> copy(base, base.catalogVersion(), base.issuer(), base.keyId(), Set.of(" "), base.quotas(), base.validFrom(), base.validUntil(), base.issuedAt(), base.sequence()));
        assertThrows(IllegalArgumentException.class, () -> copy(base, base.catalogVersion(), base.issuer(), base.keyId(), base.capabilities(), Map.of("", 1L), base.validFrom(), base.validUntil(), base.issuedAt(), base.sequence()));
        assertThrows(IllegalArgumentException.class, () -> copy(base, base.catalogVersion(), base.issuer(), base.keyId(), base.capabilities(), Map.of("q", -1L), base.validFrom(), base.validUntil(), base.issuedAt(), base.sequence()));
        assertThrows(IllegalArgumentException.class, () -> copy(base, base.catalogVersion(), base.issuer(), base.keyId(), base.capabilities(), base.quotas(), base.validFrom().plusNanos(1), base.validUntil(), base.issuedAt(), base.sequence()));
        assertThrows(IllegalArgumentException.class, () -> copy(base, base.catalogVersion(), base.issuer(), base.keyId(), base.capabilities(), base.quotas(), base.validFrom(), base.validUntil().plusNanos(1), base.issuedAt(), base.sequence()));
        assertThrows(IllegalArgumentException.class, () -> copy(base, base.catalogVersion(), base.issuer(), base.keyId(), base.capabilities(), base.quotas(), base.validFrom(), base.validUntil(), base.issuedAt().plusNanos(1), base.sequence()));
        assertThrows(IllegalArgumentException.class, () -> copy(base, base.catalogVersion(), base.issuer(), base.keyId(), base.capabilities(), base.quotas(), base.validFrom(), base.validFrom().minusSeconds(1), base.issuedAt(), base.sequence()));
        assertThrows(IllegalArgumentException.class, () -> copy(base, base.catalogVersion(), base.issuer(), base.keyId(), base.capabilities(), base.quotas(), base.validFrom(), base.validUntil(), base.validFrom().plusSeconds(1), base.sequence()));
        assertThrows(IllegalArgumentException.class, () -> copy(base, base.catalogVersion(), base.issuer(), base.keyId(), base.capabilities(), base.quotas(), base.validFrom(), base.validUntil(), base.issuedAt(), 0));
    }

    @Test
    void runtimeStatusAndVerificationResultCoverEveryLifecycleState() {
        ActivationManifestPayload payload = payload(InstallationProfile.PRO, AllocationTier.STANDARD);
        QuotaAllocationPlan plan = new QuotaAllocationPlan("catalog", InstallationProfile.PRO, AllocationTier.STANDARD, Map.of("q", 1L));
        InstallationIdentity identity = identity();
        for (ActivationUsageState state : ActivationUsageState.values()) {
            ActivationVerificationResult result = new ActivationVerificationResult(state, payload, plan, Set.of("iam.local-auth"), T0.plusSeconds(120));
            EntitlementRuntimeStatus status = EntitlementRuntimeStatus.from(identity, result, T0.plusSeconds(1));
            assertEquals(state == ActivationUsageState.ACTIVE ? ActivationState.ACTIVE
                    : state == ActivationUsageState.GRACE ? ActivationState.GRACE : ActivationState.LOCKED,
                    result.capabilityActivationState());
            assertEquals(state != ActivationUsageState.HARD_STOPPED, result.permitsServiceStartup());
            assertEquals(state != ActivationUsageState.HARD_STOPPED, result.permitsMutation());
            assertEquals(state == ActivationUsageState.ACTIVE ? EntitlementRuntimePhase.ACTIVE
                    : state == ActivationUsageState.GRACE ? EntitlementRuntimePhase.GRACE : EntitlementRuntimePhase.HARD_STOPPED,
                    status.phase());
        }

        LiteEvaluation migrated = new LiteEvaluation(LiteUsageState.MIGRATED, T0, T0.plusSeconds(10), T0.plusSeconds(20), T0.plusSeconds(1));
        assertFalse(migrated.permitsServiceStartup());
        assertFalse(migrated.permitsMutation());
        assertEquals(ActivationState.NOT_REQUIRED, migrated.capabilityActivationState());
        assertNull(migrated.mutationFailureCode());
        assertThrows(IllegalArgumentException.class, () -> EntitlementRuntimeStatus.from(identity, AllocationTier.STANDARD, migrated));

        DomainIdentifier activationId = payload.activationId();
        assertThrows(IllegalArgumentException.class, () -> runtimeStatus(-1, null, Set.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> runtimeStatus(0, activationId, Set.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> runtimeStatus(1, null, Set.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> runtimeStatus(1, activationId, Set.of(" "), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> runtimeStatus(1, activationId, Set.of("x"), Map.of("", 1L)));
        assertThrows(IllegalArgumentException.class, () -> runtimeStatus(1, activationId, Set.of("x"), Map.of("q", -1L)));
    }

    @Test
    void durableStateCoversEveryLiteAndPaidInvariantBranch() {
        DomainIdentifier activationId = idAt(T0.plusSeconds(2));
        AcceptedSequence accepted = new AcceptedSequence(1, activationId);
        assertNotNull(new EntitlementStateRecord(InstallationProfile.PRO, AllocationTier.STANDARD, null, T0, 1,
                accepted, EntitlementRuntimePhase.ACTIVE, T0.plusSeconds(10), T0.plusSeconds(20), T0));
        assertThrows(NullPointerException.class, () -> new EntitlementStateRecord(InstallationProfile.LITE, AllocationTier.STANDARD,
                null, T0, 1, AcceptedSequence.none(), EntitlementRuntimePhase.EVALUATION, null, null, T0));
        assertThrows(IllegalArgumentException.class, () -> new EntitlementStateRecord(InstallationProfile.LITE, AllocationTier.STANDARD,
                T0, T0, 1, AcceptedSequence.none(), EntitlementRuntimePhase.EVALUATION, T0.plusSeconds(1), null, T0));
        assertThrows(IllegalArgumentException.class, () -> new EntitlementStateRecord(InstallationProfile.LITE, AllocationTier.STANDARD,
                T0, T0, 1, AcceptedSequence.none(), EntitlementRuntimePhase.EVALUATION, null, T0.plusSeconds(1), T0));
        assertThrows(IllegalArgumentException.class, () -> new EntitlementStateRecord(InstallationProfile.PRO, AllocationTier.STANDARD,
                T0, T0, 1, accepted, EntitlementRuntimePhase.ACTIVE, T0.plusSeconds(1), T0.plusSeconds(2), T0));
        assertThrows(IllegalArgumentException.class, () -> new EntitlementStateRecord(InstallationProfile.PRO, AllocationTier.STANDARD,
                null, T0, 1, AcceptedSequence.none(), EntitlementRuntimePhase.ACTIVE, T0.plusSeconds(1), T0.plusSeconds(2), T0));
        assertThrows(IllegalArgumentException.class, () -> new EntitlementStateRecord(InstallationProfile.PRO, AllocationTier.STANDARD,
                null, T0, 1, accepted, EntitlementRuntimePhase.ACTIVE, null, T0.plusSeconds(2), T0));
        assertThrows(IllegalArgumentException.class, () -> new EntitlementStateRecord(InstallationProfile.PRO, AllocationTier.STANDARD,
                null, T0, 1, accepted, EntitlementRuntimePhase.ACTIVE, T0.plusSeconds(1), null, T0));
    }

    @Test
    void trustedTimeDetectsEachEvidenceDivergenceAndGenerationExhaustion() throws Exception {
        InstallationIdentity identity = identity();
        SecretKey key = new SecretKeySpec(new byte[32], "HmacSHA256");
        TrustedTimeGuard guard = new TrustedTimeGuard();
        IntegrityProof left = signedProof(identity, T0, T0.plusSeconds(4), 4, key);
        assertThrows(ClockRollbackException.class, () -> guard.observe(
                new IntegrityProofPair(left, signedProof(identity, T0.plusSeconds(1), T0.plusSeconds(4), 4, key)),
                identity, T0.plusSeconds(5), key));
        assertThrows(ClockRollbackException.class, () -> guard.observe(
                new IntegrityProofPair(left, signedProof(identity, T0, T0.plusSeconds(3), 4, key)),
                identity, T0.plusSeconds(5), key));
        assertThrows(ClockRollbackException.class, () -> guard.observe(
                new IntegrityProofPair(left, signedProof(identity, T0, T0.plusSeconds(4), 5, key)),
                identity, T0.plusSeconds(5), key));
        IntegrityProof exhausted = signedProof(identity, T0, T0.plusSeconds(4), Long.MAX_VALUE, key);
        assertThrows(ClockRollbackException.class, () -> guard.observe(
                new IntegrityProofPair(exhausted, exhausted), identity, T0.plusSeconds(5), key));
        guard.verify(left, identity, key);
        InstallationIdentity sameIdDifferentFingerprint = new InstallationIdentity(identity.installationId(), "v1", "b".repeat(64), T0);
        assertThrows(ClockRollbackException.class, () -> guard.verify(left, sameIdDifferentFingerprint, key));
        assertThrows(IllegalArgumentException.class, () -> guard.initialize(identity, T0.plusNanos(1), key));
        assertThrows(IllegalArgumentException.class, () -> guard.observe(new IntegrityProofPair(left, left), identity, T0.plusNanos(1), key));
    }

    @Test
    void miscellaneousValueObjectsCoverSecurityAndSerializationBranches() throws Exception {
        DomainIdentifier id = idAt(T0);
        InstallationIdentity identity = identity();
        ManifestInstallation binding = new ManifestInstallation(id, "v1", FP);
        assertFalse(binding.matches(new InstallationIdentity(idAt(T0.plusSeconds(1)), "v1", FP, T0)));
        assertFalse(binding.matches(new InstallationIdentity(id, "v1", "b".repeat(64), T0)));
        assertThrows(NullPointerException.class, () -> binding.matches(null));

        var ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        assertThrows(IllegalArgumentException.class, () -> new TrustedKey("rsa", rsa.generateKeyPair().getPublic(), T0, T0.plusSeconds(1)));
        assertThrows(NullPointerException.class, () -> new TrustedKey("k", ed.getPublic(), T0, T0.plusSeconds(1)).isValidAt(null));

        InMemoryRevocationRegistry revocations = new InMemoryRevocationRegistry(Map.of(), Map.of());
        assertFalse(revocations.isKeyRevoked("missing", T0));
        assertFalse(revocations.isActivationRevoked(id, T0));
        assertThrows(NullPointerException.class, () -> revocations.isKeyRevoked(null, T0));
        assertThrows(NullPointerException.class, () -> revocations.isActivationRevoked(null, T0));
        assertThrows(NullPointerException.class, () -> revocations.isKeyRevoked("missing", null));

        assertThrows(NullPointerException.class, () -> InstallationFingerprint.compute("v1", null));
        Map<String, String> nullKey = new java.util.HashMap<>(); nullKey.put(null, "x");
        assertThrows(NullPointerException.class, () -> InstallationFingerprint.compute("v1", nullKey));
        Map<String, String> nullValue = new java.util.HashMap<>(); nullValue.put("x", null);
        assertThrows(NullPointerException.class, () -> InstallationFingerprint.compute("v1", nullValue));
        assertThrows(IllegalArgumentException.class, () -> InstallationFingerprint.compute("v1", Map.of(" ", "x")));
        assertThrows(IllegalArgumentException.class, () -> InstallationFingerprint.compute("v1", Map.of("x\n", "y")));
        assertThrows(IllegalArgumentException.class, () -> InstallationFingerprint.compute("v1", Map.of("x", "y\r")));
        assertThrows(IllegalArgumentException.class, () -> new CustomerIdentity("customer\n", "Customer"));
        assertThrows(IllegalArgumentException.class, () -> new CustomerIdentity("customer", "Customer\r"));
        assertThrows(IllegalArgumentException.class, () -> new InstallationIdentity(id, "v1\n", FP, T0));
        assertThrows(IllegalArgumentException.class, () -> new InstallationIdentity(id, "   ", FP, T0));
        assertThrows(IllegalArgumentException.class, () -> new InstallationIdentity(id, "v1", FP + "\n", T0));

        assertThrows(IllegalArgumentException.class, () -> new IntegrityProof(id, FP, T0.plusNanos(1), T0.plusSeconds(1), 1, macZero()));
        assertThrows(IllegalArgumentException.class, () -> new IntegrityProof(id, FP, T0, T0.plusNanos(1), 1, macZero()));
        assertThrows(NullPointerException.class, () -> new ActivationManifest(null, Base64.getEncoder().encodeToString(new byte[64])));
        assertThrows(NullPointerException.class, () -> new ActivationManifest(payload(InstallationProfile.PRO, AllocationTier.STANDARD), null));

        ActivationValidationException validation = roundTrip(new ActivationValidationException(EntitlementErrorCodes.ACTIVATION_INVALID, "invalid"));
        assertEquals(EntitlementErrorCodes.ACTIVATION_INVALID, validation.code());
        EntitlementAccessException access = roundTrip(new EntitlementAccessException(EntitlementErrorCodes.ACTIVATION_EXPIRED, "expired"));
        assertEquals(EntitlementErrorCodes.ACTIVATION_EXPIRED, access.code());

        assertThrows(NullPointerException.class, () -> new ActivationImportResult(null, 1, T0));
        assertThrows(IllegalArgumentException.class, () -> new ActivationImportResult(ActivationUsageState.ACTIVE, 0, T0));
        assertThrows(NullPointerException.class, () -> new ActivationImportResult(ActivationUsageState.ACTIVE, 1, null));
    }

    private static EntitlementRuntimeStatus runtimeStatus(long sequence, DomainIdentifier activationId, Set<String> capabilities, Map<String, Long> quotas) {
        return new EntitlementRuntimeStatus(idAt(T0), InstallationProfile.PRO, AllocationTier.STANDARD,
                EntitlementRuntimePhase.ACTIVE, T0, null, null, null, T0.plusSeconds(10), T0.plusSeconds(20),
                sequence, activationId, capabilities, quotas, true, true);
    }

    private static ActivationManifestPayload payload(InstallationProfile profile, AllocationTier tier) {
        DomainIdentifier id = idAt(T0);
        return new ActivationManifestPayload(ActivationManifestPayload.SCHEMA, idAt(T0.plusSeconds(1)),
                new CustomerIdentity("customer", "Customer"), new ManifestInstallation(id, "v1", FP), profile, tier,
                "catalog", 0, Set.of("iam.local-auth"), Map.of("q", 0L), T0, T0.plusSeconds(60), 30,
                T0, "issuer", 1, "key");
    }

    private static ActivationManifestPayload copy(ActivationManifestPayload base, String catalog, String issuer, String keyId,
            Set<String> capabilities, Map<String, Long> quotas, Instant validFrom, Instant validUntil, Instant issuedAt, long sequence) {
        return new ActivationManifestPayload(base.schema(), base.activationId(), base.customer(), base.installation(), base.profile(),
                base.allocationTier(), catalog, base.hostLimit(), capabilities, quotas, validFrom, validUntil,
                base.gracePeriodDays(), issuedAt, issuer, sequence, keyId);
    }

    private static InstallationIdentity identity() {
        return new InstallationIdentity(idAt(T0), "v1", FP, T0);
    }

    private static IntegrityProof signedProof(InstallationIdentity identity, Instant started, Instant reliable, long generation, SecretKey key) throws Exception {
        IntegrityProof unsigned = new IntegrityProof(identity.installationId(), identity.fingerprint(), started, reliable,
                generation, macZero());
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(key);
        String mac = Base64.getEncoder().encodeToString(hmac.doFinal(CanonicalJson.bytes(unsigned.unsignedValue())));
        return new IntegrityProof(identity.installationId(), identity.fingerprint(), started, reliable, generation, mac);
    }

    private static String macZero() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(value); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) input.readObject();
        }
    }

    private static DomainIdentifier idAt(Instant instant) {
        return new UuidV7Generator(Clock.fixed(instant, ZoneOffset.UTC), new java.security.SecureRandom()).next();
    }
}
