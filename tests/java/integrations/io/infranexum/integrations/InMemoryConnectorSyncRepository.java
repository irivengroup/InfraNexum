package io.infranexum.integrations;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Deterministic in-memory repository used only by domain tests and dependency-free smokes. */
final class InMemoryConnectorSyncRepository implements ConnectorSyncRepository {
    private final Map<DomainIdentifier,ConnectorSyncRun> runs=new LinkedHashMap<>();
    private final Map<ConnectorKey,State> states=new LinkedHashMap<>();
    private final List<ConnectorSyncCheckpoint> checkpoints=new ArrayList<>();

    @Override public synchronized BeginResult begin(DomainIdentifier runId,ConnectorKey key,String provider,ConnectorSyncDirection direction,ConnectorRollbackStrategy rollback,String idem,String hash,Set<String> fields,boolean deletions,int maxBatches,DomainIdentifier actor,DomainIdentifier correlation,Instant at){
        State state=states.computeIfAbsent(key,k->new State());
        for(ConnectorSyncRun existing:runs.values())if(existing.connectorKey().equals(key)&&existing.idempotencyKey().equals(idem)){
            if(!existing.requestSha256().equals(hash))throw new ConnectorSyncStateConflictException("idempotency conflict");return new BeginResult(existing,state.cursor,state.revision,false);
        }
        if(state.active!=null)throw new ConnectorSyncStateConflictException("active run");
        ConnectorSyncRun run=new ConnectorSyncRun(runId,key,provider,direction,rollback,ConnectorSyncRunStatus.RUNNING,idem,hash,fields,deletions,maxBatches,state.revision,state.revision,null,actor,correlation,at,at,null,null);
        runs.put(runId,run);state.active=runId;return new BeginResult(run,state.cursor,state.revision,true);
    }
    @Override public synchronized Activation activate(DomainIdentifier runId,Instant at){ConnectorSyncRun run=require(runId);if(run.status()!=ConnectorSyncRunStatus.PAUSED)throw new ConnectorSyncStateConflictException("not paused");State s=state(run.connectorKey());if(s.active!=null||s.revision!=run.lastCheckpointRevision())throw new ConnectorSyncStateConflictException("cannot activate");s.active=runId;run=copy(run,ConnectorSyncRunStatus.RUNNING,null,at,null,null,run.lastCheckpointRevision());runs.put(runId,run);return new Activation(run,s.cursor,s.revision);}
    @Override public synchronized ConnectorSyncCheckpoint appendCheckpoint(DomainIdentifier checkpointId,DomainIdentifier runId,long expected,ConnectorSyncCheckpointKind kind,String cursor,String hash,long processed,long changed,long rejected,Instant at){ConnectorSyncRun run=require(runId);State s=state(run.connectorKey());if(run.status()!=ConnectorSyncRunStatus.RUNNING||!runId.equals(s.active)||s.revision!=expected)throw new ConnectorSyncStateConflictException("stale checkpoint");long revision=expected+1;ConnectorSyncCheckpoint cp=new ConnectorSyncCheckpoint(checkpointId,run.connectorKey(),runId,revision,kind,cursor,hash,processed,changed,rejected,at);checkpoints.add(cp);s.revision=revision;s.cursor=cursor;run=copy(run,run.status(),run.failureCode(),at,null,null,revision);runs.put(runId,run);return cp;}
    @Override public synchronized ConnectorSyncRun pause(DomainIdentifier id,String code,Instant at){return finish(id,ConnectorSyncRunStatus.PAUSED,code,false,at);}
    @Override public synchronized ConnectorSyncRun succeed(DomainIdentifier id,Instant at){return finish(id,ConnectorSyncRunStatus.SUCCEEDED,null,true,at);}
    @Override public synchronized ConnectorSyncRun fail(DomainIdentifier id,String code,Instant at){return finish(id,ConnectorSyncRunStatus.FAILED,code,true,at);}
    @Override public synchronized CompensationStart beginCompensation(DomainIdentifier id,Instant at){ConnectorSyncRun run=require(id);State s=state(run.connectorKey());if(run.status()==ConnectorSyncRunStatus.COMPENSATED||run.status()==ConnectorSyncRunStatus.COMPENSATING||run.status()==ConnectorSyncRunStatus.COMPENSATION_FAILED)throw new ConnectorSyncStateConflictException("invalid compensation state");if(s.active!=null&&!s.active.equals(id))throw new ConnectorSyncStateConflictException("other active");if(s.revision!=run.lastCheckpointRevision())throw new ConnectorSyncStateConflictException("newer revision");String initial=run.initialRevision()==0?null:checkpoint(run.connectorKey(),run.initialRevision()).cursor();s.active=id;run=copy(run,ConnectorSyncRunStatus.COMPENSATING,run.failureCode(),at,null,null,run.lastCheckpointRevision());runs.put(id,run);return new CompensationStart(run,initial,s.cursor,s.revision);}
    @Override public synchronized ConnectorSyncRun finishCompensation(DomainIdentifier id,long expected,DomainIdentifier checkpointId,String restored,String hash,Instant at){ConnectorSyncRun run=require(id);State s=state(run.connectorKey());if(run.status()!=ConnectorSyncRunStatus.COMPENSATING||!id.equals(s.active)||s.revision!=expected)throw new ConnectorSyncStateConflictException("compensation fence");long revision=expected+1;checkpoints.add(new ConnectorSyncCheckpoint(checkpointId,run.connectorKey(),id,revision,ConnectorSyncCheckpointKind.COMPENSATION,restored,hash,0,0,0,at));s.revision=revision;s.cursor=restored;s.active=null;run=copy(run,ConnectorSyncRunStatus.COMPENSATED,null,at,at,revision,revision);runs.put(id,run);return run;}
    @Override public synchronized ConnectorSyncRun compensationFailed(DomainIdentifier id,String code,Instant at){ConnectorSyncRun run=require(id);State s=state(run.connectorKey());if(run.status()!=ConnectorSyncRunStatus.COMPENSATING||!id.equals(s.active))throw new ConnectorSyncStateConflictException("not compensating");s.active=null;run=copy(run,ConnectorSyncRunStatus.COMPENSATION_FAILED,code,at,at,null,run.lastCheckpointRevision());runs.put(id,run);return run;}
    @Override public synchronized Optional<ConnectorSyncRun> findRun(DomainIdentifier id){return Optional.ofNullable(runs.get(id));}
    @Override public synchronized List<ConnectorSyncRun> listRuns(ConnectorKey key,int offset,int limit){return runs.values().stream().filter(r->key==null||r.connectorKey().equals(key)).sorted(Comparator.comparing(ConnectorSyncRun::startedAt).reversed()).skip(offset).limit(limit).toList();}
    @Override public synchronized List<ConnectorSyncCheckpoint> listCheckpoints(ConnectorKey key,int offset,int limit){return checkpoints.stream().filter(c->c.connectorKey().equals(key)).sorted(Comparator.comparingLong(ConnectorSyncCheckpoint::revision).reversed()).skip(offset).limit(limit).toList();}
    synchronized void advanceExternally(ConnectorKey key){State s=state(key);s.revision++;}
    synchronized String cursor(ConnectorKey key){return state(key).cursor;}
    private ConnectorSyncRun finish(DomainIdentifier id,ConnectorSyncRunStatus status,String code,boolean completed,Instant at){ConnectorSyncRun run=require(id);State s=state(run.connectorKey());if(run.status()!=ConnectorSyncRunStatus.RUNNING||!id.equals(s.active))throw new ConnectorSyncStateConflictException("not active");s.active=null;run=copy(run,status,code,at,completed?at:null,null,run.lastCheckpointRevision());runs.put(id,run);return run;}
    private ConnectorSyncRun require(DomainIdentifier id){ConnectorSyncRun run=runs.get(id);if(run==null)throw new ConnectorSyncNotFoundException("missing run");return run;}
    private State state(ConnectorKey key){State s=states.get(key);if(s==null)throw new ConnectorSyncNotFoundException("missing state");return s;}
    private ConnectorSyncCheckpoint checkpoint(ConnectorKey key,long revision){return checkpoints.stream().filter(c->c.connectorKey().equals(key)&&c.revision()==revision).findFirst().orElseThrow(()->new ConnectorSyncNotFoundException("missing checkpoint"));}
    private static ConnectorSyncRun copy(ConnectorSyncRun r,ConnectorSyncRunStatus status,String code,Instant updated,Instant completed,Long compensation,long revision){return new ConnectorSyncRun(r.runId(),r.connectorKey(),r.provider(),r.direction(),r.rollbackStrategy(),status,r.idempotencyKey(),r.requestSha256(),r.fields(),r.propagateDeletions(),r.maxBatches(),r.initialRevision(),revision,code,r.actorId(),r.correlationId(),r.startedAt(),updated,completed,compensation);}
    private static final class State{long revision;String cursor;DomainIdentifier active;}
}
