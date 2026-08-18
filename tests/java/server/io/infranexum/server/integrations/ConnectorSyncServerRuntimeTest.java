package io.infranexum.server.integrations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.infranexum.core.audit.AuditJournal;
import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.integrations.*;
import io.infranexum.server.identity.LocalAuthenticationFilter;
import io.infranexum.server.observability.CorrelationContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Hosted-JDK coverage for connector sync composition, operator service and HTTP boundary. */
final class ConnectorSyncServerRuntimeTest {
    private static final Instant NOW=Instant.parse("2026-08-18T08:00:00Z");
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private static final DomainIdentifier RUN=id("0198b180-0000-7001-8000-000000000001");
    private static final DomainIdentifier CHECKPOINT=id("0198b180-0000-7002-8000-000000000002");
    private static final DomainIdentifier ACTOR=id("0198b180-0000-7003-8000-000000000003");
    private static final DomainIdentifier CORRELATION=id("0198b180-0000-7004-8000-000000000004");
    private static final ConnectorKey KEY=new ConnectorKey("future-sync");

    @Test void handlerRegistryIsImmutableSortedAndFailClosed(){
        ConnectorSyncHandler b=handler("z-sync"), a=handler("a-sync");
        ImmutableConnectorSyncHandlerRegistry registry=new ImmutableConnectorSyncHandlerRegistry(List.of(b,a));
        assertSame(a,registry.require(new ConnectorKey("a-sync")));
        assertEquals(List.of(new ConnectorKey("a-sync"),new ConnectorKey("z-sync")),registry.keys());
        assertThrows(ConnectorSyncHandlerUnavailableException.class,()->registry.require(KEY));
        assertThrows(NullPointerException.class,()->registry.require(null));
        assertThrows(ConfigurationException.class,()->new ImmutableConnectorSyncHandlerRegistry(List.of(a,a)));
        assertThrows(NullPointerException.class,()->new ImmutableConnectorSyncHandlerRegistry(java.util.Arrays.asList(a,null)));
        assertTrue(new ImmutableConnectorSyncHandlerRegistry(null).keys().isEmpty());
    }

    @Test void operationsAuditMeterAndValidateReasons(){
        ConnectorSyncEngine engine=mock(ConnectorSyncEngine.class); ConnectorSyncRepository repo=mock(ConnectorSyncRepository.class); AuditJournal audit=mock(AuditJournal.class);
        when(engine.execute(eq(KEY),any(),eq("idem-1"),eq(ACTOR),eq(CORRELATION))).thenReturn(run(ConnectorSyncRunStatus.SUCCEEDED));
        when(engine.resume(RUN)).thenReturn(run(ConnectorSyncRunStatus.SUCCEEDED)); when(engine.compensate(RUN)).thenReturn(run(ConnectorSyncRunStatus.COMPENSATED));
        when(repo.listRuns(isNull(),eq(0),eq(10))).thenReturn(List.of(run(ConnectorSyncRunStatus.SUCCEEDED)));
        when(repo.listRuns(eq(KEY),eq(0),eq(10))).thenReturn(List.of(run(ConnectorSyncRunStatus.SUCCEEDED)));
        when(repo.listCheckpoints(eq(KEY),eq(0),eq(10))).thenReturn(List.of(checkpoint()));
        try(SimpleMeterRegistry meters=new SimpleMeterRegistry()){
            ConnectorSyncOperationsService service=new ConnectorSyncOperationsService(engine,repo,audit,new UuidV7Generator(CLOCK,new SecureRandom()),CLOCK,meters);
            assertEquals(1,service.runs(" ",0,10).size()); assertEquals(1,service.runs(KEY.value(),0,10).size()); assertEquals(1,service.checkpoints(KEY.value(),0,10).size());
            assertEquals(ConnectorSyncRunStatus.SUCCEEDED,service.execute(KEY.value(),new ConnectorSyncExecutionRequest(ConnectorSyncDirection.INBOUND,Set.of("name"),false,1),"idem-1"," approved sync ",ACTOR,CORRELATION).status());
            assertEquals(ConnectorSyncRunStatus.SUCCEEDED,service.resume(RUN,"resume after outage",ACTOR,CORRELATION).status());
            assertEquals(ConnectorSyncRunStatus.COMPENSATED,service.compensate(RUN,"operator rollback",ACTOR,CORRELATION).status());
            verify(audit,times(3)).append(any());
            assertEquals(3.0,meters.find("infranexum.integrations.sync.operations").counters().stream().mapToDouble(c->c.count()).sum());
            assertThrows(IllegalArgumentException.class,()->service.resume(RUN," ",ACTOR,CORRELATION));
            assertThrows(IllegalArgumentException.class,()->service.resume(RUN,"x".repeat(501),ACTOR,CORRELATION));
            assertThrows(NullPointerException.class,()->service.resume(RUN,"valid reason",null,CORRELATION));
        }
    }

    @Test void operationFailuresAreAuditedWithoutLeakingAsSuccess(){
        ConnectorSyncEngine engine=mock(ConnectorSyncEngine.class); ConnectorSyncRepository repo=mock(ConnectorSyncRepository.class); AuditJournal audit=mock(AuditJournal.class);
        when(engine.resume(RUN)).thenThrow(new ConnectorSyncStateConflictException("stale"));
        try(SimpleMeterRegistry meters=new SimpleMeterRegistry()){
            ConnectorSyncOperationsService service=new ConnectorSyncOperationsService(engine,repo,audit,new UuidV7Generator(CLOCK,new SecureRandom()),CLOCK,meters);
            assertThrows(ConnectorSyncStateConflictException.class,()->service.resume(RUN,"retry safely",ACTOR,CORRELATION));
            verify(audit).append(any()); assertEquals(1.0,meters.find("infranexum.integrations.sync.operations").counter().count());
        }
    }

    @Test void controllerPaginatesRedactsCursorAndExecutesMutations(){
        ConnectorSyncOperationsService operations=mock(ConnectorSyncOperationsService.class); ConnectorSyncController controller=new ConnectorSyncController(operations);
        when(operations.runs(null,0,3)).thenReturn(List.of(run(ConnectorSyncRunStatus.SUCCEEDED),run(ConnectorSyncRunStatus.PAUSED),run(ConnectorSyncRunStatus.FAILED)));
        var page=controller.runs(null,0,2); assertEquals(2,page.getBody().size()); assertEquals("2",page.getHeaders().getFirst("X-Next-Offset")); assertEquals("no-store",page.getHeaders().getFirst("Cache-Control"));
        when(operations.checkpoints(KEY.value(),0,3)).thenReturn(List.of(checkpoint(),checkpoint(),checkpoint()));
        var checkpoints=controller.checkpoints(KEY.value(),0,2); assertEquals(2,checkpoints.getBody().size()); assertEquals("a".repeat(64),checkpoints.getBody().getFirst().cursorSha256());
        MockHttpServletRequest request=request(); when(operations.execute(eq(KEY.value()),any(),eq("idem-1"),eq("approved"),eq(ACTOR),eq(CORRELATION))).thenReturn(run(ConnectorSyncRunStatus.RUNNING));
        var execute=controller.execute(KEY.value(),"idem-1",new ConnectorSyncController.ExecuteRequest(" inbound ",Set.of("name"),false,2,"approved"),request); assertEquals("RUNNING",execute.getBody().status());
        when(operations.resume(eq(RUN),eq("resume"),eq(ACTOR),eq(CORRELATION))).thenReturn(run(ConnectorSyncRunStatus.RUNNING));
        assertEquals("RUNNING",controller.resume(RUN.toString(),"idem-r",new ConnectorSyncController.ReasonRequest("resume"),request).getBody().status());
        when(operations.compensate(eq(RUN),eq("rollback"),eq(ACTOR),eq(CORRELATION))).thenReturn(run(ConnectorSyncRunStatus.COMPENSATED));
        assertEquals("COMPENSATED",controller.compensate(RUN.toString(),"idem-c",new ConnectorSyncController.ReasonRequest("rollback"),request).getBody().status());
        assertThrows(IllegalArgumentException.class,()->controller.execute(KEY.value(),"x",null,request));
        assertThrows(IllegalArgumentException.class,()->controller.execute(KEY.value(),"x",new ConnectorSyncController.ExecuteRequest("bogus",Set.of(),false,1,"ok reason"),request));
        assertThrows(IllegalArgumentException.class,()->controller.runs(null,0,201));
        assertThrows(IllegalArgumentException.class,()->controller.checkpoints(KEY.value(),0,0));
        MockHttpServletRequest missingActor=new MockHttpServletRequest(); CorrelationContext.bind(missingActor,CORRELATION);
        assertThrows(IllegalStateException.class,()->controller.execute(KEY.value(),"x",new ConnectorSyncController.ExecuteRequest("INBOUND",Set.of(),false,1,"reason"),missingActor));
        MockHttpServletRequest missingCorrelation=new MockHttpServletRequest(); missingCorrelation.setAttribute(LocalAuthenticationFilter.ACCOUNT_ATTRIBUTE,ACTOR);
        assertThrows(IllegalStateException.class,()->controller.execute(KEY.value(),"x",new ConnectorSyncController.ExecuteRequest("INBOUND",Set.of(),false,1,"reason"),missingCorrelation));
    }

    private static MockHttpServletRequest request(){MockHttpServletRequest r=new MockHttpServletRequest();r.setAttribute(LocalAuthenticationFilter.ACCOUNT_ATTRIBUTE,ACTOR);CorrelationContext.bind(r,CORRELATION);return r;}
    private static ConnectorSyncHandler handler(String key){return new ConnectorSyncHandler(){public ConnectorKey connectorKey(){return new ConnectorKey(key);}public ConnectorSyncBatchResult synchronize(ConnectorSyncBatchContext c){return ConnectorSyncBatchResult.applied(null,0,0,0,true);}public ConnectorSyncCompensationResult compensate(ConnectorSyncCompensationContext c){return ConnectorSyncCompensationResult.succeeded();}};}
    private static ConnectorSyncRun run(ConnectorSyncRunStatus status){return new ConnectorSyncRun(RUN,KEY,"future-provider",ConnectorSyncDirection.INBOUND,ConnectorRollbackStrategy.LOCAL_CHECKPOINT,status,"idem","a".repeat(64),Set.of("name"),false,2,0,status==ConnectorSyncRunStatus.COMPENSATED?2:1,status==ConnectorSyncRunStatus.PAUSED?"WAIT":null,ACTOR,CORRELATION,NOW.minusSeconds(30),NOW,(status==ConnectorSyncRunStatus.SUCCEEDED||status==ConnectorSyncRunStatus.FAILED||status==ConnectorSyncRunStatus.COMPENSATED||status==ConnectorSyncRunStatus.COMPENSATION_FAILED)?NOW:null,status==ConnectorSyncRunStatus.COMPENSATED?2L:null);}
    private static ConnectorSyncCheckpoint checkpoint(){return new ConnectorSyncCheckpoint(CHECKPOINT,KEY,RUN,1,ConnectorSyncCheckpointKind.PROGRESS,"secret-cursor","a".repeat(64),10,9,1,NOW);}
    private static DomainIdentifier id(String v){return new DomainIdentifier(UUID.fromString(v));}
}
