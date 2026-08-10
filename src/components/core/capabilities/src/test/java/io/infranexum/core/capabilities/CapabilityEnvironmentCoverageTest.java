package io.infranexum.core.capabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Exercises the complete profile/tier/topology/role/activation matrix. */
class CapabilityEnvironmentCoverageTest {
    private static final String VERSION = "v1";

    @Test
    void validProfilesCoverEveryTierAndTopologyBranch() {
        CapabilityCode code = new CapabilityCode("iam.local-auth");
        CapabilityEnvironment lite = environment(
                InstallationProfile.LITE, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER), Set.of(), ActivationState.NOT_REQUIRED,
                Map.of(code, DependencyStatus.OPERATIONAL));
        assertEquals(DependencyStatus.OPERATIONAL, lite.dependencyFor(code));
        assertEquals(DependencyStatus.NOT_APPLICABLE, lite.dependencyFor(new CapabilityCode("iam.ldap")));

        environment(InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER), Set.of(), ActivationState.ACTIVE, Map.of());
        environment(InstallationProfile.PRO, AllocationTier.ADVANCED, InstallationTopology.SPLIT_WEB,
                Set.of(DeploymentRole.SERVER, DeploymentRole.WEB), Set.of(TechnicalTrait.EXTERNAL_DATABASE),
                ActivationState.GRACE, Map.of());
        environment(InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.HIGH_AVAILABILITY,
                Set.of(DeploymentRole.SERVER), Set.of(), ActivationState.LOCKED, Map.of());

        environment(InstallationProfile.ENTERPRISE, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER, DeploymentRole.AGENT), Set.of(TechnicalTrait.ORACLE_BACKEND),
                ActivationState.ACTIVE, Map.of());
        environment(InstallationProfile.ENTERPRISE, AllocationTier.ULTIMATE, InstallationTopology.MULTI_REGION,
                Set.of(DeploymentRole.SERVER, DeploymentRole.WEB, DeploymentRole.AGENT),
                Set.of(TechnicalTrait.ORACLE_BACKEND, TechnicalTrait.HARDENED), ActivationState.INVALID, Map.of());
    }

    @Test
    void nullAndTextMetadataAreRejectedIndividually() {
        Set<DeploymentRole> roles = Set.of(DeploymentRole.SERVER);
        assertThrows(NullPointerException.class, () -> new CapabilityEnvironment(
                null, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE, roles, Set.of(), Set.of(), Map.of(),
                Set.of(), ActivationState.ACTIVE, VERSION, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityEnvironment(
                InstallationProfile.PRO, null, InstallationTopology.SINGLE_NODE, roles, Set.of(), Set.of(), Map.of(),
                Set.of(), ActivationState.ACTIVE, VERSION, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityEnvironment(
                InstallationProfile.PRO, AllocationTier.STANDARD, null, roles, Set.of(), Set.of(), Map.of(),
                Set.of(), ActivationState.ACTIVE, VERSION, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityEnvironment(
                InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE, null, Set.of(),
                Set.of(), Map.of(), Set.of(), ActivationState.ACTIVE, VERSION, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityEnvironment(
                InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE, roles, null,
                Set.of(), Map.of(), Set.of(), ActivationState.ACTIVE, VERSION, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityEnvironment(
                InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE, roles, Set.of(),
                null, Map.of(), Set.of(), ActivationState.ACTIVE, VERSION, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityEnvironment(
                InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE, roles, Set.of(),
                Set.of(), null, Set.of(), ActivationState.ACTIVE, VERSION, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityEnvironment(
                InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE, roles, Set.of(),
                Set.of(), Map.of(), null, ActivationState.ACTIVE, VERSION, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityEnvironment(
                InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE, roles, Set.of(),
                Set.of(), Map.of(), Set.of(), null, VERSION, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityEnvironment(
                InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE, roles, Set.of(),
                Set.of(), Map.of(), Set.of(), ActivationState.ACTIVE, null, 1));
        assertThrows(IllegalArgumentException.class, () -> environment(
                InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                roles, Set.of(), ActivationState.ACTIVE, Map.of(), " ", 1));
        assertThrows(IllegalArgumentException.class, () -> environment(
                InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                roles, Set.of(), ActivationState.ACTIVE, Map.of(), VERSION, 0));
    }

    @Test
    void invalidProfileTierTopologyRoleTraitAndActivationCombinationsFailClosed() {
        Set<DeploymentRole> server = Set.of(DeploymentRole.SERVER);
        assertThrows(IllegalArgumentException.class, () -> environment(
                InstallationProfile.LITE, AllocationTier.ADVANCED, InstallationTopology.SINGLE_NODE,
                server, Set.of(), ActivationState.NOT_REQUIRED, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> environment(
                InstallationProfile.PRO, AllocationTier.ULTIMATE, InstallationTopology.SINGLE_NODE,
                server, Set.of(), ActivationState.ACTIVE, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> environment(
                InstallationProfile.ENTERPRISE, AllocationTier.ADVANCED, InstallationTopology.SINGLE_NODE,
                server, Set.of(), ActivationState.ACTIVE, Map.of()));

        assertThrows(IllegalArgumentException.class, () -> environment(
                InstallationProfile.LITE, AllocationTier.STANDARD, InstallationTopology.SPLIT_WEB,
                server, Set.of(), ActivationState.NOT_REQUIRED, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> environment(
                InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.REGIONAL,
                server, Set.of(), ActivationState.ACTIVE, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> environment(
                InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.WEB), Set.of(), ActivationState.ACTIVE, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> environment(
                InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER, DeploymentRole.AGENT), Set.of(), ActivationState.ACTIVE, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> environment(
                InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                server, Set.of(TechnicalTrait.ORACLE_BACKEND), ActivationState.ACTIVE, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> environment(
                InstallationProfile.LITE, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                server, Set.of(), ActivationState.ACTIVE, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> environment(
                InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                server, Set.of(), ActivationState.NOT_REQUIRED, Map.of()));
    }

    private static CapabilityEnvironment environment(
            InstallationProfile profile,
            AllocationTier tier,
            InstallationTopology topology,
            Set<DeploymentRole> roles,
            Set<TechnicalTrait> traits,
            ActivationState activation,
            Map<CapabilityCode, DependencyStatus> dependencies) {
        return environment(profile, tier, topology, roles, traits, activation, dependencies, VERSION, 1);
    }

    private static CapabilityEnvironment environment(
            InstallationProfile profile,
            AllocationTier tier,
            InstallationTopology topology,
            Set<DeploymentRole> roles,
            Set<TechnicalTrait> traits,
            ActivationState activation,
            Map<CapabilityCode, DependencyStatus> dependencies,
            String version,
            long profileVersion) {
        return new CapabilityEnvironment(
                profile, tier, topology, roles, traits, Set.of(new CapabilityCode("iam.local-auth")), dependencies,
                Set.of(new CapabilityCode("iam.local-auth")), activation, version, profileVersion);
    }
}
