package io.infranexum.server.rsot;

import io.infranexum.core.compatibility.CompatibilityReport;
import io.infranexum.core.compatibility.RegisteredSchema;
import io.infranexum.core.compatibility.RegistryStatus;
import io.infranexum.core.compatibility.SchemaKind;
import io.infranexum.core.compatibility.SchemaProfile;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** HTTP request/response contracts for the PGM-06-E03 schema registry. */
final class RsotSchemaApiModels {
    private RsotSchemaApiModels() {}

    record CreateSchemaRequest(
            @NotBlank @Size(max = 160) String schemaKey,
            SchemaKind kind,
            @NotBlank @Size(max = 160) String owner,
            @NotBlank @Size(max = 64) String version,
            JsonNode definition,
            @Size(max = 128) String effectiveAt) {}

    record UpdateSchemaRequest(JsonNode definition) {}
    record PublishSchemaRequest(@Size(max = 240) String breakingApprovalReference) {}
    record DeprecateRequest(@NotBlank @Size(max = 128) String sunsetAt, @NotBlank @Size(max = 500) String reason) {}

    record CreateProfileRequest(
            @Size(max = 160) String code,
            @NotBlank @Size(max = 160) String owner,
            @NotBlank @Size(max = 64) String version,
            @NotEmpty @Size(max = 128) List<@NotBlank String> schemaIds) {}

    record SchemaResponse(
            String id, String schemaKey, SchemaKind kind, String owner, String version, RegistryStatus status,
            JsonNode definition, String checksumSha256, long revision, Instant effectiveAt, Instant createdAt,
            Instant updatedAt, Instant publishedAt, Instant deprecatedAt, Instant sunsetAt,
            String deprecationReason, String compatibilityEvidence, String breakingApprovalReference) {
        static SchemaResponse from(RegisteredSchema schema, JacksonSchemaDefinitionInspector inspector) {
            return new SchemaResponse(schema.id().toString(), schema.schemaKey(), schema.kind(), schema.owner(),
                    schema.version().toString(), schema.status(), inspector.parseDefinition(schema.definitionJson()),
                    schema.checksumSha256(), schema.revision(), schema.effectiveAt(), schema.createdAt(), schema.updatedAt(),
                    schema.publishedAt(), schema.deprecatedAt(), schema.sunsetAt(), schema.deprecationReason(),
                    schema.compatibilityEvidence(), schema.breakingApprovalReference());
        }
    }

    record ProfileMemberResponse(int position, String schemaId, boolean required) {}
    record ProfileResponse(
            String id, String code, String owner, String version, RegistryStatus status,
            List<ProfileMemberResponse> members, String checksumSha256, long revision, Instant createdAt,
            Instant updatedAt, Instant publishedAt, Instant deprecatedAt, Instant sunsetAt, String deprecationReason) {
        static ProfileResponse from(SchemaProfile profile) {
            return new ProfileResponse(profile.id().toString(), profile.code(), profile.owner(), profile.version().toString(),
                    profile.status(), profile.members().stream().map(member -> new ProfileMemberResponse(
                            member.position(), member.schemaId().toString(), member.required())).toList(),
                    profile.checksumSha256(), profile.revision(), profile.createdAt(), profile.updatedAt(),
                    profile.publishedAt(), profile.deprecatedAt(), profile.sunsetAt(), profile.deprecationReason());
        }
    }

    record CompatibilityResponse(String verdict, List<String> issues) {
        static CompatibilityResponse from(CompatibilityReport report) {
            return new CompatibilityResponse(report.verdict().name(), report.issues());
        }
    }
}
