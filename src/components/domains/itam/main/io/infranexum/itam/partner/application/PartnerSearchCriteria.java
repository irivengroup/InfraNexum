package io.infranexum.itam.partner.application;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.partner.domain.PartnerAuthorizationStatus;
import io.infranexum.itam.partner.domain.PartnerRole;
import java.time.LocalDate;
import java.util.Locale;

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
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        countryCode = optionalFilter(countryCode, "countryCode");
        if (countryCode != null) {
            countryCode = countryCode.toUpperCase(Locale.ROOT);
            if (!countryCode.matches("[A-Z]{2}")) {
                throw new IllegalArgumentException("countryCode must be ISO 3166-1 alpha-2");
            }
        }
        accreditation = optionalFilter(accreditation, "accreditation");
    }

    private static String optionalFilter(String value, String field) {
        if (value == null) {
            return null;
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
