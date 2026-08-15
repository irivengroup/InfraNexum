package io.infranexum.itam.compliance.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Governed warranty-type catalogue entry; no unvalidated free-form warranty type is accepted. */
public record WarrantyType(DomainIdentifier id, String code, String displayName, boolean active, Instant createdAt, DomainIdentifier createdBy) {
    private static final Pattern CODE=Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    public WarrantyType {
        Objects.requireNonNull(id,"id");
        code=ComplianceTexts.text(code,"code",2,64).toUpperCase(Locale.ROOT);
        if(!CODE.matcher(code).matches())throw new IllegalArgumentException("invalid warranty type code");
        displayName=ComplianceTexts.text(displayName,"displayName",2,160);
        Objects.requireNonNull(createdAt,"createdAt");Objects.requireNonNull(createdBy,"createdBy");
    }
}
