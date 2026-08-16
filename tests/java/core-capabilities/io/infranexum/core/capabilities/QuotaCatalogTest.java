package io.infranexum.core.capabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class QuotaCatalogTest {
    private static final String VERSION = "2.0.0-draft.21";
    private final QuotaCatalog catalog = QuotaCatalog.loadEmbedded(VERSION);

    @Test
    void normativeCatalogueLoadsAllEntries() {
        assertEquals(119, catalog.size());
        assertEquals(200, catalog.allocate(
                InstallationProfile.LITE, AllocationTier.STANDARD, VERSION, Map.of())
                .limit("rsot.managed_hosts.max"));
        assertEquals(25_000, catalog.allocate(
                InstallationProfile.PRO, AllocationTier.STANDARD, VERSION, Map.of())
                .limit("rsot.managed_hosts.max"));
        assertEquals(1_000_000, catalog.allocate(
                InstallationProfile.ENTERPRISE, AllocationTier.STANDARD, VERSION, Map.of())
                .limit("rsot.managed_hosts.max"));
    }

    @Test
    void commercialOverridesRespectTiersAndCertifiedCeilings() {
        QuotaAllocationPlan advanced = catalog.allocate(
                InstallationProfile.PRO, AllocationTier.ADVANCED, VERSION,
                Map.of("rsot.managed_hosts.max", 100_000L));
        assertEquals(100_000, advanced.limit("rsot.managed_hosts.max"));
        QuotaAllocationPlan ultimate = catalog.allocate(
                InstallationProfile.ENTERPRISE, AllocationTier.ULTIMATE, VERSION,
                Map.of("rsot.managed_hosts.max", 5_000_000L));
        assertEquals(5_000_000, ultimate.limit("rsot.managed_hosts.max"));

        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(
                InstallationProfile.PRO, AllocationTier.ADVANCED, VERSION,
                Map.of("rsot.managed_hosts.max", 100_001L)));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(
                InstallationProfile.ENTERPRISE, AllocationTier.ULTIMATE, VERSION,
                Map.of("rsot.managed_hosts.max", 5_000_001L)));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(
                InstallationProfile.PRO, AllocationTier.STANDARD, VERSION,
                Map.of("rsot.managed_hosts.max", 25_001L)));
    }

    @Test
    void architecturalAndLiteQuotasCannotBeOverridden() {
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(
                InstallationProfile.PRO, AllocationTier.ADVANCED, VERSION,
                Map.of("deployment.server.nodes_total.max", 3L)));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(
                InstallationProfile.LITE, AllocationTier.STANDARD, VERSION,
                Map.of("iam.users.max", 4L)));
    }

    @Test
    void unknownKeysVersionsAndTiersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> catalog.require("unknown.quota.max"));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(
                InstallationProfile.PRO, AllocationTier.STANDARD, "wrong", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(
                InstallationProfile.LITE, AllocationTier.ADVANCED, VERSION, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(
                InstallationProfile.PRO, AllocationTier.ULTIMATE, VERSION, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(
                InstallationProfile.ENTERPRISE, AllocationTier.ADVANCED, VERSION, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> catalog.allocate(
                InstallationProfile.PRO, AllocationTier.STANDARD, VERSION, Map.of("unknown.quota.max", 1L)));
    }
}
