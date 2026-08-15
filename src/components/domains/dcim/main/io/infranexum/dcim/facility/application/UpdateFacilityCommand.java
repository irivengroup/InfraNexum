package io.infranexum.dcim.facility.application;

import java.math.BigDecimal;

/** Mutable metadata for an existing DCIM node; authority and parentage remain immutable in E04. */
public record UpdateFacilityCommand(
        String displayName, String addressLine1, String addressLine2, String postalCode, String city, String countryCode,
        String timezone, BigDecimal latitude, BigDecimal longitude,
        Integer floorCount, Integer levelNumber, BigDecimal areaM2, BigDecimal levelHeightM, BigDecimal capacityKw,
        String accessRestriction, String zoneType, String description) {}
