package io.infranexum.core.capabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class QuotaPolicyTest {
    private final QuotaPolicy policy = new QuotaPolicy();
    private final QuotaAllocationPlan plan = new QuotaAllocationPlan(
            "v1", InstallationProfile.PRO, AllocationTier.STANDARD, Map.of("test.objects.max", 100L, "zero.max", 0L));

    @Test
    void thresholdsAreExplainableAndAllocationAtLimitIsAllowed() {
        assertEquals(QuotaUsageLevel.NORMAL, policy.evaluate(plan, "test.objects.max", 79, 0).usageLevel());
        assertEquals(QuotaUsageLevel.INFORMATION, policy.evaluate(plan, "test.objects.max", 79, 1).usageLevel());
        assertEquals(QuotaUsageLevel.WARNING, policy.evaluate(plan, "test.objects.max", 89, 1).usageLevel());
        QuotaDecision exhausted = policy.evaluate(plan, "test.objects.max", 99, 1);
        assertTrue(exhausted.allowed());
        assertEquals(QuotaUsageLevel.EXHAUSTED, exhausted.usageLevel());
        QuotaDecision blocked = policy.evaluate(plan, "test.objects.max", 100, 1);
        assertFalse(blocked.allowed());
        assertEquals(QuotaUsageLevel.EXCEEDED, blocked.usageLevel());
    }

    @Test
    void nonAugmentativeCorrectionsRemainAllowedAboveReducedLimit() {
        QuotaDecision correction = policy.evaluate(plan, "test.objects.max", 150, 0);
        assertTrue(correction.allowed());
        assertEquals(QuotaUsageLevel.EXCEEDED, correction.usageLevel());
    }

    @Test
    void zeroAndOverflowLimitsFailSafely() {
        assertTrue(policy.evaluate(plan, "zero.max", 0, 0).allowed());
        assertFalse(policy.evaluate(plan, "zero.max", 0, 1).allowed());
        QuotaDecision overflow = policy.evaluate(plan, "test.objects.max", Long.MAX_VALUE, 1);
        assertFalse(overflow.allowed());
        assertEquals("QUOTA_ARITHMETIC_OVERFLOW", overflow.reasonCode());
    }

    @Test
    void guardAndInputValidationAreStrict() {
        QuotaDecision denied = policy.evaluate(plan, "test.objects.max", 100, 1);
        QuotaExceededException failure = assertThrows(
                QuotaExceededException.class, () -> QuotaGuard.requireAllowed(denied));
        assertEquals(denied, failure.decision());
        QuotaGuard.requireAllowed(policy.evaluate(plan, "test.objects.max", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> policy.evaluate(plan, "test.objects.max", -1, 0));
        assertThrows(IllegalArgumentException.class, () -> policy.evaluate(plan, "test.objects.max", 0, -1));
    }
}
