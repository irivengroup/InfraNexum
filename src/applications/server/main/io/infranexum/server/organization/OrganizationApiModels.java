package io.infranexum.server.organization;

import io.infranexum.organization.domain.Organization;
import io.infranexum.organization.domain.Subdivision;
import io.infranexum.organization.domain.TemporalScope;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Locale;

/** HTTP DTOs kept outside the domain model. */
final class OrganizationApiModels {
    private OrganizationApiModels() {}

    record CreateOrganizationRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(min = 2, max = 160) String displayName,
            @NotBlank @Size(min = 2, max = 255) String legalName,
            @NotBlank @Size(min = 2, max = 2) String countryCode,
            @NotBlank @Size(max = 35) String defaultLanguage,
            @NotBlank @Size(max = 80) String timezone,
            @NotBlank @Size(min = 3, max = 3) String currency,
            String parentOrganizationId) {}

    record CreateSubdivisionRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(min = 2, max = 160) String displayName,
            @Size(max = 4000) String description,
            @NotBlank @Size(max = 32) String type,
            String parentSubdivisionId) {}

    record CreateScopeRequest(
            String subdivisionId,
            @NotBlank @Size(max = 24) String type,
            @NotNull Instant validFrom,
            Instant validTo) {}

    record TransitionRequest(
            @Min(0) long version,
            @Size(max = 512) String reason) {}

    record OrganizationResponse(
            String id,
            String code,
            String displayName,
            String legalName,
            String countryCode,
            String defaultLanguage,
            String timezone,
            String currency,
            String parentOrganizationId,
            String status,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        static OrganizationResponse from(Organization organization) {
            return new OrganizationResponse(
                    organization.id().toString(),
                    organization.code().value(),
                    organization.displayName(),
                    organization.legalName(),
                    organization.countryCode(),
                    organization.defaultLanguage(),
                    organization.timezone(),
                    organization.currency(),
                    organization.parentOrganizationId() == null
                            ? null
                            : organization.parentOrganizationId().toString(),
                    organization.state().name().toLowerCase(Locale.ROOT),
                    organization.version(),
                    organization.createdAt(),
                    organization.updatedAt());
        }
    }

    record SubdivisionResponse(
            String id,
            String organizationId,
            String code,
            String displayName,
            String description,
            String type,
            String status,
            String parentSubdivisionId,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        static SubdivisionResponse from(Subdivision subdivision) {
            return new SubdivisionResponse(
                    subdivision.id().toString(),
                    subdivision.organizationId().toString(),
                    subdivision.code().value(),
                    subdivision.displayName(),
                    subdivision.description(),
                    subdivision.type().wireValue(),
                    subdivision.state().name().toLowerCase(Locale.ROOT),
                    subdivision.parentSubdivisionId() == null
                            ? null
                            : subdivision.parentSubdivisionId().toString(),
                    subdivision.version(),
                    subdivision.createdAt(),
                    subdivision.updatedAt());
        }
    }

    record ScopeResponse(
            String id,
            String organizationId,
            String subdivisionId,
            String type,
            Instant validFrom,
            Instant validTo,
            long version) {
        static ScopeResponse from(TemporalScope scope) {
            return new ScopeResponse(
                    scope.id().toString(),
                    scope.organizationId().toString(),
                    scope.subdivisionId() == null ? null : scope.subdivisionId().toString(),
                    scope.type().wireValue(),
                    scope.validFrom(),
                    scope.validTo(),
                    scope.version());
        }
    }
}
