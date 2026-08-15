package io.infranexum.itam.asset.application;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.asset.domain.Asset;
import java.util.List;
import java.util.Objects;

/** Bounded stable-cursor page for asset portfolio queries. */
public record AssetPage(List<Asset> items, DomainIdentifier nextAfterId) {
    public AssetPage { items = List.copyOf(Objects.requireNonNull(items, "items")); }
}
