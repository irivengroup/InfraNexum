package io.infranexum.itam.partner.application;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.partner.domain.PartnerAuthorizationStatus;
import io.infranexum.itam.partner.domain.PartnerRole;
import java.time.LocalDate;

/** Stable-cursor search filters for governed Partner catalogues. */
public record PartnerSearchCriteria(
        DomainIdentifier governingOrganizationId,
        PartnerRole role,
        PartnerAuthorizationStatus authorizationStatus,
        String countryCode,
        String accreditation,
        LocalDate effectiveOn,
        DomainIdentifier afterId,
        int limit) {
    public PartnerSearchCriteria {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        if (countryCode != null && !countryCode.isBlank()) {
            countryCode = countryCode.strip().toUpperCase(java.util.Locale.ROOT);
            if (!countryCode.matches("[A-Z]{2}")) {
                throw new IllegalArgumentException("countryCode must be ISO 3166-1 alpha-2");
            }
        }
        if (accreditation != null && !accreditation.isBlank()) {
            accreditation = accreditation.strip();
        }
    }
}
