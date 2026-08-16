package io.infranexum.server.integrations;

import static io.infranexum.server.integrations.IntegrationApiModels.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorWebhookService;
import io.infranexum.server.http.ApiPagination;
import io.infranexum.server.identity.LocalAuthenticationFilter;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** HTTP boundary for authenticated provider webhooks and audited connector operations. */
@RestController
@ConditionalOnProperty(name = "infranexum.integrations.enabled", havingValue = "true")
public final class IntegrationController {
    private final ConnectorWebhookService webhooks;
    private final IntegrationOperationsService operations;
    private final ObjectMapper json;
    private final Clock clock;
    private final int maximumPayloadBytes;

    public IntegrationController(
            ConnectorWebhookService webhooks,
            IntegrationOperationsService operations,
            ObjectMapper json,
            @Qualifier("platformClock") Clock clock,
            IntegrationRuntimeProperties properties) {
        this.webhooks = Objects.requireNonNull(webhooks, "webhooks");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumPayloadBytes = Objects.requireNonNull(properties, "properties").webhookMaxPayloadBytes();
    }

    @PostMapping(
            path = "/api/v1/integrations/webhooks/{connectorKey}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WebhookAdmissionResponse> admit(
            @PathVariable String connectorKey,
            @RequestHeader("X-InfraNexum-Delivery-ID") String deliveryId,
            @RequestHeader("X-InfraNexum-Webhook-Timestamp") String timestamp,
            @RequestHeader("X-InfraNexum-Webhook-Signature") String signature,
            @RequestBody byte[] payload) {
        validatePayloadSize(payload);
        validateJson(payload);
        long epochSecond = parseEpochSecond(timestamp);
        var outcome = webhooks.admit(connectorKey, deliveryId, epochSecond, signature, payload);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Cache-Control", "no-store")
                .body(WebhookAdmissionResponse.from(outcome.delivery(), outcome.duplicate()));
    }

    @GetMapping("/api/v1/integrations/dlq")
    ResponseEntity<List<DeadLetterResponse>> deadLetters(
            @RequestParam(required = false) String connectorKey,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        ConnectorKey key = connectorKey == null ? null : new ConnectorKey(connectorKey);
        var page = operations.deadLetters(key, offset, limit);
        var items = page.items().stream().map(DeadLetterResponse::from).toList();
        return ApiPagination.offset(items, page.nextOffset(), limit);
    }

    @PostMapping("/api/v1/integrations/dlq/{deliveryId}/replay")
    DeadLetterResponse replay(
            @PathVariable String deliveryId,
            @RequestBody(required = false) ReasonRequest body,
            HttpServletRequest request) {
        return DeadLetterResponse.from(operations.replay(
                DomainIdentifier.parse(deliveryId),
                actor(request),
                correlation(request),
                body == null ? null : body.reason()));
    }

    @GetMapping("/api/v1/integrations/connectors/{connectorKey}/runtime")
    RuntimeStateResponse runtime(@PathVariable String connectorKey) {
        return RuntimeStateResponse.from(operations.runtime(new ConnectorKey(connectorKey)), clock.instant());
    }

    @PostMapping("/api/v1/integrations/connectors/{connectorKey}/resume")
    RuntimeStateResponse resume(
            @PathVariable String connectorKey,
            @RequestBody(required = false) ReasonRequest body,
            HttpServletRequest request) {
        return RuntimeStateResponse.from(
                operations.resume(
                        new ConnectorKey(connectorKey),
                        actor(request),
                        correlation(request),
                        body == null ? null : body.reason()),
                clock.instant());
    }

    private void validatePayloadSize(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0 || payload.length > maximumPayloadBytes) {
            throw new IllegalArgumentException("webhook payload size is invalid");
        }
    }

    private void validateJson(byte[] payload) {
        try {
            JsonNode root = json.readTree(payload);
            if (root == null || (!root.isObject() && !root.isArray())) {
                throw new IllegalArgumentException("webhook payload root must be a JSON object or array");
            }
        } catch (JacksonException invalid) {
            throw new IllegalArgumentException("webhook payload is invalid JSON", invalid);
        }
    }

    private static long parseEpochSecond(String value) {
        if (value == null || !value.matches("[0-9]{1,12}")) {
            throw new IllegalArgumentException("webhook timestamp must be epoch seconds");
        }
        return Long.parseLong(value);
    }

    private static DomainIdentifier actor(HttpServletRequest request) {
        Object value = request.getAttribute(LocalAuthenticationFilter.ACCOUNT_ATTRIBUTE);
        if (value instanceof DomainIdentifier id) {
            return id;
        }
        throw new IllegalStateException("authenticated actor is missing");
    }

    private static DomainIdentifier correlation(HttpServletRequest request) {
        return CorrelationContext.identifier(request)
                .orElseThrow(() -> new IllegalStateException("correlation identifier is missing"));
    }
}
