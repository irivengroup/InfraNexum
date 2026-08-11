package io.infranexum.core.capabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Covers constructor boundaries and a protected Lite capability through a custom catalogue. */
class CapabilityRegistryCoverageTest {
    @Test
    void constructorAndEvaluateNullBoundariesFailClosed() {
        CapabilityCatalog catalog = CapabilityCatalog.loadEmbedded("2.0.0-draft.20");
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        assertThrows(NullPointerException.class, () -> new CapabilityRegistry(null, clock));
        assertThrows(NullPointerException.class, () -> new CapabilityRegistry(catalog, null));
        CapabilityRegistry registry = new CapabilityRegistry(catalog, clock);
        assertThrows(NullPointerException.class, () -> registry.evaluate((CapabilityEnvironment) null));
        CapabilityEnvironment environment = new CapabilityEnvironment(
                InstallationProfile.LITE, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER), Set.of(), Set.of(new CapabilityCode("iam.local-auth")), Map.of(),
                Set.of(), ActivationState.NOT_REQUIRED, "2.0.0-draft.20", 1);
        assertThrows(NullPointerException.class, () -> registry.evaluate((CapabilityCode) null, environment));
        assertThrows(NullPointerException.class, () -> registry.evaluate(new CapabilityCode("iam.local-auth"), null));
    }

    @Test
    void protectedLiteCapabilityDoesNotRequireCommercialEntitlement() throws IOException {
        String csv = "capability_code,allowed_profiles,required_roles,allowed_topologies,required_traits,activation_protected\n"
                + "test.protected,lite,server,single-node,,true\n";
        Path path = Files.createTempFile("infranexum-protected-lite-", ".csv");
        try {
            Files.writeString(path, csv, StandardCharsets.UTF_8);
            CapabilityCatalog catalog = CapabilityCatalog.load("v1", path);
            CapabilityRegistry registry = new CapabilityRegistry(catalog, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            CapabilityCode code = new CapabilityCode("test.protected");
            CapabilityEnvironment environment = new CapabilityEnvironment(
                    InstallationProfile.LITE, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                    Set.of(DeploymentRole.SERVER), Set.of(), Set.of(code), Map.of(), Set.of(),
                    ActivationState.NOT_REQUIRED, "v1", 1);
            CapabilityDecision decision = registry.evaluate(code, environment);
            assertTrue(decision.available());
            assertEquals(CapabilityReasonCode.AVAILABLE, decision.reasonCode());
        } finally {
            Files.deleteIfExists(path);
        }
    }
}
