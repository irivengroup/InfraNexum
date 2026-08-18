package io.infranexum.server.integrations;

import io.infranexum.core.audit.AuditEntry;
import io.infranexum.core.audit.AuditJournal;
import io.infranexum.core.audit.AuditScope;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorSyncCheckpoint;
import io.infranexum.integrations.ConnectorSyncEngine;
import io.infranexum.integrations.ConnectorSyncExecutionRequest;
import io.infranexum.integrations.ConnectorSyncRepository;
import io.infranexum.integrations.ConnectorSyncRun;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;

/** Audited operator service for durable connector sync execution, resume and compensation. */
final class ConnectorSyncOperationsService {
    private final ConnectorSyncEngine engine;
    private final ConnectorSyncRepository repository;
    private final AuditJournal audit;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final MeterRegistry meters;

    ConnectorSyncOperationsService(
            ConnectorSyncEngine engine, ConnectorSyncRepository repository, AuditJournal audit,
            UuidV7Generator ids, @Qualifier("platformClock") Clock clock, MeterRegistry meters) {
        this.engine=Objects.requireNonNull(engine,"engine"); this.repository=Objects.requireNonNull(repository,"repository");
        this.audit=Objects.requireNonNull(audit,"audit"); this.ids=Objects.requireNonNull(ids,"ids");
        this.clock=Objects.requireNonNull(clock,"clock"); this.meters=Objects.requireNonNull(meters,"meters");
    }

    List<ConnectorSyncRun> runs(String connectorKey,int offset,int limit){return repository.listRuns(connectorKey==null||connectorKey.isBlank()?null:new ConnectorKey(connectorKey),offset,limit);}
    List<ConnectorSyncCheckpoint> checkpoints(String connectorKey,int offset,int limit){return repository.listCheckpoints(new ConnectorKey(connectorKey),offset,limit);}

    ConnectorSyncRun execute(String connectorKey,ConnectorSyncExecutionRequest request,String idempotencyKey,String reason,DomainIdentifier actor,DomainIdentifier correlation){
        return mutate("execute",reason,actor,correlation,()->engine.execute(new ConnectorKey(connectorKey),request,idempotencyKey,actor,correlation));
    }
    ConnectorSyncRun resume(DomainIdentifier runId,String reason,DomainIdentifier actor,DomainIdentifier correlation){return mutate("resume",reason,actor,correlation,()->engine.resume(runId));}
    ConnectorSyncRun compensate(DomainIdentifier runId,String reason,DomainIdentifier actor,DomainIdentifier correlation){return mutate("compensate",reason,actor,correlation,()->engine.compensate(runId));}

    private ConnectorSyncRun mutate(String operation,String reason,DomainIdentifier actor,DomainIdentifier correlation,Action action){
        Objects.requireNonNull(actor,"actor");Objects.requireNonNull(correlation,"correlation");String stableReason=reason(reason);
        try{
            ConnectorSyncRun run=action.run();
            audit(run,operation,"SUCCESS",stableReason,actor,correlation,null);
            meters.counter("infranexum.integrations.sync.operations","operation",operation,"outcome","success").increment();
            return run;
        }catch(RuntimeException failure){
            meters.counter("infranexum.integrations.sync.operations","operation",operation,"outcome","failure").increment();
            audit(null,operation,"FAILURE",stableReason,actor,correlation,failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private void audit(ConnectorSyncRun run,String operation,String outcome,String reason,DomainIdentifier actor,DomainIdentifier correlation,String failureType){
        Map<String,String> metadata=run==null?Map.of("operation",operation,"failure_type",failureType==null?"unknown":failureType):Map.of(
                "operation",operation,"provider",run.provider(),"direction",run.direction().name(),"rollback",run.rollbackStrategy().name(),"status",run.status().name(),"revision",Long.toString(run.lastCheckpointRevision()));
        audit.append(new AuditEntry(ids.next(),AuditScope.platform(),actor.toString(),"USER","integrations.connector-sync."+operation,
                "integration_sync",run==null?"unknown":run.runId().toString(),outcome,clock.instant(),correlation,outcome,"HTTP",null,null,reason,metadata,"CRITICAL"));
    }
    private static String reason(String value){if(value==null)throw new IllegalArgumentException("reason is required");String v=value.strip();if(v.length()<3||v.length()>500)throw new IllegalArgumentException("reason must contain 3..500 characters");return v;}
    @FunctionalInterface private interface Action{ConnectorSyncRun run();}
}
