package io.infranexum.core.capabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Branch-complete contract tests for capability value objects and defensive invariants. */
class CapabilityValueObjectsCoverageTest {
    @Test
    void activationAndDependencyStatesExposeEveryUsabilityBranch() {
        assertTrue(ActivationState.NOT_REQUIRED.permitsProtectedCapabilities());
        assertTrue(ActivationState.ACTIVE.permitsProtectedCapabilities());
        assertTrue(ActivationState.GRACE.permitsProtectedCapabilities());
        assertFalse(ActivationState.LOCKED.permitsProtectedCapabilities());
        assertFalse(ActivationState.INVALID.permitsProtectedCapabilities());

        assertTrue(DependencyStatus.NOT_APPLICABLE.isUsable());
        assertTrue(DependencyStatus.OPERATIONAL.isUsable());
        assertFalse(DependencyStatus.DEGRADED.isUsable());
        assertFalse(DependencyStatus.UNAVAILABLE.isUsable());
    }

    @Test
    void enumParsersAcceptCanonicalValuesAndRejectNullBlankAndUnknownValues() {
        assertEquals(InstallationProfile.PRO, InstallationProfile.parse(" pro "));
        assertEquals(AllocationTier.ULTIMATE, AllocationTier.parse(" ultimate "));
        assertEquals(DeploymentRole.WEB, DeploymentRole.parse(" web "));
        assertEquals(InstallationTopology.HIGH_AVAILABILITY, InstallationTopology.parse(" high-availability "));
        assertEquals(TechnicalTrait.AIR_GAPPED, TechnicalTrait.parse(" AIR-GAPPED "));
        assertEquals("regional", InstallationTopology.REGIONAL.code());
        assertEquals("hardened", TechnicalTrait.HARDENED.code());

        assertThrows(IllegalArgumentException.class, () -> InstallationProfile.parse(null));
        assertThrows(IllegalArgumentException.class, () -> InstallationProfile.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> InstallationProfile.parse("unknown"));
        assertThrows(IllegalArgumentException.class, () -> AllocationTier.parse(null));
        assertThrows(IllegalArgumentException.class, () -> AllocationTier.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> AllocationTier.parse("unknown"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentRole.parse(null));
        assertThrows(IllegalArgumentException.class, () -> DeploymentRole.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> DeploymentRole.parse("unknown"));
        assertThrows(IllegalArgumentException.class, () -> InstallationTopology.parse(null));
        assertThrows(IllegalArgumentException.class, () -> InstallationTopology.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> InstallationTopology.parse("unknown"));
        assertThrows(IllegalArgumentException.class, () -> TechnicalTrait.parse(null));
        assertThrows(IllegalArgumentException.class, () -> TechnicalTrait.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> TechnicalTrait.parse("unknown"));
    }

    @Test
    void capabilityCodeIsCanonicalComparableAndNullSafe() {
        CapabilityCode first = new CapabilityCode(" iam.ldap ");
        CapabilityCode second = new CapabilityCode("iam.saml");
        assertEquals("iam.ldap", first.value());
        assertEquals("iam.ldap", first.toString());
        assertTrue(first.compareTo(second) < 0);
        assertThrows(NullPointerException.class, () -> new CapabilityCode(null));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityCode("a"));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityCode("IAM.LDAP"));
    }

    @Test
    void definitionsRejectIncompleteRequirements() {
        CapabilityCode code = new CapabilityCode("iam.ldap");
        CapabilityDefinition definition = new CapabilityDefinition(
                code,
                Set.of(InstallationProfile.PRO),
                Set.of(DeploymentRole.SERVER),
                Set.of(InstallationTopology.SINGLE_NODE),
                Set.of(),
                true);
        assertEquals(code, definition.code());
        assertThrows(NullPointerException.class, () -> new CapabilityDefinition(
                null, Set.of(InstallationProfile.PRO), Set.of(), Set.of(InstallationTopology.SINGLE_NODE), Set.of(), true));
        assertThrows(NullPointerException.class, () -> new CapabilityDefinition(
                code, null, Set.of(), Set.of(InstallationTopology.SINGLE_NODE), Set.of(), true));
        assertThrows(NullPointerException.class, () -> new CapabilityDefinition(
                code, Set.of(InstallationProfile.PRO), null, Set.of(InstallationTopology.SINGLE_NODE), Set.of(), true));
        assertThrows(NullPointerException.class, () -> new CapabilityDefinition(
                code, Set.of(InstallationProfile.PRO), Set.of(), null, Set.of(), true));
        assertThrows(NullPointerException.class, () -> new CapabilityDefinition(
                code, Set.of(InstallationProfile.PRO), Set.of(), Set.of(InstallationTopology.SINGLE_NODE), null, true));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityDefinition(
                code, Set.of(), Set.of(), Set.of(InstallationTopology.SINGLE_NODE), Set.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityDefinition(
                code, Set.of(InstallationProfile.PRO), Set.of(), Set.of(), Set.of(), true));
    }

    @Test
    void decisionAndSnapshotMetadataFailClosedForEveryInvalidDimension() {
        CapabilityCode code = new CapabilityCode("iam.ldap");
        CapabilityDecision valid = decision(code, true, CapabilityReasonCode.AVAILABLE);
        assertEquals(code, valid.capabilityCode());

        assertThrows(NullPointerException.class, () -> new CapabilityDecision(
                null, true, CapabilityReasonCode.AVAILABLE, InstallationProfile.PRO, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER), Set.of(), DependencyStatus.OPERATIONAL, ActivationState.ACTIVE,
                "v1", "0".repeat(64), Instant.EPOCH, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityDecision(
                code, true, null, InstallationProfile.PRO, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER), Set.of(), DependencyStatus.OPERATIONAL, ActivationState.ACTIVE,
                "v1", "0".repeat(64), Instant.EPOCH, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityDecision(
                code, true, CapabilityReasonCode.AVAILABLE, null, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER), Set.of(), DependencyStatus.OPERATIONAL, ActivationState.ACTIVE,
                "v1", "0".repeat(64), Instant.EPOCH, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityDecision(
                code, true, CapabilityReasonCode.AVAILABLE, InstallationProfile.PRO, null,
                Set.of(DeploymentRole.SERVER), Set.of(), DependencyStatus.OPERATIONAL, ActivationState.ACTIVE,
                "v1", "0".repeat(64), Instant.EPOCH, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityDecision(
                code, true, CapabilityReasonCode.AVAILABLE, InstallationProfile.PRO, InstallationTopology.SINGLE_NODE,
                null, Set.of(), DependencyStatus.OPERATIONAL, ActivationState.ACTIVE,
                "v1", "0".repeat(64), Instant.EPOCH, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityDecision(
                code, true, CapabilityReasonCode.AVAILABLE, InstallationProfile.PRO, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER), null, DependencyStatus.OPERATIONAL, ActivationState.ACTIVE,
                "v1", "0".repeat(64), Instant.EPOCH, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityDecision(
                code, true, CapabilityReasonCode.AVAILABLE, InstallationProfile.PRO, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER), Set.of(), null, ActivationState.ACTIVE,
                "v1", "0".repeat(64), Instant.EPOCH, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityDecision(
                code, true, CapabilityReasonCode.AVAILABLE, InstallationProfile.PRO, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER), Set.of(), DependencyStatus.OPERATIONAL, null,
                "v1", "0".repeat(64), Instant.EPOCH, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityDecision(
                code, true, CapabilityReasonCode.AVAILABLE, InstallationProfile.PRO, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER), Set.of(), DependencyStatus.OPERATIONAL, ActivationState.ACTIVE,
                null, "0".repeat(64), Instant.EPOCH, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityDecision(
                code, true, CapabilityReasonCode.AVAILABLE, InstallationProfile.PRO, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER), Set.of(), DependencyStatus.OPERATIONAL, ActivationState.ACTIVE,
                "v1", null, Instant.EPOCH, 1));
        assertThrows(NullPointerException.class, () -> new CapabilityDecision(
                code, true, CapabilityReasonCode.AVAILABLE, InstallationProfile.PRO, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER), Set.of(), DependencyStatus.OPERATIONAL, ActivationState.ACTIVE,
                "v1", "0".repeat(64), null, 1));
        assertThrows(IllegalArgumentException.class, () -> decisionWithMetadata(code, " ", "0".repeat(64), 1));
        assertThrows(IllegalArgumentException.class, () -> decisionWithMetadata(code, "v1", "bad", 1));
        assertThrows(IllegalArgumentException.class, () -> decisionWithMetadata(code, "v1", "0".repeat(64), 0));
        assertThrows(IllegalArgumentException.class, () -> decision(code, false, CapabilityReasonCode.AVAILABLE));
        assertThrows(IllegalArgumentException.class, () -> decision(code, true, CapabilityReasonCode.ACTIVATION_REQUIRED));

        Map<CapabilityCode, CapabilityDecision> decisions = Map.of(code, valid);
        CapabilitySnapshot snapshot = new CapabilitySnapshot("v1", 1, "1".repeat(64), Instant.EPOCH, decisions);
        assertEquals(valid, snapshot.require(code));
        assertThrows(NullPointerException.class, () -> new CapabilitySnapshot(null, 1, "1".repeat(64), Instant.EPOCH, decisions));
        assertThrows(NullPointerException.class, () -> new CapabilitySnapshot("v1", 1, null, Instant.EPOCH, decisions));
        assertThrows(NullPointerException.class, () -> new CapabilitySnapshot("v1", 1, "1".repeat(64), null, decisions));
        assertThrows(NullPointerException.class, () -> new CapabilitySnapshot("v1", 1, "1".repeat(64), Instant.EPOCH, null));
        assertThrows(IllegalArgumentException.class, () -> new CapabilitySnapshot(" ", 1, "1".repeat(64), Instant.EPOCH, decisions));
        assertThrows(IllegalArgumentException.class, () -> new CapabilitySnapshot("v1", 0, "1".repeat(64), Instant.EPOCH, decisions));
        assertThrows(IllegalArgumentException.class, () -> new CapabilitySnapshot("v1", 1, "bad", Instant.EPOCH, decisions));
        assertThrows(IllegalArgumentException.class, () -> new CapabilitySnapshot("v1", 1, "1".repeat(64), Instant.EPOCH, Map.of()));
        assertThrows(NullPointerException.class, () -> snapshot.require(null));
        assertThrows(IllegalArgumentException.class, () -> snapshot.require(new CapabilityCode("iam.saml")));
    }

    @Test
    void guardsAndExceptionsPreserveTheAuthoritativeDecision() {
        CapabilityDecision denied = decision(
                new CapabilityCode("iam.ldap"), false, CapabilityReasonCode.ENTITLEMENT_NOT_GRANTED);
        CapabilityUnavailableException failure = assertThrows(
                CapabilityUnavailableException.class, () -> CapabilityGuard.requireAvailable(denied));
        assertEquals(denied, failure.decision());
        assertNotNull(failure.getMessage());
        assertThrows(NullPointerException.class, () -> CapabilityGuard.requireAvailable(null));
        assertThrows(NullPointerException.class, () -> new CapabilityUnavailableException(null));
    }

    private static CapabilityDecision decision(
            CapabilityCode code, boolean available, CapabilityReasonCode reason) {
        return new CapabilityDecision(
                code, available, reason, InstallationProfile.PRO, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER), Set.of(), DependencyStatus.OPERATIONAL, ActivationState.ACTIVE,
                "v1", "0".repeat(64), Instant.EPOCH, 1);
    }

    private static CapabilityDecision decisionWithMetadata(
            CapabilityCode code, String version, String hash, long profileVersion) {
        return new CapabilityDecision(
                code, true, CapabilityReasonCode.AVAILABLE, InstallationProfile.PRO, InstallationTopology.SINGLE_NODE,
                Set.of(DeploymentRole.SERVER), Set.of(), DependencyStatus.OPERATIONAL, ActivationState.ACTIVE,
                version, hash, Instant.EPOCH, profileVersion);
    }
}
