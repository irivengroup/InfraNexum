package io.infranexum.core.entitlements;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.capabilities.QuotaAllocationPlan;
import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class EntitlementGuardTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private final EntitlementGuard guard = new EntitlementGuard();

    @Test
    void enforcesLiteMutationAndStartupBoundaries() {
        LiteEvaluationPolicy policy = new LiteEvaluationPolicy();
        LiteEvaluation active = policy.evaluate(T0, T0.plusSeconds(1));
        LiteEvaluation conversion = policy.evaluate(T0, T0.plusSeconds(180L * 86400));
        LiteEvaluation stopped = policy.evaluate(T0, T0.plusSeconds(210L * 86400));

        assertDoesNotThrow(() -> guard.requireMutation(active));
        assertDoesNotThrow(() -> guard.requireServiceStartup(conversion));
        EntitlementAccessException conversionError =
                assertThrows(EntitlementAccessException.class, () -> guard.requireMutation(conversion));
        assertEquals(EntitlementErrorCodes.LITE_CONVERSION_REQUIRED, conversionError.code());
        EntitlementAccessException stoppedError =
                assertThrows(EntitlementAccessException.class, () -> guard.requireServiceStartup(stopped));
        assertEquals(EntitlementErrorCodes.LITE_HARD_STOPPED, stoppedError.code());
        assertEquals(
                EntitlementErrorCodes.LITE_HARD_STOPPED,
                assertThrows(EntitlementAccessException.class, () -> guard.requireMutation(stopped)).code());
    }

    @Test
    void enforcesPaidHardStopWhileAllowingGrace() {
        ActivationVerificationResult grace = result(ActivationUsageState.GRACE);
        ActivationVerificationResult stopped = result(ActivationUsageState.HARD_STOPPED);

        assertDoesNotThrow(() -> guard.requireServiceStartup(grace));
        assertDoesNotThrow(() -> guard.requireMutation(grace));
        assertEquals(
                EntitlementErrorCodes.ACTIVATION_EXPIRED,
                assertThrows(EntitlementAccessException.class, () -> guard.requireServiceStartup(stopped)).code());
        assertEquals(
                EntitlementErrorCodes.ACTIVATION_EXPIRED,
                assertThrows(EntitlementAccessException.class, () -> guard.requireMutation(stopped)).code());
    }

    @Test
    void rejectsMissingLifecycleDecisions() {
        assertThrows(NullPointerException.class, () -> guard.requireServiceStartup((LiteEvaluation) null));
        assertThrows(NullPointerException.class, () -> guard.requireMutation((LiteEvaluation) null));
        assertThrows(
                NullPointerException.class,
                () -> guard.requireServiceStartup((ActivationVerificationResult) null));
        assertThrows(
                NullPointerException.class,
                () -> guard.requireMutation((ActivationVerificationResult) null));
    }

    private static ActivationVerificationResult result(ActivationUsageState state) {
        DomainIdentifier identifier = DomainIdentifier.parse("018cc251-f400-7000-8000-000000000001");
        ActivationManifestPayload payload = new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA,
                identifier,
                new CustomerIdentity("customer", "Customer"),
                new ManifestInstallation(identifier, "v1", "a".repeat(64)),
                InstallationProfile.PRO,
                AllocationTier.STANDARD,
                "catalog",
                1,
                Set.of(),
                Map.of("rsot.managed_hosts.max", 1L),
                T0,
                T0.plusSeconds(86400),
                30,
                T0,
                "issuer",
                1,
                "key");
        return new ActivationVerificationResult(
                state,
                payload,
                new QuotaAllocationPlan(
                        "catalog",
                        InstallationProfile.PRO,
                        AllocationTier.STANDARD,
                        Map.of("rsot.managed_hosts.max", 1L)),
                Set.of(),
                T0.plusSeconds(31L * 86400));
    }
}
