package io.infranexum.itam.partner.application;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.partner.domain.PartnerAccreditation;
import io.infranexum.itam.partner.domain.PartnerContact;
import io.infranexum.itam.partner.domain.PartnerExternalId;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Complete creation contract for a governed Partner draft. */
public record CreatePartnerCommand(
        DomainIdentifier governingOrganizationId,
        DomainIdentifier governingSubdivisionId,
        String code,
        String legalName,
        String displayName,
        String countryCode,
        Set<String> roles,
        LocalDate validFrom,
        LocalDate validUntil,
        String officialWebsite,
        String supportPortal,
        List<String> aliases,
        List<PartnerExternalId> externalIds,
        List<PartnerAccreditation> accreditations,
        List<PartnerContact> contacts) {
}
