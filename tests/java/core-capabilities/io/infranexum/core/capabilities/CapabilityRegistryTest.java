package io.infranexum.core.capabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CapabilityRegistryTest {
    private static final String VERSION = "2.0.0-draft.21";
    private final CapabilityCatalog catalog = CapabilityCatalog.loadEmbedded(VERSION);
    private final CapabilityRegistry registry = new CapabilityRegistry(
            catalog, Clock.fixed(Instant.parse("2026-08-03T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void allocationTierDoesNotChangeFunctionalSurfaceHash() {
        CapabilityCode ldap = new CapabilityCode("iam.ldap");
        CapabilityDecision standard = registry.evaluate(ldap, pro(AllocationTier.STANDARD, ActivationState.ACTIVE,
                Set.of(ldap), Map.of(ldap, DependencyStatus.OPERATIONAL)));
        CapabilityDecision advanced = registry.evaluate(ldap, pro(AllocationTier.ADVANCED, ActivationState.ACTIVE,
                Set.of(ldap), Map.of(ldap, DependencyStatus.OPERATIONAL)));
        assertTrue(standard.available());
        assertEquals(standard.capabilityHash(), advanced.capabilityHash());
    }

    @Test
    void decisionsExplainEveryRefusalLayer() {
        CapabilityCode ldap = new CapabilityCode("iam.ldap");
        CapabilityEnvironment lite = new CapabilityEnvironment(
                InstallationProfile.LITE, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER, DeploymentRole.WEB), Set.of(), Set.of(ldap), Map.of(), Set.of(),
                ActivationState.NOT_REQUIRED, VERSION, 1);
        assertEquals(CapabilityReasonCode.PROFILE_CAPABILITY_NOT_INSTALLED,
                registry.evaluate(ldap, lite).reasonCode());

        CapabilityEnvironment missing = pro(AllocationTier.STANDARD, ActivationState.ACTIVE, Set.of(ldap), Map.of());
        assertEquals(CapabilityReasonCode.PROFILE_CAPABILITY_NOT_INSTALLED,
                registry.evaluate(new CapabilityCode("iam.saml"), missing).reasonCode());

        CapabilityEnvironment dependencyDown = pro(AllocationTier.STANDARD, ActivationState.ACTIVE, Set.of(ldap),
                Map.of(ldap, DependencyStatus.UNAVAILABLE));
        assertEquals(CapabilityReasonCode.DEPENDENCY_UNAVAILABLE,
                registry.evaluate(ldap, dependencyDown).reasonCode());

        CapabilityEnvironment locked = pro(AllocationTier.STANDARD, ActivationState.LOCKED, Set.of(ldap),
                Map.of(ldap, DependencyStatus.OPERATIONAL));
        assertEquals(CapabilityReasonCode.ACTIVATION_REQUIRED, registry.evaluate(ldap, locked).reasonCode());

        CapabilityEnvironment notEntitled = pro(AllocationTier.STANDARD, ActivationState.ACTIVE, Set.of(),
                Map.of(ldap, DependencyStatus.OPERATIONAL));
        assertEquals(CapabilityReasonCode.ENTITLEMENT_NOT_GRANTED,
                registry.evaluate(ldap, notEntitled).reasonCode());

        CapabilityDecision unknown = registry.evaluate(new CapabilityCode("future.module"), notEntitled);
        assertEquals(CapabilityReasonCode.CAPABILITY_UNKNOWN, unknown.reasonCode());
        assertFalse(unknown.available());
    }

    @Test
    void roleTopologyAndTraitRequirementsAreEnforced() {
        CapabilityCode split = new CapabilityCode("deployment.split_web");
        CapabilityEnvironment missingWeb = new CapabilityEnvironment(
                InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.SPLIT_WEB,
                Set.of(DeploymentRole.SERVER), Set.of(), Set.of(split), Map.of(), Set.of(split),
                ActivationState.ACTIVE, VERSION, 1);
        assertEquals(CapabilityReasonCode.ROLE_NOT_DEPLOYED, registry.evaluate(split, missingWeb).reasonCode());

        CapabilityCode oracle = new CapabilityCode("database.oracle");
        CapabilityEnvironment missingTrait = new CapabilityEnvironment(
                InstallationProfile.ENTERPRISE, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER, DeploymentRole.WEB), Set.of(), Set.of(oracle), Map.of(), Set.of(oracle),
                ActivationState.ACTIVE, VERSION, 1);
        assertEquals(CapabilityReasonCode.TRAIT_REQUIRED, registry.evaluate(oracle, missingTrait).reasonCode());

        CapabilityCode regional = new CapabilityCode("deployment.regional");
        CapabilityEnvironment wrongTopology = new CapabilityEnvironment(
                InstallationProfile.ENTERPRISE, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER, DeploymentRole.WEB), Set.of(), Set.of(regional), Map.of(), Set.of(regional),
                ActivationState.ACTIVE, VERSION, 1);
        assertEquals(CapabilityReasonCode.TOPOLOGY_UNSUPPORTED,
                registry.evaluate(regional, wrongTopology).reasonCode());
    }

    @Test
    void snapshotIsSortedStableAndGuarded() {
        CapabilitySnapshot first = registry.evaluate(pro(AllocationTier.STANDARD, ActivationState.ACTIVE,
                Set.of(new CapabilityCode("iam.ldap")), Map.of()));
        CapabilitySnapshot second = registry.evaluate(pro(AllocationTier.STANDARD, ActivationState.ACTIVE,
                Set.of(new CapabilityCode("iam.ldap")), Map.of()));
        assertEquals(catalog.codes().size(), first.decisions().size());
        assertEquals(32, catalog.codes().size());
        assertEquals(first.capabilityHash(), second.capabilityHash());
        assertEquals(first.require(new CapabilityCode("iam.ldap")), second.require(new CapabilityCode("iam.ldap")));
        assertThrows(IllegalArgumentException.class, () -> first.require(new CapabilityCode("unknown.value")));

        CapabilityDecision denied = first.require(new CapabilityCode("iam.saml"));
        CapabilityUnavailableException failure = assertThrows(
                CapabilityUnavailableException.class, () -> CapabilityGuard.requireAvailable(denied));
        assertEquals(denied, failure.decision());
        CapabilityGuard.requireAvailable(first.require(new CapabilityCode("iam.ldap")));
    }

    @Test
    void catalogueVersionAndEnvironmentInvariantsFailClosed() {
        CapabilityEnvironment environment = pro(AllocationTier.STANDARD, ActivationState.ACTIVE, Set.of(), Map.of());
        CapabilityEnvironment wrongVersion = new CapabilityEnvironment(
                environment.profile(), environment.allocationTier(), environment.topology(), environment.roles(),
                environment.traits(), environment.installedCapabilities(), environment.dependencyStatus(),
                environment.entitledCapabilities(), environment.activationState(), "wrong", 1);
        assertThrows(IllegalArgumentException.class, () -> registry.evaluate(wrongVersion));
        assertThrows(IllegalArgumentException.class,
                () -> registry.evaluate(new CapabilityCode("iam.ldap"), wrongVersion));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityEnvironment(
                InstallationProfile.PRO, AllocationTier.ULTIMATE, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER), Set.of(), Set.of(), Map.of(), Set.of(), ActivationState.ACTIVE,
                VERSION, 1));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityEnvironment(
                InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.REGIONAL,
                Set.of(DeploymentRole.SERVER), Set.of(), Set.of(), Map.of(), Set.of(), ActivationState.ACTIVE,
                VERSION, 1));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityEnvironment(
                InstallationProfile.PRO, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER, DeploymentRole.AGENT), Set.of(), Set.of(), Map.of(), Set.of(),
                ActivationState.ACTIVE, VERSION, 1));
    }

    private static CapabilityEnvironment pro(
            AllocationTier tier,
            ActivationState activation,
            Set<CapabilityCode> entitled,
            Map<CapabilityCode, DependencyStatus> dependencies) {
        Set<CapabilityCode> installed = Set.of(
                new CapabilityCode("iam.local-auth"),
                new CapabilityCode("database.postgresql"),
                new CapabilityCode("discovery.agentless"),
                new CapabilityCode("deployment.split_web"),
                new CapabilityCode("iam.ldap"));
        return new CapabilityEnvironment(
                InstallationProfile.PRO, tier, InstallationTopology.SPLIT_WEB,
                Set.of(DeploymentRole.SERVER, DeploymentRole.WEB), Set.of(TechnicalTrait.EXTERNAL_DATABASE),
                installed, dependencies, entitled, activation, VERSION, 1);
    }
}
