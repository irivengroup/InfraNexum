package io.infranexum.itam.compliance;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.compliance.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Saturates contractual period, verification, support-scope and terminal-state predicates. */
final class ComplianceBoundarySaturationTest {
    private static final DomainIdentifier ID=id(1),ASSET=id(2),PARTNER=id(3),TYPE=id(4),ACTOR=id(5),ORG=id(6),SUB=id(7),AUTH=id(8),MFG=id(9);
    private static final LocalDate START=LocalDate.of(2026,1,1), END=LocalDate.of(2026,12,31);
    private static final Instant T=Instant.parse("2026-08-16T18:00:00Z");

    @Test void warrantySeparatesAllVerifiedAndCoverageOperands() {
        Warranty draft=Warranty.draft(ID,ASSET,PARTNER,TYPE,"standard",START,END,END,"CERT","proof",EvidenceSource.MANUAL,ACTOR,"reason",T);
        assertEquals("standard", draft.coverageLevel());
        assertFalse(draft.verifiedComplete()); assertFalse(draft.warrantyCovers(START));
        Warranty active=draft.activate(ACTOR,"activate",T.plusSeconds(1));
        assertDoesNotThrow(()->active.revise(PARTNER,TYPE,"standard",START,END,END,"CERT","proof",EvidenceSource.MANUAL,ACTOR,"revise",T.plusSeconds(2)));
        assertTrue(active.verifiedComplete()); assertTrue(active.warrantyCovers(START)); assertTrue(active.warrantyCovers(END));
        assertFalse(active.warrantyCovers(START.minusDays(1))); assertFalse(active.warrantyCovers(END.plusDays(1)));
        assertThrows(ComplianceConflictException.class,()->draft.expire(ACTOR,"expire",T.plusSeconds(2),END.plusDays(1)));
        assertThrows(ComplianceConflictException.class,()->active.expire(ACTOR,"expire",T.plusSeconds(2),END));
        Warranty expired=active.expire(ACTOR,"expire",T.plusSeconds(2),END.plusDays(1)); assertTrue(expired.warrantyCovers(END));
        Warranty cancelled=active.cancel(ACTOR,"cancel",T.plusSeconds(2)); assertFalse(cancelled.warrantyCovers(START));
        Warranty superseded=active.supersede(ACTOR,"supersede",T.plusSeconds(2)); assertFalse(superseded.warrantyCovers(START));
        assertThrows(ComplianceConflictException.class,()->cancelled.cancel(ACTOR,"again",T.plusSeconds(3)));
        assertThrows(ComplianceConflictException.class,()->superseded.cancel(ACTOR,"again",T.plusSeconds(3)));
        assertThrows(ComplianceConflictException.class,()->draft.supersede(ACTOR,"bad",T.plusSeconds(1)));
        assertThrows(ComplianceConflictException.class,()->expired.revise(PARTNER,TYPE,"standard",START,END,END,"CERT","proof",EvidenceSource.MANUAL,ACTOR,"reason",T.plusSeconds(3)));
        assertThrows(IllegalArgumentException.class,()->Warranty.restore(ID,ASSET,PARTNER,TYPE,"standard",START,END,END,"CERT","proof",EvidenceSource.MANUAL,ComplianceStatus.ACTIVE,null,ACTOR,1,T,T,ACTOR,ACTOR,"reason"));
        assertThrows(IllegalArgumentException.class,()->Warranty.restore(ID,ASSET,PARTNER,TYPE,"standard",START,END,END,"CERT","proof",EvidenceSource.MANUAL,ComplianceStatus.ACTIVE,T,null,1,T,T,ACTOR,ACTOR,"reason"));
        assertThrows(IllegalArgumentException.class,()->Warranty.draft(ID,ASSET,PARTNER,TYPE,"standard",END,START,END,"CERT","proof",EvidenceSource.MANUAL,ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->Warranty.draft(ID,ASSET,PARTNER,TYPE,"standard",START,END,START.minusDays(1),"CERT","proof",EvidenceSource.MANUAL,ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->Warranty.draft(ID,ASSET,PARTNER,TYPE,"standard\n",START,END,END,"CERT","proof",EvidenceSource.MANUAL,ACTOR,"reason",T));
    }

    @Test void licenseSeparatesOpenEndedBoundedAndVerificationOperands() {
        SoftwareLicenseContract open=SoftwareLicenseContract.draft(ID,ASSET,PARTNER,"C-1","perpetual","production",1,START,null,END,"proof",EvidenceSource.MANUAL,ACTOR,"reason",T);
        assertEquals("C-1", open.contractNumber());
        SoftwareLicenseContract openActive=open.activate(ACTOR,"activate",T.plusSeconds(1));
        assertTrue(openActive.covers(END.plusYears(10))); assertThrows(ComplianceConflictException.class,()->openActive.expire(ACTOR,"expire",T.plusSeconds(2),END.plusYears(10)));
        SoftwareLicenseContract bounded=SoftwareLicenseContract.draft(id(20),ASSET,PARTNER,"C-2","subscription","production",1,START,END,END,"proof",EvidenceSource.IMPORT,ACTOR,"reason",T).activate(ACTOR,"activate",T.plusSeconds(1));
        assertTrue(bounded.covers(START)); assertTrue(bounded.covers(END)); assertFalse(bounded.covers(START.minusDays(1))); assertFalse(bounded.covers(END.plusDays(1)));
        assertThrows(ComplianceConflictException.class,()->bounded.expire(ACTOR,"expire",T.plusSeconds(2),END));
        assertEquals(ComplianceStatus.EXPIRED,bounded.expire(ACTOR,"expire",T.plusSeconds(2),END.plusDays(1)).status());
        assertThrows(IllegalArgumentException.class,()->SoftwareLicenseContract.draft(ID,ASSET,PARTNER,"C","model","rights",0,START,END,END,"proof",EvidenceSource.MANUAL,ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->SoftwareLicenseContract.draft(ID,ASSET,PARTNER,"C","model","rights",1,END,START,END,"proof",EvidenceSource.MANUAL,ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->SoftwareLicenseContract.draft(ID,ASSET,PARTNER,"C","model","rights",1,START,null,START.minusDays(1),"proof",EvidenceSource.MANUAL,ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->SoftwareLicenseContract.draft(ID,ASSET,PARTNER,"C\n","model","rights",1,START,null,END,"proof",EvidenceSource.MANUAL,ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->SoftwareLicenseContract.restore(ID,ASSET,PARTNER,"C-3","model","rights",1,START,END,END,"proof",EvidenceSource.MANUAL,ComplianceStatus.DRAFT,null,null,0,T,T,ACTOR,ACTOR,"reason"));
        assertThrows(IllegalArgumentException.class,()->SoftwareLicenseContract.restore(ID,ASSET,PARTNER,"C-3","model","rights",1,START,END,END,"proof",EvidenceSource.MANUAL,ComplianceStatus.DRAFT,null,null,1,T,T.minusNanos(1),ACTOR,ACTOR,"reason"));
        assertThrows(ComplianceConflictException.class,()->openActive.activate(ACTOR,"again",T.plusSeconds(2)));
        assertThrows(ComplianceConflictException.class,()->open.expire(ACTOR,"bad",T.plusSeconds(2),END.plusYears(2)));
        SoftwareLicenseContract cancelled=openActive.cancel(ACTOR,"cancel",T.plusSeconds(2));
        assertThrows(ComplianceConflictException.class,()->cancelled.cancel(ACTOR,"again",T.plusSeconds(3)));
        SoftwareLicenseContract superseded=SoftwareLicenseContract.restore(id(22),ASSET,PARTNER,"C-4","model","rights",1,START,END,END,"proof",EvidenceSource.MANUAL,ComplianceStatus.SUPERSEDED,T,ACTOR,2,T,T.plusSeconds(1),ACTOR,ACTOR,"reason");
        assertThrows(ComplianceConflictException.class,()->superseded.cancel(ACTOR,"again",T.plusSeconds(3)));
    }

    @Test void supportAuthorizationCoversEveryFilterAndSubdivisionOperand() {
        SupportProviderAuthorization scoped=SupportProviderAuthorization.draft(ID,PARTNER,ORG,Set.of(MFG),Set.of("server"),Set.of(SUB),"24x7","UTC",Set.of("gold"),Set.of("email"),START,END,ACTOR,"reason",T).activate(ACTOR,"activate",T.plusSeconds(1),START);
        assertEquals(Set.of(MFG), scoped.supportedManufacturerIds());
        assertEquals(Set.of("server"), scoped.supportedObjectTypes());
        assertEquals(Set.of(SUB), scoped.subdivisionScopes());
        assertEquals("24x7", scoped.serviceHours());
        assertEquals("UTC", scoped.timeZoneId());
        assertEquals(Set.of("gold"), scoped.serviceLevels());
        assertEquals(Set.of("email"), scoped.escalationContactTypes());
        assertEquals(T, scoped.createdAt());
        assertEquals("activate", scoped.lastReason());
        assertTrue(scoped.covers(MFG,"server",SUB,"gold",START));
        assertFalse(scoped.covers(id(99),"server",SUB,"gold",START));
        assertFalse(scoped.covers(MFG,"router",SUB,"gold",START));
        assertFalse(scoped.covers(MFG,"server",SUB,"silver",START));
        assertFalse(scoped.covers(MFG,"server",null,"gold",START));
        assertFalse(scoped.covers(MFG,"server",id(98),"gold",START));
        assertFalse(scoped.covers(MFG,"server",SUB,"gold",START.minusDays(1)));
        assertFalse(scoped.covers(MFG,"server",SUB,"gold",END.plusDays(1)));
        SupportProviderAuthorization global=SupportProviderAuthorization.draft(id(21),PARTNER,ORG,Set.of(MFG),Set.of("server"),Set.of(),"24x7","UTC",Set.of("gold"),Set.of("email"),START,null,ACTOR,"reason",T).activate(ACTOR,"activate",T,START);
        assertTrue(global.covers(MFG,"server",null,"gold",END.plusYears(5)));
        assertThrows(IllegalArgumentException.class,()->SupportProviderAuthorization.draft(ID,PARTNER,ORG,Set.of(),Set.of("server"),Set.of(),"24x7","UTC",Set.of("gold"),Set.of("email"),START,null,ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->SupportProviderAuthorization.draft(ID,PARTNER,ORG,Set.of(MFG),Set.of(),Set.of(),"24x7","UTC",Set.of("gold"),Set.of("email"),START,null,ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->SupportProviderAuthorization.draft(ID,PARTNER,ORG,Set.of(MFG),Set.of("server"),Set.of(),"24x7","UTC",Set.of(),Set.of("email"),START,null,ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->SupportProviderAuthorization.draft(ID,PARTNER,ORG,Set.of(MFG),Set.of("server"),Set.of(),"24x7","UTC",Set.of("gold"),Set.of(),START,null,ACTOR,"reason",T));
        assertThrows(DateTimeException.class,()->SupportProviderAuthorization.draft(ID,PARTNER,ORG,Set.of(MFG),Set.of("server"),Set.of(),"24x7","Not/AZone",Set.of("gold"),Set.of("email"),START,null,ACTOR,"reason",T));
    }

    @Test void supportCoverageSeparatesLifecycleAndDatePredicates() {
        SupportCoverage draft=SupportCoverage.draft(ID,ASSET,PARTNER,AUTH,null,"hardware","gold",START,END,MFG,"server",ORG,SUB,"proof",ACTOR,"reason",T);
        assertFalse(draft.covers(START));
        SupportCoverage active=draft.activate(ACTOR,"activate",T.plusSeconds(1));
        assertTrue(active.covers(START)); assertTrue(active.covers(END)); assertFalse(active.covers(START.minusDays(1))); assertFalse(active.covers(END.plusDays(1)));
        SupportCoverage review=active.requireReview(ACTOR,"review",T.plusSeconds(2)); assertFalse(review.covers(START));
        assertThrows(ComplianceConflictException.class,()->draft.requireReview(ACTOR,"bad",T));
        assertThrows(ComplianceConflictException.class,()->active.activate(ACTOR,"again",T.plusSeconds(2)));
        assertThrows(ComplianceConflictException.class,()->active.expire(ACTOR,"bad",T.plusSeconds(2),END));
        assertEquals(ComplianceStatus.EXPIRED,review.expire(ACTOR,"expire",T.plusSeconds(3),END.plusDays(1)).status());
        assertThrows(IllegalArgumentException.class,()->SupportCoverage.draft(ID,ASSET,PARTNER,AUTH,null,"hardware","gold",END,START,MFG,"server",ORG,SUB,"proof",ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->SupportCoverage.draft(ID,ASSET,PARTNER,AUTH,null,"hardware\n","gold",START,END,MFG,"server",ORG,SUB,"proof",ACTOR,"reason",T));
        SupportCoverage expired=review.expire(ACTOR,"expire",T.plusSeconds(3),END.plusDays(1));
        assertThrows(ComplianceConflictException.class,()->expired.revise(null,"hardware","gold",START,END,"proof",ACTOR,"reason",T.plusSeconds(4)));
        SupportCoverage cancelled=SupportCoverage.restore(id(23),ASSET,PARTNER,AUTH,null,"hardware","gold",START,END,MFG,"server",ORG,SUB,"proof",ComplianceStatus.CANCELLED,2,T,T.plusSeconds(1),ACTOR,ACTOR,"reason");
        assertThrows(ComplianceConflictException.class,()->cancelled.revise(null,"hardware","gold",START,END,"proof",ACTOR,"reason",T.plusSeconds(4)));
        SupportCoverage superseded=SupportCoverage.restore(id(24),ASSET,PARTNER,AUTH,null,"hardware","gold",START,END,MFG,"server",ORG,SUB,"proof",ComplianceStatus.SUPERSEDED,2,T,T.plusSeconds(1),ACTOR,ACTOR,"reason");
        assertThrows(ComplianceConflictException.class,()->superseded.revise(null,"hardware","gold",START,END,"proof",ACTOR,"reason",T.plusSeconds(4)));
    }

    @Test void commandContextAndRestoreMetadataFenceLengthsVersionsAndTime() {
        DomainIdentifier corr=id(40);
        assertThrows(NullPointerException.class,()->new io.infranexum.itam.compliance.application.ComplianceCommandContext(ACTOR,corr,null,"reason"));
        assertThrows(IllegalArgumentException.class,()->new io.infranexum.itam.compliance.application.ComplianceCommandContext(ACTOR,corr,"short","reason"));
        assertThrows(IllegalArgumentException.class,()->new io.infranexum.itam.compliance.application.ComplianceCommandContext(ACTOR,corr,"valid-key","x"));
        assertThrows(IllegalArgumentException.class,()->new io.infranexum.itam.compliance.application.ComplianceCommandContext(ACTOR,corr,"valid-key\n","reason"));
        assertThrows(IllegalArgumentException.class,()->new io.infranexum.itam.compliance.application.ComplianceCommandContext(ACTOR,corr,"x".repeat(201),"reason"));
        assertThrows(IllegalArgumentException.class,()->new io.infranexum.itam.compliance.application.ComplianceCommandContext(ACTOR,corr,"valid-key","x".repeat(1025)));
        assertThrows(IllegalArgumentException.class,()->SupportProviderAuthorization.restore(id(41),PARTNER,ORG,Set.of(MFG),Set.of("server"),Set.of(),"24x7","UTC",Set.of("gold"),Set.of("email"),START,null,ComplianceStatus.DRAFT,1,T,T.minusNanos(1),ACTOR,ACTOR,"reason"));
        assertThrows(IllegalArgumentException.class,()->SoftwareLicenseContract.draft(id(42),ASSET,PARTNER,"X".repeat(161),"model","rights",1,START,null,END,"proof",EvidenceSource.MANUAL,ACTOR,"reason",T));
    }

    @Test void revisionAndEnumsCoverOptionalAndInvalidBranches() {
        ComplianceRevision revision=new ComplianceRevision("warranty",ID,1,ComplianceStatus.DRAFT,null,"reason","{}",T,ACTOR);
        assertNull(revision.proofReference());
        assertNull(new ComplianceRevision("warranty",ID,1,ComplianceStatus.DRAFT,"  ","reason","{}",T,ACTOR).proofReference());
        assertThrows(IllegalArgumentException.class,()->new ComplianceRevision("warranty",ID,0,ComplianceStatus.DRAFT,null,"reason","{}",T,ACTOR));
        assertThrows(IllegalArgumentException.class,()->new ComplianceRevision("warranty\n",ID,1,ComplianceStatus.DRAFT,null,"reason","{}",T,ACTOR));
        assertEquals(EvidenceSource.MANUAL,EvidenceSource.parse(" MANUAL "));
        assertThrows(IllegalArgumentException.class,()->EvidenceSource.parse("unknown"));
        assertEquals(ComplianceStatus.REVIEW_REQUIRED,ComplianceStatus.parse("review_required"));
        assertTrue(ComplianceStatus.REVIEW_REQUIRED.verifiedState()); assertFalse(ComplianceStatus.DRAFT.verifiedState());
    }

    private static DomainIdentifier id(long n){return DomainIdentifier.parse("01900000-0000-7000-8000-"+String.format("%012d",n));}
}
