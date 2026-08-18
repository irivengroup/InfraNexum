package io.infranexum.server.integrations;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.PaginationConstraints;
import io.infranexum.integrations.ConnectorSyncCheckpoint;
import io.infranexum.integrations.ConnectorSyncDirection;
import io.infranexum.integrations.ConnectorSyncExecutionRequest;
import io.infranexum.integrations.ConnectorSyncRun;
import io.infranexum.server.http.ApiPagination;
import io.infranexum.server.http.AuthenticatedActorContext;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator boundary for durable connector synchronization checkpoints and governed compensation. */
@RestController
@ConditionalOnProperty(name="infranexum.integrations.enabled",havingValue="true")
@ConditionalOnExpression("\'${infranexum.persistence.mode:MEMORY}\' == \'POSTGRESQL\' || \'${infranexum.persistence.mode:MEMORY}\' == \'ORACLE\'")
final class ConnectorSyncController {
    private final ConnectorSyncOperationsService operations;
    ConnectorSyncController(ConnectorSyncOperationsService operations){this.operations=Objects.requireNonNull(operations,"operations");}

    @GetMapping("/api/v1/integrations/sync/runs")
    ResponseEntity<List<RunResponse>> runs(@RequestParam(required=false)String connectorKey,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="50")int limit){
        int bounded=PaginationConstraints.requireOffset(offset);boundedLimit(limit);List<RunResponse> page=operations.runs(connectorKey,bounded,limit+1).stream().map(RunResponse::from).toList();boolean more=page.size()>limit;List<RunResponse> body=more?page.subList(0,limit):page;
        ResponseEntity<List<RunResponse>> response=ApiPagination.offset(body,more?bounded+limit:null,limit);return ResponseEntity.ok().headers(response.getHeaders()).header("Cache-Control","no-store").body(response.getBody());
    }

    @GetMapping("/api/v1/integrations/sync/{connectorKey}/checkpoints")
    ResponseEntity<List<CheckpointResponse>> checkpoints(@PathVariable String connectorKey,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="50")int limit){
        int bounded=PaginationConstraints.requireOffset(offset);boundedLimit(limit);List<CheckpointResponse> page=operations.checkpoints(connectorKey,bounded,limit+1).stream().map(CheckpointResponse::from).toList();boolean more=page.size()>limit;List<CheckpointResponse> body=more?page.subList(0,limit):page;
        ResponseEntity<List<CheckpointResponse>> response=ApiPagination.offset(body,more?bounded+limit:null,limit);return ResponseEntity.ok().headers(response.getHeaders()).header("Cache-Control","no-store").body(response.getBody());
    }

    @PostMapping("/api/v1/integrations/sync/{connectorKey}/execute")
    ResponseEntity<RunResponse> execute(@PathVariable String connectorKey,@RequestHeader("Idempotency-Key")String idempotencyKey,@RequestBody ExecuteRequest body,HttpServletRequest request){
        if(body==null||body.direction()==null)throw new IllegalArgumentException("direction is required");ConnectorSyncDirection direction=direction(body.direction());int max=body.maxBatches()==null?10:body.maxBatches();
        ConnectorSyncRun run=operations.execute(connectorKey,new ConnectorSyncExecutionRequest(direction,body.fields(),body.propagateDeletions(),max),idempotencyKey,body.reason(),actor(request),correlation(request));
        return ResponseEntity.ok().header("Cache-Control","no-store").body(RunResponse.from(run));
    }

    @PostMapping("/api/v1/integrations/sync/runs/{syncRunId}/resume")
    ResponseEntity<RunResponse> resume(@PathVariable String syncRunId,@RequestHeader("Idempotency-Key")String ignored,@RequestBody ReasonRequest body,HttpServletRequest request){
        ConnectorSyncRun run=operations.resume(DomainIdentifier.parse(syncRunId),body==null?null:body.reason(),actor(request),correlation(request));return ResponseEntity.ok().header("Cache-Control","no-store").body(RunResponse.from(run));
    }

    @PostMapping("/api/v1/integrations/sync/runs/{syncRunId}/compensate")
    ResponseEntity<RunResponse> compensate(@PathVariable String syncRunId,@RequestHeader("Idempotency-Key")String ignored,@RequestBody ReasonRequest body,HttpServletRequest request){
        ConnectorSyncRun run=operations.compensate(DomainIdentifier.parse(syncRunId),body==null?null:body.reason(),actor(request),correlation(request));return ResponseEntity.ok().header("Cache-Control","no-store").body(RunResponse.from(run));
    }

    record ExecuteRequest(String direction,Set<String> fields,boolean propagateDeletions,Integer maxBatches,String reason){}
    record ReasonRequest(String reason){}
    record RunResponse(String runId,String connectorKey,String provider,String direction,String rollbackStrategy,String status,Set<String> fields,boolean propagateDeletions,int maxBatches,long initialRevision,long lastCheckpointRevision,String failureCode,String startedAt,String updatedAt,String completedAt,Long compensationCheckpointRevision){
        static RunResponse from(ConnectorSyncRun r){return new RunResponse(r.runId().toString(),r.connectorKey().value(),r.provider(),r.direction().name(),r.rollbackStrategy().name(),r.status().name(),r.fields(),r.propagateDeletions(),r.maxBatches(),r.initialRevision(),r.lastCheckpointRevision(),r.failureCode(),r.startedAt().toString(),r.updatedAt().toString(),r.completedAt()==null?null:r.completedAt().toString(),r.compensationCheckpointRevision());}
    }
    record CheckpointResponse(String checkpointId,String connectorKey,String runId,long revision,String kind,String cursorSha256,long processedCount,long changedCount,long rejectedCount,String createdAt){
        static CheckpointResponse from(ConnectorSyncCheckpoint c){return new CheckpointResponse(c.checkpointId().toString(),c.connectorKey().value(),c.runId().toString(),c.revision(),c.kind().name(),c.cursorSha256(),c.processedCount(),c.changedCount(),c.rejectedCount(),c.createdAt().toString());}
    }
    private static ConnectorSyncDirection direction(String value){try{return ConnectorSyncDirection.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));}catch(RuntimeException e){throw new IllegalArgumentException("unsupported connector sync direction",e);}}
    private static void boundedLimit(int limit){if(limit<1||limit>200)throw new IllegalArgumentException("limit must be between 1 and 200");}
    private static DomainIdentifier actor(HttpServletRequest request){Object value=request.getAttribute(AuthenticatedActorContext.ACCOUNT_ATTRIBUTE);if(value instanceof DomainIdentifier id)return id;throw new IllegalStateException("authenticated actor is missing");}
    private static DomainIdentifier correlation(HttpServletRequest request){return CorrelationContext.identifier(request).orElseThrow(()->new IllegalStateException("correlation identifier is missing"));}
}
