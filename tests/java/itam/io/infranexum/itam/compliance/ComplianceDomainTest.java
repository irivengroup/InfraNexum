package io.infranexum.itam.compliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.compliance.domain.ComplianceConflictException;
import io.infranexum.itam.compliance.domain.ComplianceStatus;
import io.infranexum.itam.compliance.domain.EvidenceSource;
import io.infranexum.itam.compliance.domain.SoftwareLicenseContract;
import io.infranexum.itam.compliance.domain.SupportCoverage;
import io.infranexum.itam.compliance.domain.SupportProviderAuthorization;
import io.infranexum.itam.compliance.domain.Warranty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit-level invariant and lifecycle coverage for PGM-07-E03 contractual aggregates. */
final class ComplianceDomainTest {
    private static final DomainIdentifier ID = id(1);
    private static final DomainIdentifier ASSET = id(2);
    private static final DomainIdentifier PARTNER = id(3);
    private static final DomainIdentifier TYPE = id(4);
    private static final DomainIdentifier ORG = id(5);
    private static final DomainIdentifier SUB = id(6);
    private static final DomainIdentifier ACTOR = id(7);
    private static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");

    @Test
    void warrantyRequiresEvidenceDatesAndVerificationBeforeCoveringAsset() {
        Warranty draft = Warranty.draft(ID, ASSET, PARTNER, TYPE, "parts_labour",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 30), LocalDate.of(2026, 9, 30),
                "CERT-1", "evidence:warranty:1", EvidenceSource.MANUAL, ACTOR, "Imported warranty", NOW);
        assertEquals(ComplianceStatus.DRAFT, draft.status());
        assertFalse(draft.verifiedComplete());
        assertFalse(draft.warrantyCovers(LocalDate.of(2026, 8, 15)));

        Warranty active = draft.activate(ACTOR, "Evidence verified", NOW.plusSeconds(1));
        assertTrue(active.verifiedComplete());
        assertTrue(active.warrantyCovers(LocalDate.of(2026, 8, 15)));
        assertFalse(active.warrantyCovers(LocalDate.of(2025, 12, 31)));
        assertFalse(active.warrantyCovers(LocalDate.of(2026, 8, 31)));
        assertThrows(ComplianceConflictException.class,
                () -> active.expire(ACTOR, "Too early", NOW.plusSeconds(2), LocalDate.of(2026, 8, 30)));
        Warranty expired = active.expire(ACTOR, "Contract ended", NOW.plusSeconds(3), LocalDate.of(2026, 8, 31));
        assertEquals(ComplianceStatus.EXPIRED, expired.status());
        assertThrows(ComplianceConflictException.class, () -> expired.activate(ACTOR, "Invalid", NOW.plusSeconds(4)));

        assertThrows(IllegalArgumentException.class, () -> Warranty.draft(ID, ASSET, PARTNER, TYPE, "parts_labour",
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 31), LocalDate.of(2026, 9, 30),
                null, "evidence:warranty:1", EvidenceSource.MANUAL, ACTOR, "Invalid dates", NOW));
        assertThrows(IllegalArgumentException.class, () -> Warranty.restore(ID, ASSET, PARTNER, TYPE, "parts_labour",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 30), LocalDate.of(2026, 9, 30),
                null, "evidence:warranty:1", EvidenceSource.MANUAL, ComplianceStatus.ACTIVE, null, null,
                1, NOW, NOW, ACTOR, ACTOR, "Invalid persisted state"));
    }

    @Test
    void softwareLicenseRejectsIncompleteRightsAndExpiresOnlyAfterEndDate() {
        SoftwareLicenseContract draft = SoftwareLicenseContract.draft(ID, ASSET, PARTNER, "LIC-1", "subscription",
                "production use", 10, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 1),
                "evidence:license:1", EvidenceSource.IMPORT, ACTOR, "Imported entitlement", NOW);
        SoftwareLicenseContract active = draft.activate(ACTOR, "Entitlement verified", NOW.plusSeconds(1));
        assertTrue(active.covers(LocalDate.of(2026, 8, 15)));
        assertFalse(active.covers(LocalDate.of(2027, 1, 2)));
        assertThrows(ComplianceConflictException.class,
                () -> active.expire(ACTOR, "Too early", NOW.plusSeconds(2), LocalDate.of(2027, 1, 1)));
        assertEquals(ComplianceStatus.EXPIRED,
                active.expire(ACTOR, "Contract ended", NOW.plusSeconds(3), LocalDate.of(2027, 1, 2)).status());
        assertThrows(IllegalArgumentException.class, () -> SoftwareLicenseContract.draft(ID, ASSET, PARTNER,
                "LIC-1", "subscription", "production use", 0, LocalDate.of(2026, 1, 1), null,
                LocalDate.of(2027, 1, 1), "evidence:license:1", EvidenceSource.MANUAL, ACTOR, "Invalid quantity", NOW));
        assertThrows(IllegalArgumentException.class, () -> SoftwareLicenseContract.draft(ID, ASSET, PARTNER,
                "LIC-1", "subscription", "production use", 1, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1),
                LocalDate.of(2027, 1, 1), "evidence:license:1", EvidenceSource.MANUAL, ACTOR, "Invalid dates", NOW));
    }

    @Test
    void supportAuthorizationMatchesAllScopesAndSuspensionFailsClosed() {
        SupportProviderAuthorization draft = SupportProviderAuthorization.draft(ID, PARTNER, ORG, Set.of(id(20)),
                Set.of("server"), Set.of(SUB), "24x7", "Europe/Paris", Set.of("gold"), Set.of("support"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), ACTOR, "Authorization imported", NOW);
        SupportProviderAuthorization active = draft.activate(ACTOR, "Scope approved", NOW.plusSeconds(1), LocalDate.of(2026, 8, 15));
        assertTrue(active.covers(id(20), "server", SUB, "gold", LocalDate.of(2026, 8, 15)));
        assertFalse(active.covers(id(21), "server", SUB, "gold", LocalDate.of(2026, 8, 15)));
        assertFalse(active.covers(id(20), "router", SUB, "gold", LocalDate.of(2026, 8, 15)));
        assertFalse(active.covers(id(20), "server", id(22), "gold", LocalDate.of(2026, 8, 15)));
        assertFalse(active.covers(id(20), "server", SUB, "silver", LocalDate.of(2026, 8, 15)));
        assertEquals(ComplianceStatus.REVIEW_REQUIRED, active.suspend(ACTOR, "Authorization suspended", NOW.plusSeconds(2)).status());
        assertThrows(ComplianceConflictException.class,
                () -> draft.activate(ACTOR, "Outside period", NOW.plusSeconds(1), LocalDate.of(2028, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> SupportProviderAuthorization.draft(ID, PARTNER, ORG, Set.of(),
                Set.of("server"), Set.of(), "24x7", "Europe/Paris", Set.of("gold"), Set.of("support"),
                LocalDate.of(2026, 1, 1), null, ACTOR, "Invalid scope", NOW));
        assertThrows(java.time.DateTimeException.class, () -> SupportProviderAuthorization.draft(ID, PARTNER, ORG, Set.of(id(20)),
                Set.of("server"), Set.of(), "24x7", "Mars/Olympus", Set.of("gold"), Set.of("support"),
                LocalDate.of(2026, 1, 1), null, ACTOR, "Invalid timezone", NOW));
    }

    @Test
    void supportCoverageRequiresActiveStateAndStrictDates() {
        SupportCoverage draft = SupportCoverage.draft(ID, ASSET, PARTNER, id(30), "SUP-1", "hardware_maintenance", "gold",
                LocalDate.of(2026, 8, 1), LocalDate.of(2027, 8, 1), id(20), "server", ORG, SUB,
                "evidence:support:1", ACTOR, "Coverage imported", NOW);
        assertFalse(draft.covers(LocalDate.of(2026, 8, 15)));
        SupportCoverage active = draft.activate(ACTOR, "Coverage verified", NOW.plusSeconds(1));
        assertTrue(active.covers(LocalDate.of(2026, 8, 15)));
        SupportCoverage review = active.requireReview(ACTOR, "Authorization suspended", NOW.plusSeconds(2));
        assertFalse(review.covers(LocalDate.of(2026, 8, 15)));
        assertThrows(ComplianceConflictException.class,
                () -> active.expire(ACTOR, "Too early", NOW.plusSeconds(2), LocalDate.of(2027, 8, 1)));
        assertEquals(ComplianceStatus.EXPIRED,
                active.expire(ACTOR, "Coverage ended", NOW.plusSeconds(3), LocalDate.of(2027, 8, 2)).status());
        assertThrows(IllegalArgumentException.class, () -> SupportCoverage.draft(ID, ASSET, PARTNER, id(30), null,
                "hardware_maintenance", "gold", LocalDate.of(2027, 8, 2), LocalDate.of(2027, 8, 1), id(20), "server",
                ORG, SUB, "evidence:support:1", ACTOR, "Invalid dates", NOW));
    }

    private static DomainIdentifier id(int suffix) {
        return DomainIdentifier.parse("01900000-0000-7000-8000-" + String.format("%012d", suffix));
    }
}
