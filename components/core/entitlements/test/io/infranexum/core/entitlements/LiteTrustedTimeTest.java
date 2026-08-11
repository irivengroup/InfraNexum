package io.infranexum.core.entitlements;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.capabilities.ActivationState;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class LiteTrustedTimeTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void liteBoundariesAreExactAndMapToRuntimeGuards() {
        LiteEvaluationPolicy policy = new LiteEvaluationPolicy();
        LiteEvaluation before = policy.evaluate(T0, T0.plus(180, ChronoUnit.DAYS).minusSeconds(1));
        assertEquals(LiteUsageState.EVALUATION, before.state());
        assertTrue(before.permitsMutation());
        assertTrue(before.permitsServiceStartup());
        assertEquals(ActivationState.NOT_REQUIRED, before.capabilityActivationState());
        assertNull(before.mutationFailureCode());

        LiteEvaluation conversion = policy.evaluate(T0, T0.plus(180, ChronoUnit.DAYS));
        assertEquals(LiteUsageState.CONVERSION_REQUIRED, conversion.state());
        assertFalse(conversion.permitsMutation());
        assertTrue(conversion.permitsServiceStartup());
        assertEquals(EntitlementErrorCodes.LITE_CONVERSION_REQUIRED, conversion.mutationFailureCode());

        LiteEvaluation stopped = policy.evaluate(T0, T0.plus(210, ChronoUnit.DAYS));
        assertEquals(LiteUsageState.HARD_STOPPED, stopped.state());
        assertFalse(stopped.permitsMutation());
        assertFalse(stopped.permitsServiceStartup());
        assertEquals(ActivationState.LOCKED, stopped.capabilityActivationState());
        assertEquals(EntitlementErrorCodes.LITE_HARD_STOPPED, stopped.mutationFailureCode());
        assertThrows(ClockRollbackException.class, () -> policy.evaluate(T0, T0.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> policy.evaluate(T0.plusNanos(1), T0.plusSeconds(1)));
    }

    @Test
    void trustedTimeProofsAreBoundVerifiedAndAdvanced() {
        DomainIdentifier id = new UuidV7Generator(Clock.fixed(T0, ZoneOffset.UTC), new java.security.SecureRandom()).next();
        InstallationIdentity identity = new InstallationIdentity(id, "v1", "a".repeat(64), T0);
        SecretKeySpec key = new SecretKeySpec("k".repeat(32).getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
        TrustedTimeGuard guard = new TrustedTimeGuard();
        IntegrityProofPair initial = guard.initialize(identity, T0, key);
        guard.verify(initial.databaseProof(), identity, key);
        IntegrityProofPair next = guard.observe(initial, identity, T0.plusSeconds(1), key);
        assertEquals(2, next.databaseProof().generation());
        assertEquals(T0.plusSeconds(1), next.databaseProof().lastReliableAt());
        assertEquals(next.databaseProof(), next.independentProof());
        assertThrows(ClockRollbackException.class, () -> guard.observe(next, identity, T0, key));

        IntegrityProof badMac = new IntegrityProof(id, identity.fingerprint(), T0, T0.plusSeconds(1), 2,
                Base64.getEncoder().encodeToString(new byte[32]));
        assertThrows(ClockRollbackException.class, () -> guard.verify(badMac, identity, key));
        InstallationIdentity other = new InstallationIdentity(
                new UuidV7Generator(Clock.fixed(T0.plusSeconds(2), ZoneOffset.UTC), new java.security.SecureRandom()).next(),
                "v1", "b".repeat(64), T0);
        assertThrows(ClockRollbackException.class, () -> guard.verify(next.databaseProof(), other, key));

        IntegrityProof divergent = new IntegrityProof(id, identity.fingerprint(), T0, T0.plusSeconds(2), 2,
                next.independentProof().mac());
        assertThrows(ClockRollbackException.class,
                () -> guard.observe(new IntegrityProofPair(next.databaseProof(), divergent), identity,
                        T0.plusSeconds(3), key));
        assertThrows(IllegalArgumentException.class,
                () -> new IntegrityProof(id, identity.fingerprint(), T0, T0.minusSeconds(1), 1,
                        Base64.getEncoder().encodeToString(new byte[32])));
        assertThrows(IllegalArgumentException.class,
                () -> new IntegrityProof(id, identity.fingerprint(), T0, T0, 0,
                        Base64.getEncoder().encodeToString(new byte[32])));
        assertThrows(IllegalArgumentException.class,
                () -> new IntegrityProof(id, identity.fingerprint(), T0, T0, 1, "bad"));
    }
}
