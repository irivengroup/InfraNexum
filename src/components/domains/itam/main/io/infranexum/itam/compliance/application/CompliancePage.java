package io.infranexum.itam.compliance.application;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.List;
import java.util.Objects;

/** Stable UUIDv7 cursor page for asset-bound contractual records. */
public record CompliancePage<T>(List<T> items,DomainIdentifier nextAfterId) {
    public CompliancePage { items=List.copyOf(Objects.requireNonNull(items,"items")); }
}
