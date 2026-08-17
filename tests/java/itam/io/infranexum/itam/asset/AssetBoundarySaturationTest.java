package io.infranexum.itam.asset;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.asset.application.AssetCommandContext;
import io.infranexum.itam.asset.domain.*;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;

/** Covers each independent lifecycle/custody predicate on the ITAM asset aggregate. */
final class AssetBoundarySaturationTest {
    private static final DomainIdentifier ID=id(1),RSOT=id(2),ORG=id(3),SUB=id(4),ACTOR=id(5),PARTNER=id(6);
    private static final Instant T=Instant.parse("2026-08-16T18:00:00Z");

    @Test void lifecycleExercisesEveryAllowedCustodianClassAndTerminalFence() {
        Asset acquired=asset(AssetLifecycleStatus.ACQUIRED,AssetCustodian.organization(ORG));
        assertThrows(AssetConflictException.class,()->acquired.stock(AssetCustodian.organization(ORG),ACTOR,"stock",T.plusSeconds(1)));
        Asset received=acquired.receive(AssetCustodian.organization(ORG),ACTOR,"receive",T.plusSeconds(1));
        assertThrows(AssetConflictException.class,()->received.assign(AssetCustodian.none(),ACTOR,"assign",T.plusSeconds(2)));
        assertThrows(AssetConflictException.class,()->received.assign(new AssetCustodian(AssetCustodianKind.PARTNER,PARTNER),ACTOR,"assign",T.plusSeconds(2)));
        Asset assigned=received.assign(new AssetCustodian(AssetCustodianKind.ACTOR,ACTOR),ACTOR,"assign",T.plusSeconds(2));
        assertThrows(AssetConflictException.class,()->assigned.deploy(AssetCustodian.none(),ACTOR,"deploy",T.plusSeconds(3)));
        assertThrows(AssetConflictException.class,()->assigned.deploy(new AssetCustodian(AssetCustodianKind.PARTNER,PARTNER),ACTOR,"deploy",T.plusSeconds(3)));
        Asset deployed=assigned.deploy(new AssetCustodian(AssetCustodianKind.SUBDIVISION,SUB),ACTOR,"deploy",T.plusSeconds(3));
        assertThrows(AssetConflictException.class,()->deployed.transfer(AssetCustodian.none(),ACTOR,"transfer",T.plusSeconds(4)));
        Asset transferred=deployed.transfer(new AssetCustodian(AssetCustodianKind.PARTNER,PARTNER),ACTOR,"transfer",T.plusSeconds(4));
        assertThrows(AssetConflictException.class,()->transferred.startMaintenance(AssetCustodian.none(),ACTOR,"maintain",T.plusSeconds(5)));
        assertThrows(AssetConflictException.class,()->transferred.startMaintenance(new AssetCustodian(AssetCustodianKind.ACTOR,ACTOR),ACTOR,"maintain",T.plusSeconds(5)));
        Asset maintenance=transferred.startMaintenance(new AssetCustodian(AssetCustodianKind.PARTNER,PARTNER),ACTOR,"maintain",T.plusSeconds(5));
        assertThrows(AssetConflictException.class,()->maintenance.returnFromMaintenance(new AssetCustodian(AssetCustodianKind.ACTOR,ACTOR),ACTOR,"return",T.plusSeconds(6)));
        Asset returned=maintenance.returnFromMaintenance(AssetCustodian.organization(ORG),ACTOR,"return",T.plusSeconds(6));
        Asset stock=returned.stock(new AssetCustodian(AssetCustodianKind.SUBDIVISION,SUB),ACTOR,"stock",T.plusSeconds(7));
        Asset retired=stock.retire(ACTOR,"retire",T.plusSeconds(8));
        Asset disposed=retired.dispose(ACTOR,"dispose",T.plusSeconds(9));
        assertThrows(AssetConflictException.class,()->disposed.setProducer(PARTNER,ACTOR,"producer",T.plusSeconds(10)));
        assertThrows(AssetConflictException.class,()->disposed.retire(ACTOR,"again",T.plusSeconds(10)));
    }

    @Test void producerNoopBackwardTimeAndTextControlsAreIndependentlyCovered() {
        Asset original=Asset.acquired(ID,RSOT,AssetType.HARDWARE,ORG,SUB,LocalDate.of(2026,1,1),new AssetValue(BigDecimal.ONE,"EUR"),null,PARTNER,ACTOR,"reason",T);
        assertSame(original,original.setProducer(PARTNER,ACTOR,"same",T));
        Asset changed=original.setProducer(id(99),ACTOR,"change",T.plusSeconds(1)); assertEquals(2,changed.version());
        assertThrows(IllegalArgumentException.class,()->original.receive(AssetCustodian.organization(ORG),ACTOR,"reason",T.minusNanos(1)));
        assertThrows(IllegalArgumentException.class,()->original.receive(AssetCustodian.organization(ORG),ACTOR,"x",T.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->original.receive(AssetCustodian.organization(ORG),ACTOR,"reason\n",T.plusSeconds(1)));
        assertThrows(NullPointerException.class,()->original.setProducer(null,ACTOR,"reason",T));
        assertThrows(IllegalArgumentException.class,()->Asset.restore(ID,RSOT,AssetType.HARDWARE,ORG,SUB,LocalDate.of(2026,1,1),new AssetValue(BigDecimal.ONE,"EUR"),null,PARTNER,AssetLifecycleStatus.ACQUIRED,AssetCustodian.organization(ORG),1,T,T,ACTOR,ACTOR,"x"));
        assertThrows(IllegalArgumentException.class,()->Asset.restore(ID,RSOT,AssetType.HARDWARE,ORG,SUB,LocalDate.of(2026,1,1),new AssetValue(BigDecimal.ONE,"EUR"),null,PARTNER,AssetLifecycleStatus.ACQUIRED,AssetCustodian.organization(ORG),1,T,T,ACTOR,ACTOR,"x".repeat(1025)));
    }

    @Test void moneyAndCustodyEventSaturateCompoundValidation() {
        assertDoesNotThrow(()->new AssetValue(BigDecimal.ZERO,"USD"));
        assertThrows(IllegalArgumentException.class,()->new AssetValue(new BigDecimal("-1"),"USD"));
        assertThrows(IllegalArgumentException.class,()->new AssetValue(new BigDecimal("12345678901234567890"),"USD"));
        assertThrows(IllegalArgumentException.class,()->new AssetValue(new BigDecimal("1.00001"),"USD"));
        assertThrows(IllegalArgumentException.class,()->new AssetCustodian(AssetCustodianKind.NONE,ORG));
        assertThrows(IllegalArgumentException.class,()->new AssetCustodian(AssetCustodianKind.ACTOR,null));
        assertThrows(IllegalArgumentException.class,()->new AssetCustodyEvent(id(10),ID,1,AssetCustodyEventType.ACQUIRED,null,AssetLifecycleStatus.ACQUIRED,AssetCustodian.organization(ORG),T,ACTOR,id(11),"reason\n",null));
        assertThrows(IllegalArgumentException.class,()->new AssetCustodyEvent(id(10),ID,1,AssetCustodyEventType.ACQUIRED,null,AssetLifecycleStatus.ACQUIRED,AssetCustodian.organization(ORG),T,ACTOR,id(11),"reason","proof\n"));
        assertThrows(IllegalArgumentException.class,()->new AssetCustodyEvent(id(10),ID,1,AssetCustodyEventType.DISPOSED,AssetLifecycleStatus.RETIRED,AssetLifecycleStatus.DISPOSED,AssetCustodian.none(),T,ACTOR,id(11),"reason",null));
        assertDoesNotThrow(()->new AssetCustodyEvent(id(10),ID,1,AssetCustodyEventType.DISPOSED,AssetLifecycleStatus.RETIRED,AssetLifecycleStatus.DISPOSED,AssetCustodian.none(),T,ACTOR,id(11),"reason","proof"));
        assertThrows(NullPointerException.class,()->new AssetCustodyEvent(id(10),ID,1,AssetCustodyEventType.ACQUIRED,null,AssetLifecycleStatus.ACQUIRED,AssetCustodian.organization(ORG),T,ACTOR,id(11),null,null));
        assertThrows(IllegalArgumentException.class,()->new AssetCustodyEvent(id(10),ID,1,AssetCustodyEventType.ACQUIRED,null,AssetLifecycleStatus.ACQUIRED,AssetCustodian.organization(ORG),T,ACTOR,id(11),"x",null));
        assertThrows(IllegalArgumentException.class,()->new AssetCustodyEvent(id(10),ID,1,AssetCustodyEventType.ACQUIRED,null,AssetLifecycleStatus.ACQUIRED,AssetCustodian.organization(ORG),T,ACTOR,id(11),"x".repeat(1025),null));
        assertNull(new AssetCustodyEvent(id(10),ID,1,AssetCustodyEventType.ACQUIRED,null,AssetLifecycleStatus.ACQUIRED,AssetCustodian.organization(ORG),T,ACTOR,id(11),"reason","   ").evidenceReference());
        assertThrows(IllegalArgumentException.class,()->new AssetCustodyEvent(id(10),ID,1,AssetCustodyEventType.ACQUIRED,null,AssetLifecycleStatus.ACQUIRED,AssetCustodian.organization(ORG),T,ACTOR,id(11),"reason","x".repeat(241)));
    }

    @Test void commandContextRejectsControlCharactersLengthBoundsAndNormalizesBlankEvidence() {
        DomainIdentifier corr=id(12);
        assertThrows(IllegalArgumentException.class,()->new AssetCommandContext(ACTOR,corr,"valid-key\n","reason",null));
        assertThrows(IllegalArgumentException.class,()->new AssetCommandContext(ACTOR,corr,"x".repeat(201),"reason",null));
        assertThrows(IllegalArgumentException.class,()->new AssetCommandContext(ACTOR,corr,"valid-key","reason\n",null));
        assertThrows(IllegalArgumentException.class,()->new AssetCommandContext(ACTOR,corr,"valid-key","x".repeat(1025),null));
        assertThrows(IllegalArgumentException.class,()->new AssetCommandContext(ACTOR,corr,"valid-key","reason","proof\n"));
        assertNull(new AssetCommandContext(ACTOR,corr,"valid-key","reason","   ").evidenceReference());
        assertThrows(IllegalArgumentException.class,()->new AssetCommandContext(ACTOR,corr,"valid-key","reason","x".repeat(241)));
    }

    private static Asset asset(AssetLifecycleStatus status,AssetCustodian custodian){return Asset.restore(ID,RSOT,AssetType.HARDWARE,ORG,SUB,LocalDate.of(2026,1,1),new AssetValue(BigDecimal.ONE,"EUR"),null,PARTNER,status,custodian,1,T,T,ACTOR,ACTOR,"reason");}
    private static DomainIdentifier id(long n){return DomainIdentifier.parse("01900000-0000-7000-8000-"+String.format("%012d",n));}
}
