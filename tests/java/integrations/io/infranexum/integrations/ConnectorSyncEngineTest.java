package io.infranexum.integrations;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Hosted-JDK coverage for durable checkpoint, resume and compensation semantics. */
final class ConnectorSyncEngineTest {
    private static final Instant NOW=Instant.parse("2026-08-18T08:00:00Z");
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private static final ConnectorKey KEY=new ConnectorKey("sync-test");
    private static final DomainIdentifier ACTOR=new DomainIdentifier(UUID.fromString("0198b180-0000-7001-8000-000000000001"));
    private static final DomainIdentifier CORR=new DomainIdentifier(UUID.fromString("0198b180-0000-7002-8000-000000000002"));

    @Test void validatesSyncValueObjectsFailClosed(){
        assertThrows(IllegalArgumentException.class,()->new ConnectorSyncExecutionRequest(ConnectorSyncDirection.INBOUND,Set.of(),false,0));
        assertThrows(IllegalArgumentException.class,()->new ConnectorSyncBatchContext(ACTOR,KEY,ConnectorSyncDirection.INBOUND,null,-1,1));
        assertThrows(IllegalArgumentException.class,()->new ConnectorSyncBatchResult(ConnectorSyncBatchResult.Outcome.FAILED,null,0,0,0,true,false,false,"ERR"));
        assertThrows(IllegalArgumentException.class,()->new ConnectorSyncBatchResult(ConnectorSyncBatchResult.Outcome.APPLIED,null,0,0,0,false,true,false,null));
        assertThrows(IllegalArgumentException.class,()->ConnectorSyncBatchResult.failed("bad code",false,false));
        assertThrows(IllegalArgumentException.class,()->new ConnectorSyncCompensationResult(true,"ERR"));
        assertThrows(IllegalArgumentException.class,()->new ConnectorSyncCompensationResult(false,null));
        assertThrows(IllegalArgumentException.class,()->new ConnectorSyncCheckpoint(ACTOR,KEY,CORR,0,ConnectorSyncCheckpointKind.PROGRESS,null,"a".repeat(64),0,0,0,NOW));
        assertThrows(IllegalArgumentException.class,()->ConnectorSyncCheckpoint.normalizeCursor("x\n"));
        assertThrows(IllegalArgumentException.class,()->ConnectorSyncCheckpoint.normalizeCursor("x".repeat(2049)));
        assertNull(ConnectorSyncCheckpoint.normalizeCursor(null));
        assertEquals("abc",ConnectorSyncCheckpoint.normalizeCursor("abc"));
        ConnectorSyncRun sample=run(ConnectorSyncRunStatus.SUCCEEDED,0,0,null,null);assertTrue(sample.terminal());
        assertFalse(run(ConnectorSyncRunStatus.PAUSED,0,0,"WAIT",null).terminal());
    }

    @Test void executesMultipleBatchesAndDeduplicatesRunAdmission(){
        InMemoryConnectorSyncRepository repo=new InMemoryConnectorSyncRepository();AtomicInteger calls=new AtomicInteger();
        ConnectorSyncHandler handler=handler(ctx->{int n=calls.incrementAndGet();return ConnectorSyncBatchResult.applied("cursor-"+n,2,1,1,n==2);},c->ConnectorSyncCompensationResult.succeeded());
        ConnectorSyncEngine engine=engine(policy(ConnectorRollbackStrategy.LOCAL_CHECKPOINT),handler,repo);
        ConnectorSyncExecutionRequest request=request(10);
        ConnectorSyncRun result=engine.execute(KEY,request,"sync-exec-0001",ACTOR,CORR);
        assertEquals(ConnectorSyncRunStatus.SUCCEEDED,result.status());assertEquals(2,result.lastCheckpointRevision());assertEquals("cursor-2",repo.cursor(KEY));
        assertEquals(2,repo.listCheckpoints(KEY,0,10).size());
        ConnectorSyncRun duplicate=engine.execute(KEY,request,"sync-exec-0001",ACTOR,CORR);assertEquals(result.runId(),duplicate.runId());assertEquals(2,calls.get());
        assertThrows(ConnectorSyncStateConflictException.class,()->engine.execute(KEY,new ConnectorSyncExecutionRequest(ConnectorSyncDirection.INBOUND,Set.of("name"),false,1),"sync-exec-0001",ACTOR,CORR));
    }

    @Test void pausesAtBatchBudgetAndResumesFromDurableCursor(){
        InMemoryConnectorSyncRepository repo=new InMemoryConnectorSyncRepository();AtomicInteger calls=new AtomicInteger();
        ConnectorSyncHandler handler=handler(ctx->{int n=calls.incrementAndGet();assertEquals(n-1,ctx.currentRevision());return ConnectorSyncBatchResult.applied("p"+n,1,1,0,n>=2);},c->ConnectorSyncCompensationResult.succeeded());
        ConnectorSyncEngine engine=engine(policy(ConnectorRollbackStrategy.LOCAL_CHECKPOINT),handler,repo);
        ConnectorSyncRun paused=engine.execute(KEY,request(1),"sync-pause-0001",ACTOR,CORR);assertEquals(ConnectorSyncRunStatus.PAUSED,paused.status());assertEquals("BATCH_BUDGET_EXHAUSTED",paused.failureCode());
        ConnectorSyncRun resumed=engine.resume(paused.runId());assertEquals(ConnectorSyncRunStatus.SUCCEEDED,resumed.status());assertEquals(2,resumed.lastCheckpointRevision());assertEquals("p2",repo.cursor(KEY));
        assertThrows(ConnectorSyncStateConflictException.class,()->engine.resume(resumed.runId()));
    }

    @Test void mapsRetryableAndPermanentFailuresWithoutFakeSuccess(){
        InMemoryConnectorSyncRepository retryRepo=new InMemoryConnectorSyncRepository();ConnectorSyncEngine retry=engine(policy(ConnectorRollbackStrategy.LOCAL_CHECKPOINT),handler(c->ConnectorSyncBatchResult.failed("REMOTE_503",true,false),c->ConnectorSyncCompensationResult.succeeded()),retryRepo);
        ConnectorSyncRun paused=retry.execute(KEY,request(2),"sync-retry-0001",ACTOR,CORR);assertEquals(ConnectorSyncRunStatus.PAUSED,paused.status());assertEquals("REMOTE_503",paused.failureCode());
        InMemoryConnectorSyncRepository hardRepo=new InMemoryConnectorSyncRepository();ConnectorSyncEngine hard=engine(policy(ConnectorRollbackStrategy.LOCAL_CHECKPOINT),handler(c->ConnectorSyncBatchResult.failed("BAD_MAPPING",false,false),c->ConnectorSyncCompensationResult.succeeded()),hardRepo);
        ConnectorSyncRun failed=hard.execute(KEY,request(2),"sync-hard-0001",ACTOR,CORR);assertEquals(ConnectorSyncRunStatus.FAILED,failed.status());assertEquals("BAD_MAPPING",failed.failureCode());
    }

    @Test void automaticallyCompensatesPartialMutationAndSurfacesCompensationFailure(){
        InMemoryConnectorSyncRepository okRepo=new InMemoryConnectorSyncRepository();ConnectorSyncEngine ok=engine(policy(ConnectorRollbackStrategy.DUAL_COMPENSATION),handler(c->ConnectorSyncBatchResult.failed("PARTIAL_WRITE",false,true),c->ConnectorSyncCompensationResult.succeeded()),okRepo);
        ConnectorSyncRun compensated=ok.execute(KEY,request(2),"sync-comp-0001",ACTOR,CORR);assertEquals(ConnectorSyncRunStatus.COMPENSATED,compensated.status());assertEquals(1,compensated.compensationCheckpointRevision());
        InMemoryConnectorSyncRepository badRepo=new InMemoryConnectorSyncRepository();ConnectorSyncEngine bad=engine(policy(ConnectorRollbackStrategy.REMOTE_COMPENSATION),handler(c->ConnectorSyncBatchResult.failed("PARTIAL_WRITE",false,true),c->ConnectorSyncCompensationResult.failed("ROLLBACK_503")),badRepo);
        ConnectorSyncRun failure=bad.execute(KEY,request(2),"sync-comp-0002",ACTOR,CORR);assertEquals(ConnectorSyncRunStatus.COMPENSATION_FAILED,failure.status());assertEquals("ROLLBACK_503",failure.failureCode());
        InMemoryConnectorSyncRepository manualRepo=new InMemoryConnectorSyncRepository();ConnectorSyncEngine manual=engine(policy(ConnectorRollbackStrategy.MANUAL),handler(c->ConnectorSyncBatchResult.failed("PARTIAL_WRITE",false,true),c->ConnectorSyncCompensationResult.succeeded()),manualRepo);
        assertEquals("MANUAL_COMPENSATION_REQUIRED",manual.execute(KEY,request(2),"sync-comp-0003",ACTOR,CORR).failureCode());
    }

    @Test void explicitCompensationIsAppendOnlyAndFencedAgainstNewerState(){
        InMemoryConnectorSyncRepository repo=new InMemoryConnectorSyncRepository();ConnectorSyncHandler handler=handler(c->ConnectorSyncBatchResult.applied("done",1,1,0,true),c->ConnectorSyncCompensationResult.succeeded());ConnectorSyncEngine engine=engine(policy(ConnectorRollbackStrategy.LOCAL_CHECKPOINT),handler,repo);
        ConnectorSyncRun run=engine.execute(KEY,request(2),"sync-explicit-1",ACTOR,CORR);ConnectorSyncRun compensated=engine.compensate(run.runId());assertEquals(ConnectorSyncRunStatus.COMPENSATED,compensated.status());assertEquals(2,compensated.lastCheckpointRevision());assertNull(repo.cursor(KEY));assertEquals(ConnectorSyncCheckpointKind.COMPENSATION,repo.listCheckpoints(KEY,0,10).getFirst().kind());
        assertThrows(ConnectorSyncStateConflictException.class,()->engine.compensate(run.runId()));
        InMemoryConnectorSyncRepository newer=new InMemoryConnectorSyncRepository();ConnectorSyncEngine engine2=engine(policy(ConnectorRollbackStrategy.LOCAL_CHECKPOINT),handler,newer);ConnectorSyncRun old=engine2.execute(KEY,request(2),"sync-explicit-2",ACTOR,CORR);newer.advanceExternally(KEY);assertThrows(ConnectorSyncStateConflictException.class,()->engine2.compensate(old.runId()));
    }

    @Test void governanceAndHandlerRegistrationStayFailClosed(){
        ConnectorGovernancePolicy read=ConnectorGovernancePolicy.externalFederatedRead(KEY,"jira-assets");InMemoryConnectorSyncRepository repo=new InMemoryConnectorSyncRepository();ConnectorSyncHandler handler=handler(c->ConnectorSyncBatchResult.applied(null,0,0,0,true),c->ConnectorSyncCompensationResult.succeeded());
        assertThrows(ConnectorSyncPolicyDeniedException.class,()->engine(read,handler,repo).execute(KEY,new ConnectorSyncExecutionRequest(ConnectorSyncDirection.FEDERATED_READ,Set.of(),false,1),"sync-deny-0001",ACTOR,CORR));
        ConnectorSyncEngine missing=engine(policy(ConnectorRollbackStrategy.LOCAL_CHECKPOINT),null,new InMemoryConnectorSyncRepository());assertThrows(ConnectorSyncHandlerUnavailableException.class,()->missing.execute(KEY,request(1),"sync-deny-0002",ACTOR,CORR));
        MutableRegistry mutable=new MutableRegistry(policy(ConnectorRollbackStrategy.LOCAL_CHECKPOINT));InMemoryConnectorSyncRepository pauseRepo=new InMemoryConnectorSyncRepository();ConnectorSyncEngine changing=engine(mutable,handler(c->ConnectorSyncBatchResult.applied("x",1,1,0,false),c->ConnectorSyncCompensationResult.succeeded()),pauseRepo);ConnectorSyncRun paused=changing.execute(KEY,request(1),"sync-deny-0003",ACTOR,CORR);mutable.policy=policy(ConnectorRollbackStrategy.DUAL_COMPENSATION);assertEquals("POLICY_CHANGED",changing.resume(paused.runId()).failureCode());
    }

    private static ConnectorSyncExecutionRequest request(int max){return new ConnectorSyncExecutionRequest(ConnectorSyncDirection.INBOUND,Set.of("name"),false,max);}
    private static ConnectorGovernancePolicy policy(ConnectorRollbackStrategy rollback){return new ConnectorGovernancePolicy(KEY,"lab",ConnectorSyncDirection.INBOUND,ConnectorDataAuthority.EXTERNAL,ConnectorConflictStrategy.REJECT,ConnectorDeletionPolicy.IGNORE,rollback,List.of(new ConnectorFieldAuthority("name",ConnectorDataAuthority.EXTERNAL)));}
    private static ConnectorSyncHandler handler(java.util.function.Function<ConnectorSyncBatchContext,ConnectorSyncBatchResult> sync,java.util.function.Function<ConnectorSyncCompensationContext,ConnectorSyncCompensationResult> compensate){return new ConnectorSyncHandler(){public ConnectorKey connectorKey(){return KEY;}public ConnectorSyncBatchResult synchronize(ConnectorSyncBatchContext c){return sync.apply(c);}public ConnectorSyncCompensationResult compensate(ConnectorSyncCompensationContext c){return compensate.apply(c);}};}
    private static ConnectorSyncEngine engine(ConnectorGovernancePolicy policy,ConnectorSyncHandler handler,InMemoryConnectorSyncRepository repo){return engine(new MutableRegistry(policy),handler,repo);}
    private static ConnectorSyncEngine engine(ConnectorGovernanceRegistry registry,ConnectorSyncHandler handler,InMemoryConnectorSyncRepository repo){ConnectorSyncHandlerRegistry handlers=key->{if(handler==null||!KEY.equals(key))throw new ConnectorSyncHandlerUnavailableException(key);return handler;};return new ConnectorSyncEngine(registry,new ConnectorGovernancePlanner(),handlers,repo,new UuidV7Generator(CLOCK,new SecureRandom(new byte[]{4,3,2,1})),CLOCK);}
    private static ConnectorSyncRun run(ConnectorSyncRunStatus status,long initial,long last,String failure,Long compensation){return new ConnectorSyncRun(ACTOR,KEY,"lab",ConnectorSyncDirection.INBOUND,ConnectorRollbackStrategy.LOCAL_CHECKPOINT,status,"sync-test-0001","a".repeat(64),Set.of("name"),false,1,initial,last,failure,ACTOR,CORR,NOW,NOW,status==ConnectorSyncRunStatus.PAUSED?null:NOW,compensation);}
    private static final class MutableRegistry implements ConnectorGovernanceRegistry{ConnectorGovernancePolicy policy;MutableRegistry(ConnectorGovernancePolicy p){policy=p;}public List<ConnectorGovernancePolicy> policies(){return List.of(policy);}public ConnectorGovernancePolicy require(ConnectorKey key){if(!KEY.equals(key))throw new ConnectorGovernanceNotFoundException("missing");return policy;}}
}
