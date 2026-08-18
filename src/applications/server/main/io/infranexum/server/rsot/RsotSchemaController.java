package io.infranexum.server.rsot;

import io.infranexum.server.http.AuthenticatedActorContext;
import static io.infranexum.server.rsot.RsotSchemaApiModels.*;

import io.infranexum.core.compatibility.CreateProfileCommand;
import io.infranexum.core.compatibility.CreateSchemaCommand;
import io.infranexum.core.compatibility.RegistryStatus;
import io.infranexum.core.compatibility.SchemaKind;
import io.infranexum.core.compatibility.SchemaRegistryCommandContext;
import io.infranexum.core.compatibility.SchemaRegistryService;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.server.configuration.ServerTemporalInputParser;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Versioned Core Schema Registry HTTP boundary consumed by RSOT and its extensions. */
@RestController
@ConditionalOnExpression("\'${infranexum.persistence.mode:MEMORY}\' == \'POSTGRESQL\' || \'${infranexum.persistence.mode:MEMORY}\' == \'ORACLE\'")
@RequestMapping("/api/v1/rsot")
public final class RsotSchemaController {
    private final SchemaRegistryService service;
    private final JacksonSchemaDefinitionInspector inspector;
    private final ServerTemporalInputParser temporal;
    private final UuidV7Generator ids;

    public RsotSchemaController(
            SchemaRegistryService service,
            JacksonSchemaDefinitionInspector inspector,
            ServerTemporalInputParser temporal,
            @Qualifier("platformClock") Clock clock) {
        this.service = Objects.requireNonNull(service, "service");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.temporal = Objects.requireNonNull(temporal, "temporal");
        this.ids = new UuidV7Generator(Objects.requireNonNull(clock, "clock"), new SecureRandom());
    }

    @PostMapping("/schemas")
    ResponseEntity<SchemaResponse> createSchema(@Valid @RequestBody CreateSchemaRequest body, HttpServletRequest request) {
        requireDefinition(body.definition());
        var schema = service.createSchema(new CreateSchemaCommand(body.schemaKey(), Objects.requireNonNull(body.kind(), "kind"),
                body.owner(), body.version(), inspector.writeDefinition(body.definition()), temporal.optionalInstant(body.effectiveAt(), "effectiveAt")), context(request));
        return response(schema, HttpStatus.CREATED);
    }

    @GetMapping("/schemas")
    List<SchemaResponse> schemas(
            @RequestParam(required = false) String schemaKey,
            @RequestParam(required = false) SchemaKind kind,
            @RequestParam(required = false) RegistryStatus status,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return service.listSchemas(schemaKey, kind, status, offset, limit).stream().map(schema -> SchemaResponse.from(schema, inspector)).toList();
    }

    @GetMapping("/schemas/{schemaId}")
    ResponseEntity<SchemaResponse> schema(@PathVariable String schemaId) {
        return response(service.getSchema(id(schemaId)), HttpStatus.OK);
    }

    @PatchMapping("/schemas/{schemaId}")
    ResponseEntity<SchemaResponse> updateSchema(
            @PathVariable String schemaId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody UpdateSchemaRequest body,
            HttpServletRequest request) {
        requireDefinition(body.definition());
        return response(service.updateDraft(id(schemaId), revision(ifMatch), inspector.writeDefinition(body.definition()), context(request)), HttpStatus.OK);
    }

    @GetMapping("/schemas/{schemaId}/compatibility")
    CompatibilityResponse compatibility(@PathVariable String schemaId) {
        return CompatibilityResponse.from(service.previewCompatibility(id(schemaId)));
    }

    @PostMapping("/schemas/{schemaId}/publish")
    ResponseEntity<SchemaResponse> publishSchema(
            @PathVariable String schemaId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody(required = false) PublishSchemaRequest body,
            HttpServletRequest request) {
        String approval = body == null ? null : body.breakingApprovalReference();
        return response(service.publish(id(schemaId), revision(ifMatch), approval, context(request)), HttpStatus.OK);
    }

    @PostMapping("/schemas/{schemaId}/deprecate")
    ResponseEntity<SchemaResponse> deprecateSchema(
            @PathVariable String schemaId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody DeprecateRequest body,
            HttpServletRequest request) {
        return response(service.deprecate(id(schemaId), revision(ifMatch), requiredInstant(body.sunsetAt(), "sunsetAt"), body.reason(), context(request)), HttpStatus.OK);
    }

    @PostMapping("/schema-profiles")
    ResponseEntity<ProfileResponse> createProfile(@Valid @RequestBody CreateProfileRequest body, HttpServletRequest request) {
        List<DomainIdentifier> memberIds = body.schemaIds().stream().map(RsotSchemaController::id).toList();
        var profile = service.createProfile(new CreateProfileCommand(body.code(), body.owner(), body.version(), memberIds), context(request));
        return profileResponse(profile, HttpStatus.CREATED);
    }

    @GetMapping("/schema-profiles")
    List<ProfileResponse> profiles(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) RegistryStatus status,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return service.listProfiles(code, status, offset, limit).stream().map(ProfileResponse::from).toList();
    }

    @GetMapping("/schema-profiles/{profileId}")
    ResponseEntity<ProfileResponse> profile(@PathVariable String profileId) {
        return profileResponse(service.getProfile(id(profileId)), HttpStatus.OK);
    }

    @PostMapping("/schema-profiles/{profileId}/publish")
    ResponseEntity<ProfileResponse> publishProfile(
            @PathVariable String profileId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            HttpServletRequest request) {
        return profileResponse(service.publishProfile(id(profileId), revision(ifMatch), context(request)), HttpStatus.OK);
    }

    @PostMapping("/schema-profiles/{profileId}/deprecate")
    ResponseEntity<ProfileResponse> deprecateProfile(
            @PathVariable String profileId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody DeprecateRequest body,
            HttpServletRequest request) {
        return profileResponse(service.deprecateProfile(id(profileId), revision(ifMatch), requiredInstant(body.sunsetAt(), "sunsetAt"), body.reason(), context(request)), HttpStatus.OK);
    }

    private ResponseEntity<SchemaResponse> response(io.infranexum.core.compatibility.RegisteredSchema schema, HttpStatus status) {
        return ResponseEntity.status(status).eTag(etag(schema.revision())).body(SchemaResponse.from(schema, inspector));
    }

    private static ResponseEntity<ProfileResponse> profileResponse(io.infranexum.core.compatibility.SchemaProfile profile, HttpStatus status) {
        return ResponseEntity.status(status).eTag(etag(profile.revision())).body(ProfileResponse.from(profile));
    }

    private java.time.Instant requiredInstant(String value, String field) {
        java.time.Instant parsed = temporal.optionalInstant(value, field);
        if (parsed == null) throw new IllegalArgumentException(field + " is required");
        return parsed;
    }

    private SchemaRegistryCommandContext context(HttpServletRequest request) {
        Object actor = request.getAttribute(AuthenticatedActorContext.ACCOUNT_ATTRIBUTE);
        if (!(actor instanceof DomainIdentifier actorId)) throw new IllegalStateException("authenticated actor missing after RBAC boundary");
        return new SchemaRegistryCommandContext(actorId, CorrelationContext.identifier(request).orElseGet(ids::next));
    }

    private static void requireDefinition(tools.jackson.databind.JsonNode definition) {
        if (definition == null || !definition.isObject()) throw new IllegalArgumentException("definition must be a JSON object");
    }

    private static long revision(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("If-Match is required");
        String normalized = value.strip();
        if (normalized.startsWith("W/")) normalized = normalized.substring(2).strip();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) normalized = normalized.substring(1, normalized.length() - 1);
        if (normalized.toLowerCase(Locale.ROOT).startsWith("rev-")) normalized = normalized.substring(4);
        try {
            long parsed = Long.parseLong(normalized);
            if (parsed < 1) throw new NumberFormatException("non-positive");
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("If-Match must contain a positive registry revision", failure);
        }
    }

    private static String etag(long revision) { return "\"rev-" + revision + "\""; }
    private static DomainIdentifier id(String value) { return DomainIdentifier.parse(value); }
}
