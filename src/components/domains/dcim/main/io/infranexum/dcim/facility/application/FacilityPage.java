package io.infranexum.dcim.facility.application;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.dcim.facility.domain.FacilityNode;
import java.util.List;

/** Cursor page returned by DCIM facility searches. */
public record FacilityPage(List<FacilityNode> items, DomainIdentifier nextCursor) {
    public FacilityPage { items = List.copyOf(items); }
}
