package io.infranexum.dcim.facility.application;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.dcim.facility.domain.FacilityKind;
import io.infranexum.dcim.facility.domain.FacilityStatus;
import java.util.Locale;
import java.util.Set;

/** Bounded cursor criteria for physical hierarchy catalogue reads. */
public record FacilitySearchCriteria(DomainIdentifier organizationId, DomainIdentifier subdivisionId, FacilityKind kind,
        DomainIdentifier parentId, FacilityStatus status, String countryCode, DomainIdentifier afterId, int limit) {
    public FacilitySearchCriteria {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        if (countryCode != null && !countryCode.isBlank()) {
            if (kind != FacilityKind.SITE) throw new IllegalArgumentException("countryCode filter is only valid for sites");
            countryCode = countryCode.strip().toUpperCase(Locale.ROOT);
            if (countryCode.length() != 2 || !Set.of(Locale.getISOCountries()).contains(countryCode)) {
                throw new IllegalArgumentException("invalid countryCode filter");
            }
        } else {
            countryCode = null;
        }
    }
}
