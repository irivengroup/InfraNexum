package io.infranexum.dcim.facility.application;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.dcim.facility.domain.FacilityKind;
import java.math.BigDecimal;

/** Declarative creation contract for all PGM-07-E04 physical hierarchy nodes. */
public record CreateFacilityCommand(
        FacilityKind kind, DomainIdentifier organizationId, DomainIdentifier subdivisionId, DomainIdentifier parentId,
        String code, String displayName, String addressLine1, String addressLine2, String postalCode, String city,
        String countryCode, String timezone, BigDecimal latitude, BigDecimal longitude,
        Integer floorCount, Integer levelNumber, BigDecimal areaM2, BigDecimal levelHeightM, BigDecimal capacityKw,
        String accessRestriction, String zoneType, String description) {}
