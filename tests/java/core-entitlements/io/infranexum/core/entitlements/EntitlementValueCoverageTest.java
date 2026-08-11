package io.infranexum.core.entitlements;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Exhaustive boundary tests for entitlement value objects and in-memory adapters. */
class EntitlementValueCoverageTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void acceptedSequenceValidatesIdentityAndOrdering() {
        DomainIdentifier id = idAt(T0);
        AcceptedSequence none = AcceptedSequence.none();
        assertEquals(0L, none.value());
        assertTrue(none.accepts(1, id));
        assertFalse(none.accepts(0, id));
        AcceptedSequence current = new AcceptedSequence(2, id);
        assertTrue(current.accepts(3, id));
        assertTrue(current.accepts(2, id));
        assertFalse(current.accepts(2, idAt(T0.plusSeconds(1))));
        assertFalse(current.accepts(1, id));
        assertThrows(IllegalArgumentException.class, () -> new AcceptedSequence(-1, null));
        assertThrows(IllegalArgumentException.class, () -> new AcceptedSequence(0, id));
        assertThrows(NullPointerException.class, () -> new AcceptedSequence(1, null));
    }

    @Test
    void customerManifestAndTrustedKeyRejectMalformedInput() throws Exception {
        assertEquals("c", new CustomerIdentity(" c ", " Customer ").customerId());
        assertThrows(NullPointerException.class, () -> new CustomerIdentity(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> new CustomerIdentity(" ", "x"));
        assertThrows(IllegalArgumentException.class, () -> new CustomerIdentity("c", " "));

        DomainIdentifier id = idAt(T0);
        assertTrue(new ManifestInstallation(id, "v1", "a".repeat(64))
                .matches(new InstallationIdentity(id, "v1", "a".repeat(64), T0)));
        assertFalse(new ManifestInstallation(id, "v1", "a".repeat(64))
                .matches(new InstallationIdentity(id, "v2", "a".repeat(64), T0)));

        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        TrustedKey key = new TrustedKey(" key ", pair.getPublic(), T0, T0.plusSeconds(2));
        assertTrue(key.isValidAt(T0));
        assertTrue(key.isValidAt(T0.plusSeconds(1)));
        assertFalse(key.isValidAt(T0.minusSeconds(1)));
        assertFalse(key.isValidAt(T0.plusSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new TrustedKey(" ", pair.getPublic(), T0, T0.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new TrustedKey("k", pair.getPublic(), T0, T0));
    }

    @Test
    void inMemoryStoresAreImmutableAndRespectRevocationTime() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        TrustedKey key = new TrustedKey("k", pair.getPublic(), T0, T0.plusSeconds(10));
        InMemoryTrustedKeyStore store = new InMemoryTrustedKeyStore(Map.of("k", key));
        assertSame(key, store.find("k").orElseThrow());
        assertTrue(store.find("missing").isEmpty());
        assertThrows(NullPointerException.class, () -> store.find(null));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryTrustedKeyStore(Map.of("different", key)));

        DomainIdentifier activationId = idAt(T0);
        InMemoryRevocationRegistry revocations = new InMemoryRevocationRegistry(
                Map.of("k", T0.plusSeconds(2)), Map.of(activationId, T0.plusSeconds(3)));
        assertFalse(revocations.isKeyRevoked("k", T0.plusSeconds(1)));
        assertTrue(revocations.isKeyRevoked("k", T0.plusSeconds(2)));
        assertFalse(revocations.isActivationRevoked(activationId, T0.plusSeconds(2)));
        assertTrue(revocations.isActivationRevoked(activationId, T0.plusSeconds(3)));
    }

    @Test
    void integrityProofValidatesShapeAndTrustedTimeDetectsTampering() {
        InstallationIdentity identity = new InstallationIdentity(idAt(T0), "v1", "a".repeat(64), T0);
        var secret = new SecretKeySpec(new byte[32], "HmacSHA256");
        TrustedTimeGuard guard = new TrustedTimeGuard();
        IntegrityProofPair pair = guard.initialize(identity, T0, secret);
        guard.verify(pair.databaseProof(), identity, secret);
        assertEquals(1L, pair.databaseProof().generation());

        assertThrows(IllegalArgumentException.class, () -> new IntegrityProof(identity.installationId(), identity.fingerprint(),
                T0.plusSeconds(1), T0, 1, Base64.getEncoder().encodeToString(new byte[32])));
        assertThrows(IllegalArgumentException.class, () -> new IntegrityProof(identity.installationId(), identity.fingerprint(),
                T0, T0, 0, Base64.getEncoder().encodeToString(new byte[32])));
        assertThrows(IllegalArgumentException.class, () -> new IntegrityProof(identity.installationId(), identity.fingerprint(),
                T0, T0, 1, "not-base64"));
        assertThrows(IllegalArgumentException.class, () -> new IntegrityProof(identity.installationId(), identity.fingerprint(),
                T0, T0, 1, Base64.getEncoder().encodeToString(new byte[31])));

        InstallationIdentity other = new InstallationIdentity(idAt(T0.plusSeconds(1)), "v1", "b".repeat(64), T0);
        assertThrows(ClockRollbackException.class, () -> guard.verify(pair.databaseProof(), other, secret));
        IntegrityProof tampered = new IntegrityProof(identity.installationId(), identity.fingerprint(), T0, T0, 1,
                Base64.getEncoder().encodeToString(new byte[32]));
        assertThrows(ClockRollbackException.class, () -> guard.verify(tampered, identity, secret));
        assertThrows(ClockRollbackException.class,
                () -> guard.observe(pair, identity, T0.minusSeconds(1), secret));

        IntegrityProof divergent = new IntegrityProof(identity.installationId(), identity.fingerprint(), T0,
                T0.plusSeconds(1), 2, pair.databaseProof().mac());
        assertThrows(ClockRollbackException.class,
                () -> guard.observe(new IntegrityProofPair(pair.databaseProof(), divergent), identity, T0.plusSeconds(1), secret));
    }

    @Test
    void stateAndRuntimeStatusEnforceProfileInvariants() {
        DomainIdentifier installationId = idAt(T0);
        InstallationIdentity identity = new InstallationIdentity(installationId, "v1", "a".repeat(64), T0);
        EntitlementStateRecord lite = new EntitlementStateRecord(InstallationProfile.LITE, AllocationTier.STANDARD,
                T0, T0, 1, AcceptedSequence.none(), EntitlementRuntimePhase.EVALUATION, null, null, T0);
        assertNull(lite.acceptedActivationId());
        assertThrows(IllegalArgumentException.class, () -> new EntitlementStateRecord(InstallationProfile.LITE,
                AllocationTier.STANDARD, T0, T0, 0, AcceptedSequence.none(), EntitlementRuntimePhase.EVALUATION, null, null, T0));
        assertThrows(IllegalArgumentException.class, () -> new EntitlementStateRecord(InstallationProfile.LITE,
                AllocationTier.STANDARD, T0, T0, 1, new AcceptedSequence(1, idAt(T0.plusSeconds(1))),
                EntitlementRuntimePhase.EVALUATION, null, null, T0));
        assertThrows(IllegalArgumentException.class, () -> new EntitlementStateRecord(InstallationProfile.PRO,
                AllocationTier.STANDARD, null, T0, 1, AcceptedSequence.none(), EntitlementRuntimePhase.ACTIVE,
                T0.plusSeconds(10), T0.plusSeconds(20), T0));

        LiteEvaluation evaluation = new LiteEvaluationPolicy().evaluate(T0, T0);
        EntitlementRuntimeStatus status = EntitlementRuntimeStatus.from(identity, AllocationTier.STANDARD, evaluation);
        assertEquals(EntitlementRuntimePhase.EVALUATION, status.phase());
        assertThrows(IllegalArgumentException.class, () -> new EntitlementRuntimeStatus(installationId,
                InstallationProfile.LITE, AllocationTier.STANDARD, EntitlementRuntimePhase.EVALUATION, T0,
                T0, T0.plusSeconds(1), T0.plusSeconds(2), null, null, -1, null, Set.of(), Map.of(), true, true));
        assertThrows(IllegalArgumentException.class, () -> new EntitlementRuntimeStatus(installationId,
                InstallationProfile.PRO, AllocationTier.STANDARD, EntitlementRuntimePhase.ACTIVE, T0,
                null, null, null, T0.plusSeconds(1), T0.plusSeconds(2), 1, null, Set.of(), Map.of(), true, true));
        assertThrows(IllegalArgumentException.class, () -> new EntitlementRuntimeStatus(installationId,
                InstallationProfile.PRO, AllocationTier.STANDARD, EntitlementRuntimePhase.ACTIVE, T0,
                null, null, null, T0.plusSeconds(1), T0.plusSeconds(2), 1, idAt(T0.plusSeconds(1)),
                Set.of(" "), Map.of(), true, true));
        assertThrows(IllegalArgumentException.class, () -> new EntitlementRuntimeStatus(installationId,
                InstallationProfile.PRO, AllocationTier.STANDARD, EntitlementRuntimePhase.ACTIVE, T0,
                null, null, null, T0.plusSeconds(1), T0.plusSeconds(2), 1, idAt(T0.plusSeconds(1)),
                Set.of("x"), Map.of("q", -1L), true, true));
    }

    @Test
    void liteEvaluationCoversAllBoundariesAndGuardCodes() {
        LiteEvaluationPolicy policy = new LiteEvaluationPolicy();
        LiteEvaluation active = policy.evaluate(T0, T0.plusSeconds(179L * 24 * 3600));
        LiteEvaluation conversion = policy.evaluate(T0, T0.plusSeconds(180L * 24 * 3600));
        LiteEvaluation stopped = policy.evaluate(T0, T0.plusSeconds(210L * 24 * 3600));
        assertEquals(LiteUsageState.EVALUATION, active.state());
        assertEquals(LiteUsageState.CONVERSION_REQUIRED, conversion.state());
        assertEquals(LiteUsageState.HARD_STOPPED, stopped.state());
        assertTrue(active.permitsMutation());
        assertFalse(conversion.permitsMutation());
        assertTrue(conversion.permitsServiceStartup());
        assertFalse(stopped.permitsServiceStartup());
        assertEquals(EntitlementErrorCodes.LITE_CONVERSION_REQUIRED, conversion.mutationFailureCode());
        assertEquals(EntitlementErrorCodes.LITE_HARD_STOPPED, stopped.mutationFailureCode());
        assertThrows(ClockRollbackException.class, () -> policy.evaluate(T0, T0.minusSeconds(1)));

        EntitlementGuard guard = new EntitlementGuard();
        guard.requireServiceStartup(active);
        guard.requireMutation(active);
        EntitlementAccessException conversionFailure = assertThrows(EntitlementAccessException.class,
                () -> guard.requireMutation(conversion));
        assertEquals(EntitlementErrorCodes.LITE_CONVERSION_REQUIRED, conversionFailure.code());
        EntitlementAccessException hardStop = assertThrows(EntitlementAccessException.class,
                () -> guard.requireServiceStartup(stopped));
        assertEquals(EntitlementErrorCodes.LITE_HARD_STOPPED, hardStop.code());
    }

    private static DomainIdentifier idAt(Instant instant) {
        return new UuidV7Generator(Clock.fixed(instant, ZoneOffset.UTC), new java.security.SecureRandom()).next();
    }
}
