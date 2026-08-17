package io.infranexum.server.integrations;

import io.infranexum.adapters.jiraassets.JiraAssetsConnector;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.PaginationConstraints;
import io.infranexum.server.http.ApiPagination;
import io.infranexum.server.http.AuthenticatedActorContext;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated HTTP boundary for the Jira Assets governed federated-read provider. */
@RestController
@ConditionalOnProperty(name = "infranexum.integrations.enabled", havingValue = "true")
final class JiraAssetsController {
    private final JiraAssetsOperationsService operations;

    JiraAssetsController(JiraAssetsOperationsService operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @GetMapping("/api/v1/integrations/providers/jira-assets")
    ResponseEntity<List<JiraAssetsOperationsService.ConnectorDescriptor>> connectors(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        int boundedOffset = PaginationConstraints.requireOffset(offset);
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        List<JiraAssetsOperationsService.ConnectorDescriptor> all = operations.connectors();
        int start = Math.min(boundedOffset, all.size());
        int end = Math.min(start + limit, all.size());
        Integer nextOffset = end < all.size() ? end : null;
        ResponseEntity<List<JiraAssetsOperationsService.ConnectorDescriptor>> page =
                ApiPagination.offset(all.subList(start, end), nextOffset, limit);
        return ResponseEntity.ok().headers(page.getHeaders()).header("Cache-Control", "no-store").body(page.getBody());
    }

    @GetMapping("/api/v1/integrations/providers/jira-assets/{connectorKey}/health")
    ResponseEntity<JiraAssetsConnector.Health> health(@PathVariable String connectorKey, HttpServletRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(operations.health(connectorKey, actor(request), correlation(request)));
    }

    @PostMapping("/api/v1/integrations/providers/jira-assets/{connectorKey}/objects/search")
    ResponseEntity<List<JiraAssetsConnector.RemoteObject>> search(
            @PathVariable String connectorKey,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestBody SearchRequest body,
            HttpServletRequest request) {
        if (body == null || body.aql() == null) throw new IllegalArgumentException("aql is required");
        JiraAssetsConnector.ObjectPage page = operations.search(
                connectorKey, body.aql(), offset, limit, actor(request), correlation(request));
        ResponseEntity<List<JiraAssetsConnector.RemoteObject>> paged = ApiPagination.offset(page.items(), page.nextOffset(), limit);
        return ResponseEntity.ok().headers(paged.getHeaders()).header("Cache-Control", "no-store").body(paged.getBody());
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

    record SearchRequest(String aql) {}
}
