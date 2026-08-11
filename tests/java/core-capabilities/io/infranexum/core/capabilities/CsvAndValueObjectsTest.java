package io.infranexum.core.capabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.StringReader;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CsvAndValueObjectsTest {
    @Test
    void csvParserSupportsQuotesEscapesAndCrLf() {
        var rows = CsvTable.read(new StringReader("a,b\r\n\"x,y\",\"z\"\"q\"\r\n"));
        assertEquals("x,y", rows.getFirst().get("a"));
        assertEquals("z\"q", rows.getFirst().get("b"));
    }

    @Test
    void csvParserRejectsMalformedDocuments() {
        assertThrows(IllegalArgumentException.class, () -> CsvTable.read(new StringReader("")));
        assertThrows(IllegalArgumentException.class, () -> CsvTable.read(new StringReader("a,a\n1,2\n")));
        assertThrows(IllegalArgumentException.class, () -> CsvTable.read(new StringReader("a,b\n1\n")));
        assertThrows(IllegalArgumentException.class, () -> CsvTable.read(new StringReader("a\n\"x")));
    }

    @Test
    void identifiersAndEnumsParseCanonicalValues() {
        assertEquals(InstallationProfile.ENTERPRISE, InstallationProfile.parse("enterprise"));
        assertEquals(AllocationTier.ADVANCED, AllocationTier.parse("advanced"));
        assertEquals(InstallationTopology.MULTI_REGION, InstallationTopology.parse("multi-region"));
        assertEquals(DeploymentRole.AGENT, DeploymentRole.parse("agent"));
        assertEquals(TechnicalTrait.ORACLE_BACKEND, TechnicalTrait.parse("oracle-backend"));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityCode("Invalid"));
        assertThrows(IllegalArgumentException.class, () -> InstallationTopology.parse("unknown"));
        assertThrows(IllegalArgumentException.class, () -> TechnicalTrait.parse("unknown"));
    }

    @Test
    void decisionRecordsRejectInconsistentState() {
        CapabilityCode code = new CapabilityCode("iam.ldap");
        assertThrows(IllegalArgumentException.class, () -> new CapabilityDecision(
                code, true, CapabilityReasonCode.ENTITLEMENT_NOT_GRANTED,
                InstallationProfile.PRO, InstallationTopology.SINGLE_NODE, Set.of(DeploymentRole.SERVER), Set.of(),
                DependencyStatus.OPERATIONAL, ActivationState.ACTIVE, "v1", "0".repeat(64), Instant.EPOCH, 1));
        assertThrows(IllegalArgumentException.class, () -> new QuotaAllocationPlan(
                "", InstallationProfile.PRO, AllocationTier.STANDARD, Map.of("x", 1L)));
        assertThrows(IllegalArgumentException.class, () -> new QuotaDecision(
                "x", -1, 0, 0, 0, false, QuotaUsageLevel.NORMAL, "reason"));
    }
}
