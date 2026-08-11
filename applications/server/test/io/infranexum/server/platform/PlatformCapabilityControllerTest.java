package io.infranexum.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.capabilities.ActivationState;
import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.CapabilityCatalog;
import io.infranexum.core.capabilities.CapabilityCode;
import io.infranexum.core.capabilities.CapabilityEnvironment;
import io.infranexum.core.capabilities.CapabilityRegistry;
import io.infranexum.core.capabilities.DeploymentRole;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.capabilities.InstallationTopology;
import io.infranexum.core.capabilities.QuotaCatalog;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlatformCapabilityControllerTest {
    @Test
    void returnsNoStoreRegistryCapabilityAndQuotaResponses() {
        String version = "2.0.0-draft.20";
        var environment = new CapabilityEnvironment(
                InstallationProfile.LITE, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER, DeploymentRole.WEB), Set.of(),
                Set.of(new CapabilityCode("iam.local-auth"), new CapabilityCode("database.postgresql"),
                        new CapabilityCode("discovery.agentless")),
                Map.of(), Set.of(), ActivationState.NOT_REQUIRED, version, 1);
        var service = new PlatformCapabilityService(
                new CapabilityRegistry(CapabilityCatalog.loadEmbedded(version), Clock.systemUTC()),
                environment,
                QuotaCatalog.loadEmbedded(version).allocate(
                        InstallationProfile.LITE, AllocationTier.STANDARD, version, Map.of()));
        var controller = new PlatformCapabilityController(service);
        var snapshot = controller.capabilities();
        assertEquals("no-store", snapshot.getHeaders().getCacheControl());
        assertEquals(21, snapshot.getBody().capabilities().size());
        var local = controller.capability("iam.local-auth");
        assertTrue(local.getBody().available());
        assertEquals("LITE", local.getBody().profile());
        var quotas = controller.quotas();
        assertEquals(119, quotas.getBody().quotas().size());
        assertEquals("STANDARD", quotas.getBody().allocationTier());
    }
}
