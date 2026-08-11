package io.infranexum.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.infranexum.core.capabilities.ActivationState;
import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.DependencyStatus;
import io.infranexum.core.capabilities.DeploymentRole;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.capabilities.InstallationTopology;
import io.infranexum.core.capabilities.TechnicalTrait;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlatformCapabilityPropertiesTest {
    @Test
    void convertsValidatedConfigurationToDomainEnvironment() {
        var properties = new PlatformCapabilityProperties(
                InstallationProfile.PRO,
                AllocationTier.ADVANCED,
                InstallationTopology.SPLIT_WEB,
                Set.of(DeploymentRole.SERVER, DeploymentRole.WEB),
                Set.of(TechnicalTrait.EXTERNAL_DATABASE),
                Set.of("iam.local-auth", "iam.ldap", "deployment.split_web"),
                Set.of("iam.ldap", "deployment.split_web"),
                Map.of("iam.ldap", DependencyStatus.OPERATIONAL),
                ActivationState.ACTIVE,
                " 2.0.0-draft.20 ",
                3,
                Map.of("iam.users.max", 1_000L));
        var environment = properties.toEnvironment();
        assertEquals(InstallationProfile.PRO, environment.profile());
        assertEquals("2.0.0-draft.20", environment.catalogVersion());
        assertEquals(3, environment.profileVersion());
        assertEquals(1_000L, properties.quotaOverrides().get("iam.users.max"));
    }

    @Test
    void rejectsIncompleteOrNegativeConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new PlatformCapabilityProperties(
                InstallationProfile.LITE, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                Set.of(), Set.of(), Set.of("iam.local-auth"), Set.of(), Map.of(), ActivationState.NOT_REQUIRED,
                "v1", 1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new PlatformCapabilityProperties(
                InstallationProfile.LITE, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER), Set.of(), Set.of(), Set.of(), Map.of(), ActivationState.NOT_REQUIRED,
                "v1", 1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new PlatformCapabilityProperties(
                InstallationProfile.LITE, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER), null, Set.of("iam.local-auth"), null, null,
                ActivationState.NOT_REQUIRED, "v1", 1, Map.of("iam.users.max", -1L)));
    }
}
