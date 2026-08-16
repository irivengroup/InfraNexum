package io.infranexum.itam.compliance;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.compliance.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Branch-completion tests for contractual lifecycle/value invariants. */
final class ComplianceBranchCoverageTest {
    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 12, 31);
    private static final DomainIdentifier ID=id(1), ASSET=id(2), PARTNER=id(3), TYPE=id(4), ORG=id(5), SUB=id(6), ACTOR=id(7), MFG=id(8), AUTH=id(9);

    @Test
    void enumsAlertsRevisionsAndCataloguesCoverEveryBoundary() {
        for (ComplianceStatus status : ComplianceStatus.values()) assertEquals(status, ComplianceStatus.parse(status.wireValue()));
        assertTrue(ComplianceStatus.ACTIVE.verifiedState());
        assertTrue(ComplianceStatus.EXPIRED.verifiedState());
        assertTrue(ComplianceStatus.REVIEW_REQUIRED.verifiedState());
        assertFalse(ComplianceStatus.DRAFT.verifiedState());
        assertFalse(ComplianceStatus.CANCELLED.verifiedState());
        assertFalse(ComplianceStatus.SUPERSEDED.verifiedState());
        assertThrows(NullPointerException.class, () -> ComplianceStatus.parse(null));
        assertThrows(IllegalArgumentException.class, () -> ComplianceStatus.parse("invalid"));
        for (EvidenceSource source : EvidenceSource.values()) assertEquals(source, EvidenceSource.parse(source.wireValue()));
        assertThrows(NullPointerException.class, () -> EvidenceSource.parse(null));
        assertThrows(IllegalArgumentException.class, () -> EvidenceSource.parse("invalid"));

        for (ComplianceAlertKind kind : ComplianceAlertKind.values()) {
            assertFalse(kind.wireValue().isBlank()); assertFalse(kind.eventType().isBlank());
            ComplianceAlert alert = new ComplianceAlert(kind, ID, ASSET, END, 0, 1);
            assertEquals(0L, alert.daysRemaining());
        }
        assertThrows(NullPointerException.class, () -> new ComplianceAlert(null, ID, ASSET, END, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ComplianceAlert(ComplianceAlertKind.WARRANTY_END, ID, ASSET, END, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ComplianceAlert(ComplianceAlertKind.WARRANTY_END, ID, ASSET, END, 0, 0));

        ComplianceRevision revision = new ComplianceRevision(" warranty ", ID, 1, ComplianceStatus.DRAFT, null,
                " reason ", " {} ", NOW, ACTOR);
        assertEquals("warranty", revision.recordType()); assertNull(revision.proofReference());
        assertThrows(IllegalArgumentException.class, () -> new ComplianceRevision("x", ID, 1, ComplianceStatus.DRAFT, null,"reason","{}",NOW,ACTOR));
        assertThrows(IllegalArgumentException.class, () -> new ComplianceRevision("warranty", ID, 0, ComplianceStatus.DRAFT, null,"reason","{}",NOW,ACTOR));
        assertThrows(IllegalArgumentException.class, () -> new ComplianceRevision("warranty", ID, 1, ComplianceStatus.DRAFT, null,"x","{}",NOW,ACTOR));

        WarrantyType type = new WarrantyType(TYPE," standard "," Standard warranty ",true,NOW,ACTOR);
        assertEquals("STANDARD", type.code());
        assertThrows(IllegalArgumentException.class, () -> new WarrantyType(TYPE,"1bad","Standard",true,NOW,ACTOR));
        ComplianceConflictException conflict = new ComplianceConflictException("CODE", "message");
        assertEquals("CODE", conflict.code());
        assertThrows(IllegalArgumentException.class, () -> new ComplianceConflictException(null,"x"));
        assertThrows(IllegalArgumentException.class, () -> new ComplianceConflictException(" ","x"));
        assertEquals("ITAM compliance record not found", new ComplianceNotFoundException().getMessage());
    }

    @Test
    void warrantyLifecycleCoversReviseCancelSupersedeAndPersistedStateBranches() {
        Warranty draft = warranty(ComplianceStatus.DRAFT, null, null, 1, NOW, NOW);
        Warranty revised = draft.revise(PARTNER, TYPE, "premium", START, END, END.plusMonths(1), null,
                "proof-2", EvidenceSource.INTEGRATION, ACTOR, "revise warranty", NOW.plusSeconds(1));
        assertEquals(2, revised.version()); assertEquals(ComplianceStatus.DRAFT, revised.status());
        Warranty active = draft.activate(ACTOR,"verify warranty",NOW.plusSeconds(1));
        assertThrows(ComplianceConflictException.class, () -> active.activate(ACTOR,"again",NOW.plusSeconds(2)));
        Warranty cancelled = active.cancel(ACTOR,"cancel warranty",NOW.plusSeconds(2));
        assertEquals(ComplianceStatus.CANCELLED,cancelled.status());
        assertFalse(cancelled.warrantyCovers(START.plusDays(1)));
        assertThrows(ComplianceConflictException.class, () -> cancelled.cancel(ACTOR,"again",NOW.plusSeconds(3)));
        assertThrows(ComplianceConflictException.class, () -> cancelled.revise(PARTNER,TYPE,"x",START,END,END,null,"proof",EvidenceSource.MANUAL,ACTOR,"revise",NOW.plusSeconds(3)));
        Warranty expired = active.expire(ACTOR,"expired",NOW.plusSeconds(3),END.plusDays(1));
        Warranty superseded = expired.supersede(ACTOR,"superseded",NOW.plusSeconds(4));
        assertEquals(ComplianceStatus.SUPERSEDED,superseded.status());
        assertThrows(ComplianceConflictException.class, () -> draft.supersede(ACTOR,"bad",NOW.plusSeconds(1)));
        assertFalse(warranty(ComplianceStatus.DRAFT,null,null,1,NOW,NOW).verifiedComplete());
        assertThrows(IllegalArgumentException.class, () -> warranty(ComplianceStatus.ACTIVE,null,ACTOR,1,NOW,NOW));
        assertThrows(IllegalArgumentException.class, () -> warranty(ComplianceStatus.ACTIVE,NOW,null,1,NOW,NOW));
        assertThrows(IllegalArgumentException.class, () -> warranty(ComplianceStatus.DRAFT,null,null,0,NOW,NOW));
        assertThrows(IllegalArgumentException.class, () -> warranty(ComplianceStatus.DRAFT,null,null,1,NOW,NOW.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> Warranty.draft(ID,ASSET,PARTNER,TYPE,"level",END,START,END,"CERT","proof",EvidenceSource.MANUAL,ACTOR,"reason",NOW));
        assertThrows(IllegalArgumentException.class, () -> Warranty.draft(ID,ASSET,PARTNER,TYPE,"level",START,END,START.minusDays(1),"CERT","proof",EvidenceSource.MANUAL,ACTOR,"reason",NOW));
    }

    @Test
    void licenseLifecycleCoversOpenEndedCoverageCancellationAndRestoreValidation() {
        SoftwareLicenseContract open = SoftwareLicenseContract.draft(ID,ASSET,PARTNER,"LIC-1","perpetual","production",1,START,null,END,
                "proof-license",EvidenceSource.MANUAL,ACTOR,"create license",NOW);
        SoftwareLicenseContract active = open.activate(ACTOR,"verify license",NOW.plusSeconds(1));
        assertTrue(active.covers(END.plusYears(5)));
        assertThrows(ComplianceConflictException.class, () -> active.expire(ACTOR,"no end",NOW.plusSeconds(2),END.plusYears(5)));
        SoftwareLicenseContract cancelled = active.cancel(ACTOR,"cancel",NOW.plusSeconds(2));
        assertThrows(ComplianceConflictException.class, () -> cancelled.cancel(ACTOR,"again",NOW.plusSeconds(3)));
        assertThrows(ComplianceConflictException.class, () -> cancelled.revise(PARTNER,"LIC","model","rights",1,START,null,END,"proof",EvidenceSource.MANUAL,ACTOR,"revise",NOW.plusSeconds(3)));
        SoftwareLicenseContract revised = active.revise(PARTNER,"LIC-2","subscription","rights",2,START,END,END,"proof-2",EvidenceSource.IMPORT,ACTOR,"revise",NOW.plusSeconds(2));
        assertEquals(ComplianceStatus.DRAFT,revised.status());
        assertThrows(IllegalArgumentException.class, () -> SoftwareLicenseContract.restore(ID,ASSET,PARTNER,"LIC","model","rights",1,START,END,END,"proof",EvidenceSource.MANUAL,ComplianceStatus.ACTIVE,null,ACTOR,1,NOW,NOW,ACTOR,ACTOR,"reason"));
        assertThrows(IllegalArgumentException.class, () -> SoftwareLicenseContract.restore(ID,ASSET,PARTNER,"LIC","model","rights",1,START,END,END,"proof",EvidenceSource.MANUAL,ComplianceStatus.ACTIVE,NOW,null,1,NOW,NOW,ACTOR,ACTOR,"reason"));
        assertThrows(IllegalArgumentException.class, () -> SoftwareLicenseContract.draft(ID,ASSET,PARTNER,"LIC","model","rights",1,END,START,END,"proof",EvidenceSource.MANUAL,ACTOR,"reason",NOW));
        assertThrows(IllegalArgumentException.class, () -> SoftwareLicenseContract.draft(ID,ASSET,PARTNER,"LIC","model","rights",1,START,null,START.minusDays(1),"proof",EvidenceSource.MANUAL,ACTOR,"reason",NOW));
    }

    @Test
    void supportAuthorizationAndCoverageCoverOpenScopesReviewExpiryAndInvalidRestores() {
        SupportProviderAuthorization open = SupportProviderAuthorization.draft(ID,PARTNER,ORG,Set.of(MFG),Set.of("server"),Set.of(),
                "24x7","UTC",Set.of("gold"),Set.of("email"),START,null,ACTOR,"create authorization",NOW);
        SupportProviderAuthorization active = open.activate(ACTOR,"activate",NOW.plusSeconds(1),START);
        assertTrue(active.selectableOn(END.plusYears(2)));
        assertTrue(active.covers(MFG,"server",null,"gold",END.plusYears(2)));
        assertFalse(active.covers(id(99),"server",null,"gold",END));
        SupportProviderAuthorization review = active.suspend(ACTOR,"review",NOW.plusSeconds(2));
        assertFalse(review.selectableOn(END));
        assertThrows(ComplianceConflictException.class, () -> review.suspend(ACTOR,"again",NOW.plusSeconds(3)));
        SupportProviderAuthorization reactivated = review.activate(ACTOR,"reactivate",NOW.plusSeconds(3),END);
        assertEquals(ComplianceStatus.ACTIVE,reactivated.status());
        assertThrows(ComplianceConflictException.class, () -> active.activate(ACTOR,"invalid",NOW.plusSeconds(2),END));
        SupportProviderAuthorization bounded = SupportProviderAuthorization.draft(id(20),PARTNER,ORG,Set.of(MFG),Set.of("server"),Set.of(SUB),
                "business","Europe/Paris",Set.of("gold"),Set.of("phone"),START,END,ACTOR,"bounded",NOW);
        assertThrows(ComplianceConflictException.class, () -> bounded.activate(ACTOR,"too early",NOW,START.minusDays(1)));
        assertThrows(ComplianceConflictException.class, () -> bounded.activate(ACTOR,"too late",NOW,END.plusDays(1)));
        SupportProviderAuthorization boundedActive = bounded.activate(ACTOR,"ok",NOW,START);
        assertFalse(boundedActive.covers(MFG,"server",null,"gold",START));
        assertTrue(boundedActive.covers(MFG,"server",SUB,"gold",START));
        assertThrows(IllegalArgumentException.class, () -> SupportProviderAuthorization.restore(ID,PARTNER,ORG,Set.of(MFG),Set.of("server"),Set.of(),"24x7","UTC",Set.of("gold"),Set.of("email"),END,START,ComplianceStatus.DRAFT,1,NOW,NOW,ACTOR,ACTOR,"reason"));
        assertThrows(IllegalArgumentException.class, () -> SupportProviderAuthorization.restore(ID,PARTNER,ORG,Set.of(MFG),Set.of("server"),Set.of(),"24x7","UTC",Set.of("gold"),Set.of("email"),START,null,ComplianceStatus.DRAFT,0,NOW,NOW,ACTOR,ACTOR,"reason"));

        SupportCoverage draft = SupportCoverage.draft(ID,ASSET,PARTNER,AUTH,null,"hardware","gold",START,END,MFG,"server",ORG,SUB,"proof-support",ACTOR,"create coverage",NOW);
        SupportCoverage revised = draft.revise("SUP-2","hardware","platinum",START,END,"proof-2",ACTOR,"revise coverage",NOW.plusSeconds(1));
        assertEquals(ComplianceStatus.DRAFT,revised.status());
        SupportCoverage coverageActive = draft.activate(ACTOR,"activate",NOW.plusSeconds(1));
        assertFalse(coverageActive.covers(START.minusDays(1))); assertTrue(coverageActive.covers(START)); assertTrue(coverageActive.covers(END)); assertFalse(coverageActive.covers(END.plusDays(1)));
        SupportCoverage coverageReview = coverageActive.requireReview(ACTOR,"review",NOW.plusSeconds(2));
        assertEquals(ComplianceStatus.ACTIVE, coverageReview.activate(ACTOR,"reactivate",NOW.plusSeconds(3)).status());
        assertThrows(ComplianceConflictException.class, () -> draft.requireReview(ACTOR,"bad",NOW));
        assertThrows(ComplianceConflictException.class, () -> draft.expire(ACTOR,"bad",NOW,END.plusDays(1)));
        SupportCoverage expired = coverageReview.expire(ACTOR,"expired",NOW.plusSeconds(3),END.plusDays(1));
        assertThrows(ComplianceConflictException.class, () -> expired.revise(null,"hardware","gold",START,END,"proof",ACTOR,"bad",NOW.plusSeconds(4)));
        assertThrows(IllegalArgumentException.class, () -> SupportCoverage.restore(ID,ASSET,PARTNER,AUTH,null,"hardware","gold",START,END,MFG,"server",ORG,SUB,"proof",ComplianceStatus.DRAFT,0,NOW,NOW,ACTOR,ACTOR,"reason"));
        assertThrows(IllegalArgumentException.class, () -> SupportCoverage.restore(ID,ASSET,PARTNER,AUTH,null,"hardware","gold",START,END,MFG,"server",ORG,SUB,"proof",ComplianceStatus.DRAFT,1,NOW,NOW.minusSeconds(1),ACTOR,ACTOR,"reason"));
    }

    private static Warranty warranty(ComplianceStatus status, Instant verifiedAt, DomainIdentifier verifiedBy, long version, Instant createdAt, Instant updatedAt) {
        return Warranty.restore(ID,ASSET,PARTNER,TYPE,"premium",START,END,END.plusMonths(1),"CERT","proof-warranty",EvidenceSource.MANUAL,status,verifiedAt,verifiedBy,version,createdAt,updatedAt,ACTOR,ACTOR,"restore warranty");
    }
    private static DomainIdentifier id(int n){ return DomainIdentifier.parse("01900000-0000-7000-8000-"+String.format("%012d",n)); }
}
