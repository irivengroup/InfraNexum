package io.infranexum.core.capabilities;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

/** Dependency-free behavioral smoke for the capability and quota kernel. */
public final class CapabilitiesSmoke {
    private CapabilitiesSmoke() {}

    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("expected capability and quota catalogue paths");
        }
        String catalogVersion = "2.0.0-draft.20";
        CapabilityCatalog capabilities = CapabilityCatalog.load(catalogVersion, Path.of(args[0]));
        QuotaCatalog quotas = QuotaCatalog.load(catalogVersion, Path.of(args[1]));
        assert capabilities.codes().size() == 23;
        assert quotas.size() == 119;

        CapabilityCode ldap = new CapabilityCode("iam.ldap");
        Set<CapabilityCode> proInstalled = Set.of(
                new CapabilityCode("iam.local-auth"),
                new CapabilityCode("database.postgresql"),
                new CapabilityCode("discovery.agentless"),
                new CapabilityCode("deployment.split_web"),
                ldap);
        CapabilityEnvironment proStandard = environment(
                AllocationTier.STANDARD, proInstalled, Set.of(ldap, new CapabilityCode("deployment.split_web")));
        CapabilityEnvironment proAdvanced = environment(
                AllocationTier.ADVANCED, proInstalled, Set.of(ldap, new CapabilityCode("deployment.split_web")));
        Clock clock = Clock.fixed(Instant.parse("2026-08-03T12:00:00Z"), ZoneOffset.UTC);
        CapabilityRegistry registry = new CapabilityRegistry(capabilities, clock);
        CapabilityDecision standardDecision = registry.evaluate(ldap, proStandard);
        CapabilityDecision advancedDecision = registry.evaluate(ldap, proAdvanced);
        assert standardDecision.available();
        assert standardDecision.capabilityHash().equals(advancedDecision.capabilityHash())
                : "allocation tiers must not change the functional surface";

        CapabilityEnvironment locked = new CapabilityEnvironment(
                InstallationProfile.PRO,
                AllocationTier.STANDARD,
                InstallationTopology.SPLIT_WEB,
                Set.of(DeploymentRole.SERVER, DeploymentRole.WEB),
                Set.of(TechnicalTrait.EXTERNAL_DATABASE),
                proInstalled,
                Map.of(ldap, DependencyStatus.OPERATIONAL),
                Set.of(ldap),
                ActivationState.LOCKED,
                catalogVersion,
                2);
        assert registry.evaluate(ldap, locked).reasonCode() == CapabilityReasonCode.ACTIVATION_REQUIRED;

        CapabilityEnvironment lite = new CapabilityEnvironment(
                InstallationProfile.LITE,
                AllocationTier.STANDARD,
                InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER, DeploymentRole.WEB),
                Set.of(),
                Set.of(new CapabilityCode("iam.local-auth"), ldap),
                Map.of(),
                Set.of(),
                ActivationState.NOT_REQUIRED,
                catalogVersion,
                1);
        assert registry.evaluate(ldap, lite).reasonCode()
                == CapabilityReasonCode.PROFILE_CAPABILITY_NOT_INSTALLED;

        QuotaAllocationPlan litePlan = quotas.allocate(
                InstallationProfile.LITE, AllocationTier.STANDARD, catalogVersion, Map.of());
        assert litePlan.limit("rsot.managed_hosts.max") == 200;
        QuotaAllocationPlan proPlan = quotas.allocate(
                InstallationProfile.PRO,
                AllocationTier.ADVANCED,
                catalogVersion,
                Map.of("rsot.managed_hosts.max", 100_000L));
        assert proPlan.limit("rsot.managed_hosts.max") == 100_000;

        QuotaPolicy policy = new QuotaPolicy();
        QuotaDecision information = policy.evaluate(proPlan, "rsot.managed_hosts.max", 79_999, 1);
        assert information.allowed() && information.usageLevel() == QuotaUsageLevel.INFORMATION;
        QuotaDecision exhausted = policy.evaluate(proPlan, "rsot.managed_hosts.max", 99_999, 1);
        assert exhausted.allowed() && exhausted.usageLevel() == QuotaUsageLevel.EXHAUSTED;
        QuotaDecision blocked = policy.evaluate(proPlan, "rsot.managed_hosts.max", 100_000, 1);
        assert !blocked.allowed() && blocked.reasonCode().equals("QUOTA_LIMIT_EXCEEDED");
        try {
            QuotaGuard.requireAllowed(blocked);
            throw new AssertionError("blocked allocation was accepted");
        } catch (QuotaExceededException expected) {
            assert expected.decision() == blocked;
        }

        System.out.println("capabilities-smoke: OK; capabilities=" + capabilities.codes().size()
                + "; quotas=" + quotas.size());
    }

    private static CapabilityEnvironment environment(
            AllocationTier tier, Set<CapabilityCode> installed, Set<CapabilityCode> entitled) {
        return new CapabilityEnvironment(
                InstallationProfile.PRO,
                tier,
                InstallationTopology.SPLIT_WEB,
                Set.of(DeploymentRole.SERVER, DeploymentRole.WEB),
                Set.of(TechnicalTrait.EXTERNAL_DATABASE),
                installed,
                Map.of(new CapabilityCode("iam.ldap"), DependencyStatus.OPERATIONAL),
                entitled,
                ActivationState.ACTIVE,
                "2.0.0-draft.20",
                2);
    }
}
