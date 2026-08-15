package io.infranexum.itam.asset.application;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.asset.domain.AssetLifecycleStatus;
import io.infranexum.itam.asset.domain.AssetType;

/** Stable-cursor filters for ITAM asset portfolio reads. */
public record AssetSearchCriteria(
        DomainIdentifier owningOrganizationId,
        AssetType assetType,
        AssetLifecycleStatus lifecycleStatus,
        DomainIdentifier rsotObjectId,
        DomainIdentifier afterId,
        int limit) {
    public AssetSearchCriteria {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
    }
}
