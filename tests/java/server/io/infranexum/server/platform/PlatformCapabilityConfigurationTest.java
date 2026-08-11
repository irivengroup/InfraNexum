package io.infranexum.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.infranexum.core.capabilities.ActivationState;
import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.DeploymentRole;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.capabilities.InstallationTopology;
import io.infranexum.server.platform.entitlements.ActivationRuntimeProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlatformCapabilityConfigurationTest {
    @Test
    void buildsServiceFromValidatedProperties() {
        var properties = new PlatformCapabilityProperties(
                InstallationProfile.LITE, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER, DeploymentRole.WEB), Set.of(),
                Set.of("iam.local-auth", "database.postgresql", "discovery.agentless"),
                Set.of(), Map.of(), ActivationState.NOT_REQUIRED, "2.0.0-draft.20", 1, Map.of());
        var configuration = new PlatformCapabilityConfiguration();
        var capabilityCatalog = configuration.capabilityCatalog(properties);
        var quotaCatalog = configuration.quotaCatalog(properties);
        var entitlements = new ActivationRuntimeProperties(
                false, "customer-1", null, Path.of("integrity.key"), Path.of("proofs"),
                4096, Duration.ofMinutes(5));
        var service = configuration.platformCapabilityService(
                properties, capabilityCatalog, quotaCatalog, entitlements);
        assertEquals(119, service.quotaPlan().limits().size());
        assertEquals(21, service.snapshot().decisions().size());
        var snapshot = CapabilitySnapshotResponse.from(service.snapshot());
        assertEquals(21, snapshot.capabilities().size());
        assertEquals(service.snapshot().capabilityHash(), snapshot.capabilityHash());
        var quotaPlan = QuotaPlanResponse.from(service.quotaPlan());
        assertEquals(119, quotaPlan.quotas().size());
        assertEquals("LITE", quotaPlan.profile());
    }
}
