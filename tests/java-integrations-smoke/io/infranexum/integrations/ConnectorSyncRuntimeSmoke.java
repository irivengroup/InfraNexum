package io.infranexum.integrations;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Dependency-free proof of checkpoint, resume and compensation semantics. */
public final class ConnectorSyncRuntimeSmoke {
    private ConnectorSyncRuntimeSmoke() {}
    public static void main(String[] args){
        ConnectorKey key=new ConnectorKey("sync-lab");
        ConnectorGovernancePolicy policy=new ConnectorGovernancePolicy(key,"lab",ConnectorSyncDirection.INBOUND,ConnectorDataAuthority.EXTERNAL,ConnectorConflictStrategy.REJECT,ConnectorDeletionPolicy.IGNORE,ConnectorRollbackStrategy.LOCAL_CHECKPOINT,List.of(new ConnectorFieldAuthority("name",ConnectorDataAuthority.EXTERNAL)));
        ConnectorGovernanceRegistry governance=new ConnectorGovernanceRegistry(){public List<ConnectorGovernancePolicy> policies(){return List.of(policy);}public ConnectorGovernancePolicy require(ConnectorKey requested){if(!key.equals(requested))throw new ConnectorGovernanceNotFoundException("missing");return policy;}};
        class Handler implements ConnectorSyncHandler {int calls;public ConnectorKey connectorKey(){return key;}public ConnectorSyncBatchResult synchronize(ConnectorSyncBatchContext c){calls++;return ConnectorSyncBatchResult.applied("cursor-"+calls,1,1,0,calls>=2);}public ConnectorSyncCompensationResult compensate(ConnectorSyncCompensationContext c){return ConnectorSyncCompensationResult.succeeded();}}
        Handler handler=new Handler();ConnectorSyncHandlerRegistry registry=requested->{if(!key.equals(requested))throw new ConnectorSyncHandlerUnavailableException(requested);return handler;};
        InMemoryConnectorSyncRepository repository=new InMemoryConnectorSyncRepository();Clock clock=Clock.fixed(Instant.parse("2026-08-18T08:00:00Z"),ZoneOffset.UTC);ConnectorSyncEngine engine=new ConnectorSyncEngine(governance,new ConnectorGovernancePlanner(),registry,repository,new UuidV7Generator(clock,new SecureRandom(new byte[]{1,2,3,4})),clock);
        DomainIdentifier actor=new DomainIdentifier(UUID.fromString("0198b180-0000-7001-8000-000000000001"));DomainIdentifier corr=new DomainIdentifier(UUID.fromString("0198b180-0000-7002-8000-000000000002"));
        ConnectorSyncRun run=engine.execute(key,new ConnectorSyncExecutionRequest(ConnectorSyncDirection.INBOUND,Set.of("name"),false,10),"sync-smoke-0001",actor,corr);
        assert run.status()==ConnectorSyncRunStatus.SUCCEEDED;assert run.lastCheckpointRevision()==2;assert "cursor-2".equals(repository.cursor(key));
        ConnectorSyncRun compensated=engine.compensate(run.runId());assert compensated.status()==ConnectorSyncRunStatus.COMPENSATED;assert compensated.compensationCheckpointRevision()==3;assert repository.cursor(key)==null;
        boolean denied=false;try{ConnectorGovernancePolicy read=ConnectorGovernancePolicy.externalFederatedRead(new ConnectorKey("read-only"),"jira-assets");new ConnectorSyncEngine(new ConnectorGovernanceRegistry(){public List<ConnectorGovernancePolicy> policies(){return List.of(read);}public ConnectorGovernancePolicy require(ConnectorKey k){return read;}},new ConnectorGovernancePlanner(),registry,repository,new UuidV7Generator(clock,new SecureRandom()),clock).execute(read.connectorKey(),new ConnectorSyncExecutionRequest(ConnectorSyncDirection.FEDERATED_READ,Set.of(),false,1),"sync-smoke-0002",actor,corr);}catch(ConnectorSyncPolicyDeniedException expected){denied=true;}assert denied;
        System.out.println("connector-sync-runtime-smoke: PASS checkpoints=2 compensation=append-only federated-read=denied");
    }
}
