package io.infranexum.itam.partner.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Versionable accreditation evidence attached to a Partner. */
public record PartnerAccreditation(
        String code, String issuer, LocalDate validFrom, LocalDate validUntil, String evidenceReference) {
    public PartnerAccreditation {
        code = text(code, "accreditation code", 2, 120);
        issuer = text(issuer, "accreditation issuer", 2, 200);
        Objects.requireNonNull(validFrom, "validFrom");
        if (validUntil != null && validUntil.isBefore(validFrom)) {
            throw new IllegalArgumentException("accreditation validUntil precedes validFrom");
        }
        evidenceReference = text(evidenceReference, "evidence reference", 2, 240);
    }
    private static String text(String value, String field, int min, int max) {
        Objects.requireNonNull(value, field); String result = value.strip();
        if (result.length() < min || result.length() > max || result.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return result;
    }
}
