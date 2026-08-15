package io.infranexum.itam.partner.application;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.partner.domain.Partner;
import java.util.List;
import java.util.Objects;

/** One deterministic Partner page and the UUIDv7 cursor for the following page. */
public record PartnerPage(List<Partner> items, DomainIdentifier nextCursor) {
    public PartnerPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
