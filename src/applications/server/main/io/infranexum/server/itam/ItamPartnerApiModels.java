package io.infranexum.server.itam;

import io.infranexum.itam.partner.domain.Partner;
import io.infranexum.itam.partner.domain.PartnerAccreditation;
import io.infranexum.itam.partner.domain.PartnerContact;
import io.infranexum.itam.partner.domain.PartnerExternalId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** HTTP DTOs for the governed ITAM Partner catalogue. */
final class ItamPartnerApiModels {
    private ItamPartnerApiModels() {}

    record ExternalIdRequest(
            @NotBlank @Size(max = 64) String authority,
            @NotBlank @Size(max = 240) String value) {
        PartnerExternalId toDomain() { return new PartnerExternalId(authority, value); }
    }

    record AccreditationRequest(
            @NotBlank @Size(max = 120) String code,
            @NotBlank @Size(max = 200) String issuer,
            @NotNull LocalDate validFrom,
            LocalDate validUntil,
            @NotBlank @Size(max = 240) String evidenceReference) {
        PartnerAccreditation toDomain() {
            return new PartnerAccreditation(code, issuer, validFrom, validUntil, evidenceReference);
        }
    }

    record ContactRequest(
            @NotBlank @Size(max = 32) String type,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 320) String email,
            @Size(max = 64) String phone,
            @Size(max = 2048) String uri) {
        PartnerContact toDomain() { return new PartnerContact(type, name, email, phone, uri); }
    }

    record CreatePartnerRequest(
            @NotBlank String governingOrganizationId,
            String governingSubdivisionId,
            @NotBlank @Size(min = 3, max = 32) String code,
            @NotBlank @Size(min = 2, max = 255) String legalName,
            @NotBlank @Size(min = 2, max = 255) String displayName,
            @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String countryCode,
            @NotEmpty Set<@NotBlank String> roles,
            @NotNull LocalDate validFrom,
            LocalDate validUntil,
            @Size(max = 2048) String officialWebsite,
            @Size(max = 2048) String supportPortal,
            @Size(max = 64) List<@NotBlank @Size(min = 2, max = 255) String> aliases,
            @Valid @Size(max = 64) List<ExternalIdRequest> externalIds,
            @Valid @Size(max = 128) List<AccreditationRequest> accreditations,
            @Valid @Size(max = 128) List<ContactRequest> contacts,
            @NotBlank @Size(min = 2, max = 1024) String reason) {}

    record PartnerTransitionRequest(@NotBlank @Size(min = 2, max = 1024) String reason) {}

    record ExternalIdResponse(String authority, String value) {}
    record AccreditationResponse(String code, String issuer, LocalDate validFrom, LocalDate validUntil, String evidenceReference) {}
    record ContactResponse(String type, String name, String email, String phone, String uri) {}

    record PartnerResponse(
            String id,
            String governingOrganizationId,
            String governingSubdivisionId,
            String code,
            String legalName,
            String displayName,
            String countryCode,
            List<String> roles,
            String authorizationStatus,
            LocalDate validFrom,
            LocalDate validUntil,
            String officialWebsite,
            String supportPortal,
            List<String> aliases,
            List<ExternalIdResponse> externalIds,
            List<AccreditationResponse> accreditations,
            List<ContactResponse> contacts,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        static PartnerResponse from(Partner partner) {
            List<String> roles = partner.roles().stream().map(value -> value.wireValue()).sorted().toList();
            List<ExternalIdResponse> externalIds = partner.externalIds().stream()
                    .map(value -> new ExternalIdResponse(value.authority(), value.value())).toList();
            List<AccreditationResponse> accreditations = partner.accreditations().stream()
                    .map(value -> new AccreditationResponse(value.code(), value.issuer(), value.validFrom(),
                            value.validUntil(), value.evidenceReference())).toList();
            List<ContactResponse> contacts = partner.contacts().stream()
                    .map(value -> new ContactResponse(value.type(), value.name(), value.email(), value.phone(), value.uri())).toList();
            return new PartnerResponse(
                    partner.id().toString(), partner.governingOrganizationId().toString(),
                    partner.governingSubdivisionId() == null ? null : partner.governingSubdivisionId().toString(),
                    partner.code().value(), partner.legalName(), partner.displayName(), partner.countryCode(), roles,
                    partner.authorizationStatus().wireValue(), partner.validFrom(), partner.validUntil(),
                    partner.officialWebsite(), partner.supportPortal(), partner.aliases(), externalIds, accreditations,
                    contacts, partner.version(), partner.createdAt(), partner.updatedAt());
        }
    }

    record PartnerPageResponse(List<PartnerResponse> items, String nextCursor) {}
}
