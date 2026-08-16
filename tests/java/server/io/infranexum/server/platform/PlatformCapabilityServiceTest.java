package io.infranexum.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.capabilities.ActivationState;
import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.CapabilityCatalog;
import io.infranexum.core.capabilities.CapabilityCode;
import io.infranexum.core.capabilities.CapabilityEnvironment;
import io.infranexum.core.capabilities.CapabilityRegistry;
import io.infranexum.core.capabilities.DependencyStatus;
import io.infranexum.core.capabilities.DeploymentRole;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.capabilities.InstallationTopology;
import io.infranexum.core.capabilities.QuotaCatalog;
import io.infranexum.core.entitlements.EntitlementRuntimePhase;
import io.infranexum.core.entitlements.EntitlementRuntimeStatus;
import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlatformCapabilityServiceTest {
    @Test
    void publishesOneImmutableStartupSnapshotAndQuotaPlan() {
        String version = "2.0.0-draft.21";
        var environment = new CapabilityEnvironment(
                InstallationProfile.LITE, AllocationTier.STANDARD, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER, DeploymentRole.WEB), Set.of(),
                Set.of(new CapabilityCode("iam.local-auth"), new CapabilityCode("database.postgresql"),
                        new CapabilityCode("discovery.agentless")),
                Map.of(), Set.of(), ActivationState.NOT_REQUIRED, version, 1);
        var registry = new CapabilityRegistry(
                CapabilityCatalog.loadEmbedded(version),
                Clock.fixed(Instant.parse("2026-08-03T12:00:00Z"), ZoneOffset.UTC));
        var plan = QuotaCatalog.loadEmbedded(version).allocate(
                InstallationProfile.LITE, AllocationTier.STANDARD, version, Map.of());
        var service = new PlatformCapabilityService(registry, environment, plan);
        assertEquals(21, service.snapshot().decisions().size());
        assertTrue(service.explain("iam.local-auth").available());
        assertFalse(service.explain("iam.ldap").available());
        assertEquals(5, service.quotaPlan().limit("iam.users.max"));
    }
    @Test
    void remainsUnavailableUntilTheAuthoritativeEntitlementDecisionAndThenRefreshesAtomically() {
        String version = "2.0.0-draft.21";
        var properties = new PlatformCapabilityProperties(
                InstallationProfile.PRO,
                AllocationTier.STANDARD,
                InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER, DeploymentRole.WEB),
                Set.of(),
                Set.of("iam.local-auth", "iam.ldap", "database.postgresql", "discovery.agentless"),
                Set.of(),
                Map.<String, DependencyStatus>of(),
                ActivationState.INVALID,
                version,
                1,
                Map.of());
        var registry = new CapabilityRegistry(
                CapabilityCatalog.loadEmbedded(version),
                Clock.fixed(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC));
        var service = new PlatformCapabilityService(
                registry, properties, QuotaCatalog.loadEmbedded(version), true);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, service::snapshot);
        var status = new EntitlementRuntimeStatus(
                DomainIdentifier.parse("01989c82-7000-7abc-8def-0123456789ab"),
                InstallationProfile.PRO,
                AllocationTier.ADVANCED,
                EntitlementRuntimePhase.ACTIVE,
                Instant.parse("2026-08-04T12:00:00Z"),
                null, null, null,
                Instant.parse("2027-08-04T12:00:00Z"),
                Instant.parse("2027-09-03T12:00:00Z"),
                7,
                DomainIdentifier.parse("01989c82-7001-7abc-8def-0123456789ab"),
                Set.of("iam.local-auth", "iam.ldap"),
                Map.of("iam.users.max", 500L),
                true,
                true);

        service.applyEntitlementStatus(status);

        assertTrue(service.explain("iam.local-auth").available());
        assertTrue(service.explain("iam.ldap").available());
        assertEquals(500, service.quotaPlan().limit("iam.users.max"));
        assertEquals(AllocationTier.ADVANCED, service.quotaPlan().tier());
    }

}
