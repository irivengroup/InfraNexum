package io.infranexum.itam.partner;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.partner.application.PartnerCommandContext;
import io.infranexum.itam.partner.application.PartnerSearchCriteria;
import io.infranexum.itam.partner.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Covers independent partner-governance validation and lifecycle branches. */
final class PartnerBoundarySaturationTest {
    private static final DomainIdentifier ID=id(1),ORG=id(2),ACTOR=id(3);
    private static final Instant T=Instant.parse("2026-08-16T18:00:00Z");
    private static final LocalDate D=LocalDate.of(2026,8,16);

    @Test void contactsExerciseEveryReachabilityAndUriPredicate() {
        assertDoesNotThrow(()->new PartnerContact("support","Desk","a@b.example",null,null));
        assertDoesNotThrow(()->new PartnerContact("support","Desk",null,"+331234",null));
        assertDoesNotThrow(()->new PartnerContact("support","Desk",null,null,"https://support.example.test/path"));
        assertThrows(IllegalArgumentException.class,()->new PartnerContact("support","Desk",null,null,null));
        assertThrows(IllegalArgumentException.class,()->new PartnerContact("1bad","Desk","a@b.example",null,null));
        assertThrows(IllegalArgumentException.class,()->new PartnerContact("support","Desk","bad",null,null));
        assertThrows(IllegalArgumentException.class,()->new PartnerContact("support","Desk",null,null,"ftp://example.test"));
        assertThrows(IllegalArgumentException.class,()->new PartnerContact("support","Desk",null,null,"https://user@example.test"));
        assertThrows(IllegalArgumentException.class,()->new PartnerContact("support","Desk",null,null,"https:///missing-host"));
        assertThrows(IllegalArgumentException.class,()->new PartnerContact("support\n","Desk","a@b.example",null,null));
        assertThrows(IllegalArgumentException.class,()->new PartnerContact("support","Desk\n","a@b.example",null,null));
    }

    @Test void accreditationAndExternalIdsFenceDatesControlsAndIdentityTokens() {
        var accreditation=new PartnerAccreditation(" CERT "," Issuer ",D,D.plusDays(1)," proof ");
        assertEquals("CERT",accreditation.code());
        assertThrows(IllegalArgumentException.class,()->new PartnerAccreditation("CERT","Issuer",D,D.minusDays(1),"proof"));
        assertThrows(IllegalArgumentException.class,()->new PartnerAccreditation("C\n","Issuer",D,null,"proof"));
        assertThrows(IllegalArgumentException.class,()->new PartnerAccreditation("X".repeat(121),"Issuer",D,null,"proof"));
        assertThrows(IllegalArgumentException.class,()->new PartnerAccreditation("CERT","X".repeat(201),D,null,"proof"));
        assertThrows(IllegalArgumentException.class,()->new PartnerAccreditation("CERT","Issuer",D,null,"X".repeat(241)));
        PartnerExternalId ext=new PartnerExternalId(" duns "," ab-12 ");
        assertEquals("duns:AB-12",ext.identityToken());
        assertThrows(IllegalArgumentException.class,()->new PartnerExternalId("1bad","x"));
        assertThrows(IllegalArgumentException.class,()->new PartnerExternalId("duns"," "));
        assertThrows(IllegalArgumentException.class,()->new PartnerExternalId("duns\n","x"));
        assertThrows(IllegalArgumentException.class,()->new PartnerExternalId("duns","x\n"));
    }

    @Test void aggregateSeparatesOpenAndBoundedAuthorizationPeriodsAndTransitionRules() {
        Partner open=partner(D,null,PartnerAuthorizationStatus.DRAFT);
        Partner pending=open.submitApproval(ACTOR,"submit",T.plusSeconds(1));
        Partner active=pending.authorize(ACTOR,"approve",T.plusSeconds(2),D);
        assertTrue(active.selectableOn(D)); assertTrue(active.selectableOn(D.plusYears(2)));
        assertFalse(open.selectableOn(D)); assertFalse(active.selectableOn(D.minusDays(1)));
        Partner suspended=active.suspend(ACTOR,"suspend",T.plusSeconds(3)); assertFalse(suspended.selectableOn(D));
        Partner reactivated=suspended.reactivate(ACTOR,"reactivate",T.plusSeconds(4),D); assertTrue(reactivated.selectableOn(D));
        Partner retired=reactivated.retire(ACTOR,"retire",T.plusSeconds(5)); assertFalse(retired.selectableOn(D));
        assertThrows(PartnerConflictException.class,()->retired.submitApproval(ACTOR,"bad",T.plusSeconds(6)));
        assertThrows(IllegalArgumentException.class,()->active.suspend(ACTOR,"bad",T.minusSeconds(1)));

        Partner bounded=partner(D,D.plusDays(10),PartnerAuthorizationStatus.PENDING_APPROVAL);
        assertThrows(PartnerConflictException.class,()->bounded.authorize(ACTOR,"early",T,D.minusDays(1)));
        assertThrows(PartnerConflictException.class,()->bounded.authorize(ACTOR,"late",T,D.plusDays(11)));
        Partner boundedActive=bounded.authorize(ACTOR,"ok",T,D.plusDays(10));
        assertTrue(boundedActive.selectableOn(D.plusDays(10))); assertFalse(boundedActive.selectableOn(D.plusDays(11)));
        Partner boundedSuspended=boundedActive.suspend(ACTOR,"pause",T.plusSeconds(1));
        assertThrows(PartnerConflictException.class,()->boundedSuspended.reactivate(ACTOR,"early",T.plusSeconds(2),D.minusDays(1)));
        assertThrows(PartnerConflictException.class,()->boundedSuspended.reactivate(ACTOR,"late",T.plusSeconds(2),D.plusDays(11)));
    }

    @Test void aggregateValidatesRolesAliasesCountryUrisVersionAndTimestampsIndependently() {
        assertThrows(IllegalArgumentException.class,()->Partner.draft(ID,ORG,null,new PartnerCode("ABC"),"Legal","Display","FR",Set.of(),D,null,null,null,List.of(),List.of(),List.of(),List.of(),ACTOR,"reason",T));
        List<String> aliases=new ArrayList<>(); for(int i=0;i<65;i++) aliases.add("Alias "+i);
        assertThrows(IllegalArgumentException.class,()->Partner.draft(ID,ORG,null,new PartnerCode("ABC"),"Legal","Display","FR",Set.of(PartnerRole.MANUFACTURER),D,null,null,null,aliases,List.of(),List.of(),List.of(),ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->Partner.draft(ID,ORG,null,new PartnerCode("ABC"),"Legal","Display","XX",Set.of(PartnerRole.MANUFACTURER),D,null,null,null,List.of(),List.of(),List.of(),List.of(),ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->Partner.draft(ID,ORG,null,new PartnerCode("ABC"),"Legal","Display","FR",Set.of(PartnerRole.MANUFACTURER),D,null,"ftp://example.test",null,List.of(),List.of(),List.of(),List.of(),ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->Partner.draft(ID,ORG,null,new PartnerCode("ABC"),"Legal\n","Display","FR",Set.of(PartnerRole.MANUFACTURER),D,null,null,null,List.of(),List.of(),List.of(),List.of(),ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->Partner.restore(ID,ORG,null,new PartnerCode("ABC"),"Legal","Display","FR",Set.of(PartnerRole.MANUFACTURER),PartnerAuthorizationStatus.DRAFT,D,null,null,null,List.of(),List.of(),List.of(),List.of(),0,T,T,ACTOR,ACTOR,"reason"));
        assertThrows(IllegalArgumentException.class,()->Partner.restore(ID,ORG,null,new PartnerCode("ABC"),"Legal","Display","FR",Set.of(PartnerRole.MANUFACTURER),PartnerAuthorizationStatus.DRAFT,D,null,null,null,List.of(),List.of(),List.of(),List.of(),1,T,T.minusNanos(1),ACTOR,ACTOR,"reason"));
        Partner identity=Partner.draft(ID,ORG,null,new PartnerCode("ABC"),"Société Éxample","Display","FR",Set.of(PartnerRole.MANUFACTURER),D,null,null,null,List.of(" Societe Example "),List.of(new PartnerExternalId("duns","12")),List.of(),List.of(),ACTOR,"reason",T);
        assertTrue(identity.identityTokens().stream().anyMatch(v->v.startsWith("name:FR:")));
        assertTrue(identity.identityTokens().stream().anyMatch(v->v.startsWith("external:")));
        assertThrows(IllegalArgumentException.class,()->Partner.draft(ID,ORG,null,new PartnerCode("ABC"),"x","Display","FR",Set.of(PartnerRole.MANUFACTURER),D,null,null,null,List.of(),List.of(),List.of(),List.of(),ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->Partner.draft(ID,ORG,null,new PartnerCode("ABC"),"Legal","Display","F",Set.of(PartnerRole.MANUFACTURER),D,null,null,null,List.of(),List.of(),List.of(),List.of(),ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->Partner.draft(ID,ORG,null,new PartnerCode("ABC"),"Legal","Display","FR",Set.of(PartnerRole.MANUFACTURER),D,null,"x".repeat(2049),null,List.of(),List.of(),List.of(),List.of(),ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->Partner.draft(ID,ORG,null,new PartnerCode("ABC"),"Legal","Display","FR",Set.of(PartnerRole.MANUFACTURER),D,null,"https://user@example.test",null,List.of(),List.of(),List.of(),List.of(),ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->Partner.draft(ID,ORG,null,new PartnerCode("ABC"),"L".repeat(321),"Display","FR",Set.of(PartnerRole.MANUFACTURER),D,null,null,null,List.of(),List.of(),List.of(),List.of(),ACTOR,"reason",T));
        assertDoesNotThrow(()->Partner.draft(id(101),ORG,null,new PartnerCode("ABD"),"Legal","Display","FR",Set.of(PartnerRole.MANUFACTURER),D,null,"   ","   ",List.of(),List.of(),List.of(),List.of(),ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->Partner.draft(id(102),ORG,null,new PartnerCode("ABE"),"Legal","Display","FR",Set.of(PartnerRole.MANUFACTURER),D,null,"//example.test/path",null,List.of(),List.of(),List.of(),List.of(),ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->Partner.draft(id(103),ORG,null,new PartnerCode("ABF"),"Legal","Display","FR",Set.of(PartnerRole.MANUFACTURER),D,null,"https:/missing-host",null,List.of(),List.of(),List.of(),List.of(),ACTOR,"reason",T));
    }

    @Test void contextsSearchAndContactLengthBoundariesAreFailClosed() {
        DomainIdentifier corr=id(10);
        assertThrows(IllegalArgumentException.class,()->new PartnerCommandContext(ACTOR,corr,"valid-key","reason\n"));
        assertThrows(IllegalArgumentException.class,()->new PartnerCommandContext(ACTOR,corr,"valid-key","x".repeat(1025)));
        assertThrows(IllegalArgumentException.class,()->new PartnerContact("support","D","a@b.example",null,null));
        assertThrows(IllegalArgumentException.class,()->new PartnerContact("support","Desk","a@b.example",null,"x".repeat(2049)));
        assertThrows(IllegalArgumentException.class,()->new PartnerContact("support","Desk",null,null,"mailto:a@example.test"));
        assertDoesNotThrow(()->new PartnerContact("support","Desk","a@b.example","   ",null));
        assertThrows(IllegalArgumentException.class,()->new PartnerContact("support","Desk",null,null,"//example.test/path"));
        assertThrows(IllegalArgumentException.class,()->new PartnerContact("support","Desk",null,null,"https:/missing-host"));
        assertThrows(IllegalArgumentException.class,()->new PartnerSearchCriteria(ORG,null,null,"F\n",null,null,null,20));
        assertThrows(IllegalArgumentException.class,()->new PartnerSearchCriteria(ORG,null,null,"FR","cert\n",null,null,20));
        assertThrows(IllegalArgumentException.class,()->new PartnerSearchCriteria(ORG,null,null,"FRA",null,null,null,20));
        assertThrows(IllegalArgumentException.class,()->new PartnerSearchCriteria(ORG,null,null,"FR",null,null,null,0));
        assertThrows(IllegalArgumentException.class,()->new PartnerSearchCriteria(ORG,null,null,"FR",null,null,null,201));
        assertNull(new PartnerSearchCriteria(ORG,null,null," "," ",null,null,20).countryCode());
    }

    private static Partner partner(LocalDate from,LocalDate until,PartnerAuthorizationStatus status){
        return Partner.restore(ID,ORG,null,new PartnerCode("ABC"),"Legal Name","Display Name","FR",Set.of(PartnerRole.MANUFACTURER),status,from,until,"https://example.test",null,List.of(),List.of(),List.of(),List.of(),1,T,T,ACTOR,ACTOR,"reason");
    }
    private static DomainIdentifier id(long n){return DomainIdentifier.parse("01900000-0000-7000-8000-"+String.format("%012d",n));}
}
