package io.infranexum.core.capabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exhaustive quota catalogue, value-object and arithmetic boundary coverage. */
class QuotaCoverageTest {
    private static final String VERSION = "2.0.0-draft.20";
    private static final String HEADER = String.join(",",
            "component", "quota_key", "unit", "quota_class", "generator_adjustable", "lite_fixed",
            "pro_standard", "pro_advanced_ceiling", "enterprise_standard", "enterprise_ultimate_ceiling",
            "scope", "enforcement");

    @Test
    void allocationCoversEveryProfileTierAndDefaultOverrideBranch() {
        QuotaCatalog catalog = QuotaCatalog.loadEmbedded(VERSION);
        assertEquals(VERSION, catalog.version());
        assertTrue(catalog.keys().contains("rsot.managed_hosts.max"));
        assertNotNull(catalog.require("rsot.managed_hosts.max"));

        assertEquals(200, catalog.allocate(InstallationProfile.LITE, AllocationTier.STANDARD, VERSION, Map.of())
                .limit("rsot.managed_hosts.max"));
        assertEquals(25_000, catalog.allocate(InstallationProfile.PRO, AllocationTier.STANDARD, VERSION, Map.of())
                .limit("rsot.managed_hosts.max"));
        assertEquals(20_000, catalog.allocate(InstallationProfile.PRO, AllocationTier.STANDARD, VERSION,
                Map.of("rsot.managed_hosts.max", 20_000L)).limit("rsot.managed_hosts.max"));
        assertEquals(25_000, catalog.allocate(InstallationProfile.PRO, AllocationTier.ADVANCED, VERSION, Map.of())
                .limit("rsot.managed_hosts.max"));
        assertEquals(100_000, catalog.allocate(InstallationProfile.PRO, AllocationTier.ADVANCED, VERSION,
                Map.of("rsot.managed_hosts.max", 100_000L)).limit("rsot.managed_hosts.max"));
        assertEquals(1_000_000, catalog.allocate(InstallationProfile.ENTERPRISE, AllocationTier.STANDARD, VERSION, Map.of())
                .limit("rsot.managed_hosts.max"));
        assertEquals(900_000, catalog.allocate(InstallationProfile.ENTERPRISE, AllocationTier.STANDARD, VERSION,
                Map.of("rsot.managed_hosts.max", 900_000L)).limit("rsot.managed_hosts.max"));
        assertEquals(1_000_000, catalog.allocate(InstallationProfile.ENTERPRISE, AllocationTier.ULTIMATE, VERSION, Map.of())
                .limit("rsot.managed_hosts.max"));
        assertEquals(5_000_000, catalog.allocate(InstallationProfile.ENTERPRISE, AllocationTier.ULTIMATE, VERSION,
                Map.of("rsot.managed_hosts.max", 5_000_000L)).limit("rsot.managed_hosts.max"));

        // Architectural quotas exercise the fixed-value switch for every profile.
        String architectural = "deployment.server.nodes_total.max";
        assertTrue(catalog.allocate(InstallationProfile.LITE, AllocationTier.STANDARD, VERSION, Map.of()).limit(architectural) >= 0);
        assertTrue(catalog.allocate(InstallationProfile.PRO, AllocationTier.STANDARD, VERSION, Map.of()).limit(architectural) >= 0);
        assertTrue(catalog.allocate(InstallationProfile.ENTERPRISE, AllocationTier.STANDARD, VERSION, Map.of()).limit(architectural) >= 0);
    }

    @Test
    void quotaCatalogueRejectsEveryInvalidAllocationDimension() {
        QuotaCatalog catalog = QuotaCatalog.loadEmbedded(VERSION);
        assertThrows(NullPointerException.class, () -> catalog.require(null));
        assertThrows(IllegalArgumentException.class, () -> catalog.require("unknown.quota.max"));
        assertThrows(NullPointerException.class, () -> catalog.allocate(null, AllocationTier.STANDARD, VERSION, Map.of()));
        assertThrows(NullPointerException.class, () -> catalog.allocate(InstallationProfile.PRO, null, VERSION, Map.of()));
        assertThrows(NullPointerException.class, () -> catalog.allocate(InstallationProfile.PRO, AllocationTier.STANDARD, VERSION, null));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(InstallationProfile.PRO, AllocationTier.STANDARD, "wrong", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(InstallationProfile.LITE, AllocationTier.ADVANCED, VERSION, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(InstallationProfile.PRO, AllocationTier.ULTIMATE, VERSION, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(InstallationProfile.ENTERPRISE, AllocationTier.ADVANCED, VERSION, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(InstallationProfile.PRO, AllocationTier.STANDARD, VERSION,
                Map.of("unknown.quota.max", 1L)));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(InstallationProfile.LITE, AllocationTier.STANDARD, VERSION,
                Map.of("rsot.managed_hosts.max", 1L)));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(InstallationProfile.PRO, AllocationTier.STANDARD, VERSION,
                Map.of("rsot.managed_hosts.max", -1L)));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(InstallationProfile.PRO, AllocationTier.STANDARD, VERSION,
                Map.of("rsot.managed_hosts.max", 25_001L)));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(InstallationProfile.PRO, AllocationTier.ADVANCED, VERSION,
                Map.of("rsot.managed_hosts.max", 24_999L)));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(InstallationProfile.PRO, AllocationTier.ADVANCED, VERSION,
                Map.of("rsot.managed_hosts.max", 100_001L)));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(InstallationProfile.ENTERPRISE, AllocationTier.STANDARD, VERSION,
                Map.of("rsot.managed_hosts.max", 1_000_001L)));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(InstallationProfile.ENTERPRISE, AllocationTier.ULTIMATE, VERSION,
                Map.of("rsot.managed_hosts.max", 999_999L)));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(InstallationProfile.ENTERPRISE, AllocationTier.ULTIMATE, VERSION,
                Map.of("rsot.managed_hosts.max", 5_000_001L)));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(InstallationProfile.PRO, AllocationTier.ADVANCED, VERSION,
                Map.of("deployment.server.nodes_total.max", 3L)));
    }

    @Test
    void quotaValueObjectsRejectMalformedStateAndGuardsPreserveDecision() {
        assertThrows(NullPointerException.class, () -> new QuotaAllocationPlan(null, InstallationProfile.PRO, AllocationTier.STANDARD, Map.of("x", 1L)));
        assertThrows(NullPointerException.class, () -> new QuotaAllocationPlan("v1", null, AllocationTier.STANDARD, Map.of("x", 1L)));
        assertThrows(NullPointerException.class, () -> new QuotaAllocationPlan("v1", InstallationProfile.PRO, null, Map.of("x", 1L)));
        assertThrows(NullPointerException.class, () -> new QuotaAllocationPlan("v1", InstallationProfile.PRO, AllocationTier.STANDARD, null));
        assertThrows(IllegalArgumentException.class, () -> new QuotaAllocationPlan(" ", InstallationProfile.PRO, AllocationTier.STANDARD, Map.of("x", 1L)));
        assertThrows(IllegalArgumentException.class, () -> new QuotaAllocationPlan("v1", InstallationProfile.PRO, AllocationTier.STANDARD, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new QuotaAllocationPlan("v1", InstallationProfile.PRO, AllocationTier.STANDARD, Map.of("x", -1L)));

        QuotaAllocationPlan plan = new QuotaAllocationPlan("v1", InstallationProfile.PRO, AllocationTier.STANDARD, Map.of("x", 10L));
        assertEquals(10L, plan.limit("x"));
        assertThrows(NullPointerException.class, () -> plan.limit(null));
        assertThrows(IllegalArgumentException.class, () -> plan.limit("missing"));

        QuotaDecision valid = new QuotaDecision("x", 10, 1, 1, 2, true, QuotaUsageLevel.NORMAL, "OK");
        assertEquals("x", valid.quotaKey());
        assertThrows(NullPointerException.class, () -> new QuotaDecision(null, 1, 0, 0, 0, true, QuotaUsageLevel.NORMAL, "OK"));
        assertThrows(NullPointerException.class, () -> new QuotaDecision("x", 1, 0, 0, 0, true, null, "OK"));
        assertThrows(NullPointerException.class, () -> new QuotaDecision("x", 1, 0, 0, 0, true, QuotaUsageLevel.NORMAL, null));
        for (long[] values : new long[][] {{-1, 0, 0, 0}, {1, -1, 0, 0}, {1, 0, -1, 0}, {1, 0, 0, -1}}) {
            assertThrows(IllegalArgumentException.class, () -> new QuotaDecision(
                    "x", values[0], values[1], values[2], values[3], false, QuotaUsageLevel.NORMAL, "NO"));
        }
        assertThrows(IllegalArgumentException.class, () -> new QuotaDecision(" ", 1, 0, 0, 0, true, QuotaUsageLevel.NORMAL, "OK"));

        QuotaDecision denied = new QuotaDecision("x", 1, 1, 1, 2, false, QuotaUsageLevel.EXCEEDED, "NO");
        QuotaExceededException failure = assertThrows(QuotaExceededException.class, () -> QuotaGuard.requireAllowed(denied));
        assertEquals(denied, failure.decision());
        assertNotNull(failure.getMessage());
        assertThrows(NullPointerException.class, () -> QuotaGuard.requireAllowed(null));
        assertThrows(NullPointerException.class, () -> new QuotaExceededException(null));
    }

    @Test
    void quotaPolicyHandlesAllThresholdsAndLongRangeWithoutOverflow() {
        QuotaPolicy policy = new QuotaPolicy();
        QuotaAllocationPlan plan = new QuotaAllocationPlan("v1", InstallationProfile.PRO, AllocationTier.STANDARD,
                Map.of("x", 100L, "zero", 0L, "huge", Long.MAX_VALUE));
        assertEquals(QuotaUsageLevel.NORMAL, policy.evaluate(plan, "x", 79, 0).usageLevel());
        assertEquals(QuotaUsageLevel.INFORMATION, policy.evaluate(plan, "x", 80, 0).usageLevel());
        assertEquals(QuotaUsageLevel.WARNING, policy.evaluate(plan, "x", 90, 0).usageLevel());
        assertEquals(QuotaUsageLevel.EXHAUSTED, policy.evaluate(plan, "x", 100, 0).usageLevel());
        assertEquals(QuotaUsageLevel.EXCEEDED, policy.evaluate(plan, "x", 101, 0).usageLevel());
        assertEquals(QuotaUsageLevel.EXHAUSTED, policy.evaluate(plan, "zero", 0, 0).usageLevel());
        assertEquals(QuotaUsageLevel.EXCEEDED, policy.evaluate(plan, "zero", 1, 0).usageLevel());
        assertFalse(policy.evaluate(plan, "x", Long.MAX_VALUE, 1).allowed());
        assertEquals("QUOTA_ARITHMETIC_OVERFLOW", policy.evaluate(plan, "x", Long.MAX_VALUE, 1).reasonCode());
        assertEquals(QuotaUsageLevel.WARNING, policy.evaluate(plan, "huge", Long.MAX_VALUE - 1, 0).usageLevel());
        assertThrows(NullPointerException.class, () -> policy.evaluate(null, "x", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> policy.evaluate(plan, "x", -1, 0));
        assertThrows(IllegalArgumentException.class, () -> policy.evaluate(plan, "x", 0, -1));
    }

    @Test
    void quotaDefinitionAndFileCatalogueValidationCoverAllDefensiveBranches() throws IOException {
        QuotaDefinition valid = new QuotaDefinition(
                "test", "test.objects.max", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, 20, 100, 200, "installation", "hard");
        assertEquals("test.objects.max", valid.key());
        assertThrows(NullPointerException.class, () -> new QuotaDefinition(
                null, "test.objects.max", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, 20, 100, 200, "installation", "hard"));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDefinition(
                " ", "test.objects.max", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, 20, 100, 200, "installation", "hard"));
        assertThrows(NullPointerException.class, () -> new QuotaDefinition(
                "test", null, "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, 20, 100, 200, "installation", "hard"));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDefinition(
                "test", " ", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, 20, 100, 200, "installation", "hard"));
        assertThrows(NullPointerException.class, () -> new QuotaDefinition(
                "test", "test.objects.max", null, QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, 20, 100, 200, "installation", "hard"));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDefinition(
                "test", "test.objects.max", " ", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, 20, 100, 200, "installation", "hard"));
        assertThrows(NullPointerException.class, () -> new QuotaDefinition(
                "test", "test.objects.max", "objects", null, true,
                1, 10, 20, 100, 200, "installation", "hard"));
        assertThrows(NullPointerException.class, () -> new QuotaDefinition(
                "test", "test.objects.max", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, 20, 100, 200, null, "hard"));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDefinition(
                "test", "test.objects.max", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, 20, 100, 200, " ", "hard"));
        assertThrows(NullPointerException.class, () -> new QuotaDefinition(
                "test", "test.objects.max", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, 20, 100, 200, "installation", null));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDefinition(
                "test", "test.objects.max", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, 20, 100, 200, "installation", " "));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDefinition(
                "test", "wrong.objects.max", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, 20, 100, 200, "installation", "hard"));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDefinition(
                "test", "test.Invalid", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, 20, 100, 200, "installation", "hard"));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDefinition(
                "test", "test.objects.max", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                -1, 10, 20, 100, 200, "installation", "hard"));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDefinition(
                "test", "test.objects.max", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, -1, 20, 100, 200, "installation", "hard"));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDefinition(
                "test", "test.objects.max", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, -1, 100, 200, "installation", "hard"));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDefinition(
                "test", "test.objects.max", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, 20, -1, 200, "installation", "hard"));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDefinition(
                "test", "test.objects.max", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, 20, 100, -1, "installation", "hard"));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDefinition(
                "test", "test.objects.max", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 20, 10, 100, 200, "installation", "hard"));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDefinition(
                "test", "test.objects.max", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, 20, 200, 100, "installation", "hard"));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDefinition(
                "test", "test.objects.max", "objects", QuotaClass.ARCHITECTURAL_FIXED, true,
                1, 10, 20, 100, 200, "installation", "hard"));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDefinition(
                "test", "test.objects.max", "objects", QuotaClass.COMMERCIAL_SCALABLE, false,
                1, 10, 20, 100, 200, "installation", "hard"));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDefinition(
                "test", "test.objects.max", "objects", QuotaClass.COMMERCIAL_SCALABLE, true,
                1, 10, 50, 100, 200, "installation", "hard"));

        assertQuotaLoadFails("");
        assertQuotaLoadFails("bad,columns\n1,2\n");
        assertQuotaLoadFails(row("test", "test.a.max", "COMMERCIAL_SCALABLE", "maybe", "1", "10", "20", "100", "200"));
        assertQuotaLoadFails(row("test", "test.a.max", "COMMERCIAL_SCALABLE", "true", "x", "10", "20", "100", "200"));
        assertQuotaLoadFails(row("test", "test.a.max", "UNKNOWN", "true", "1", "10", "20", "100", "200"));
        assertQuotaLoadFails(row("test", "test.a.max", "COMMERCIAL_SCALABLE", "true", "1", "10", "20", "100", "200")
                + rowData("test", "test.a.max", "COMMERCIAL_SCALABLE", "true", "1", "10", "20", "100", "200"));

        Path validPath = Files.createTempFile("infranexum-quota-", ".csv");
        try {
            Files.writeString(validPath, row("test", "test.a.max", "COMMERCIAL_SCALABLE", "true", "1", "10", "20", "100", "200"), StandardCharsets.UTF_8);
            assertThrows(NullPointerException.class, () -> QuotaCatalog.load(null, validPath));
            assertThrows(IllegalArgumentException.class, () -> QuotaCatalog.load(" ", validPath));
        } finally {
            Files.deleteIfExists(validPath);
        }
        assertThrows(IllegalArgumentException.class,
                () -> QuotaCatalog.load("v1", Path.of("/definitely/missing/infranexum-quota.csv")));
    }

    private static void assertQuotaLoadFails(String content) throws IOException {
        Path path = Files.createTempFile("infranexum-quota-invalid-", ".csv");
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> QuotaCatalog.load("v1", path));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static String row(
            String component, String key, String quotaClass, String adjustable,
            String lite, String pro, String advanced, String enterprise, String ultimate) {
        return HEADER + "\n" + rowData(component, key, quotaClass, adjustable, lite, pro, advanced, enterprise, ultimate);
    }

    private static String rowData(
            String component, String key, String quotaClass, String adjustable,
            String lite, String pro, String advanced, String enterprise, String ultimate) {
        return String.join(",", component, key, "objects", quotaClass, adjustable, lite, pro, advanced,
                enterprise, ultimate, "installation", "hard") + "\n";
    }
}
