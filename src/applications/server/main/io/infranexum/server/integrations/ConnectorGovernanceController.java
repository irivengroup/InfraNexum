package io.infranexum.server.integrations;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.PaginationConstraints;
import io.infranexum.integrations.ConnectorGovernancePolicy;
import io.infranexum.integrations.ConnectorSyncDirection;
import io.infranexum.integrations.ConnectorSyncPlan;
import io.infranexum.integrations.ConnectorSyncPlanRequest;
import io.infranexum.server.http.ApiPagination;
import io.infranexum.server.http.AuthenticatedActorContext;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator boundary for fail-closed connector authority, sync-direction and rollback governance. */
@RestController
@ConditionalOnProperty(name = "infranexum.integrations.enabled", havingValue = "true")
final class ConnectorGovernanceController {
    private final ConnectorGovernanceOperationsService operations;

    ConnectorGovernanceController(ConnectorGovernanceOperationsService operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @GetMapping("/api/v1/integrations/governance")
    ResponseEntity<List<GovernancePolicyResponse>> policies(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        int boundedOffset = PaginationConstraints.requireOffset(offset);
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        List<GovernancePolicyResponse> all = operations.policies().stream().map(GovernancePolicyResponse::from).toList();
        int start = Math.min(boundedOffset, all.size());
        int end = Math.min(start + limit, all.size());
        Integer nextOffset = end < all.size() ? end : null;
        ResponseEntity<List<GovernancePolicyResponse>> page = ApiPagination.offset(all.subList(start, end), nextOffset, limit);
        return ResponseEntity.ok().headers(page.getHeaders()).header("Cache-Control", "no-store").body(page.getBody());
    }

    @GetMapping("/api/v1/integrations/governance/{connectorKey}")
    ResponseEntity<GovernancePolicyResponse> policy(@PathVariable String connectorKey) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(GovernancePolicyResponse.from(operations.require(connectorKey)));
    }

    @PostMapping("/api/v1/integrations/governance/{connectorKey}/sync-plan")
    ResponseEntity<SyncPlanResponse> plan(
            @PathVariable String connectorKey,
            @RequestBody SyncPlanRequest body,
            HttpServletRequest request) {
        if (body == null || body.direction() == null) throw new IllegalArgumentException("direction is required");
        ConnectorSyncDirection direction;
        try { direction = ConnectorSyncDirection.valueOf(body.direction()); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("unsupported connector sync direction", invalid); }
        ConnectorSyncPlan result = operations.plan(
                connectorKey,
                new ConnectorSyncPlanRequest(direction, body.fields(), body.propagateDeletions()),
                actor(request), correlation(request));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(SyncPlanResponse.from(result));
    }

    record SyncPlanRequest(String direction, Set<String> fields, boolean propagateDeletions) {}

    record GovernancePolicyResponse(
            String connectorKey,
            String provider,
            String direction,
            String authority,
            String conflictStrategy,
            String deletionPolicy,
            String rollbackStrategy,
            boolean mutating,
            List<FieldAuthorityResponse> fields) {
        static GovernancePolicyResponse from(ConnectorGovernancePolicy policy) {
            return new GovernancePolicyResponse(
                    policy.connectorKey().value(), policy.provider(), policy.direction().name(), policy.authority().name(),
                    policy.conflictStrategy().name(), policy.deletionPolicy().name(), policy.rollbackStrategy().name(),
                    policy.direction().mutating(), policy.fields().stream().map(FieldAuthorityResponse::from).toList());
        }
    }

    record FieldAuthorityResponse(String field, String authority) {
        static FieldAuthorityResponse from(io.infranexum.integrations.ConnectorFieldAuthority value) {
            return new FieldAuthorityResponse(value.field(), value.authority().name());
        }
    }

    record SyncPlanResponse(
            String connectorKey,
            String provider,
            String configuredDirection,
            String requestedDirection,
            String decision,
            boolean mutating,
            String rollbackStrategy,
            Set<String> fields,
            List<String> reasons) {
        static SyncPlanResponse from(ConnectorSyncPlan plan) {
            return new SyncPlanResponse(
                    plan.connectorKey().value(), plan.provider(), plan.configuredDirection().name(),
                    plan.requestedDirection().name(), plan.decision().name(), plan.mutating(),
                    plan.rollbackStrategy().name(), plan.fields(), plan.reasons());
        }
    }

    private static DomainIdentifier actor(HttpServletRequest request) {
        Object value = request.getAttribute(AuthenticatedActorContext.ACCOUNT_ATTRIBUTE);
        if (value instanceof DomainIdentifier id) return id;
        throw new IllegalStateException("authenticated actor is missing");
    }

    private static DomainIdentifier correlation(HttpServletRequest request) {
        return CorrelationContext.identifier(request)
                .orElseThrow(() -> new IllegalStateException("correlation identifier is missing"));
    }
}
