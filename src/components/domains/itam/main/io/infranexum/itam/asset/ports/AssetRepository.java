package io.infranexum.itam.asset.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.asset.application.AssetPage;
import io.infranexum.itam.asset.application.AssetSearchCriteria;
import io.infranexum.itam.asset.domain.Asset;
import io.infranexum.itam.asset.domain.AssetCustodyEvent;
import java.util.List;
import java.util.Optional;

/** Authoritative ITAM storage port for asset current state and append-only custody history. */
public interface AssetRepository {
    long count();
    boolean existsByRsotObjectId(DomainIdentifier rsotObjectId);
    Optional<Asset> findById(DomainIdentifier id);
    void insert(Asset asset, AssetCustodyEvent acquisitionEvent);
    void update(Asset asset, long expectedVersion, AssetCustodyEvent custodyEvent);
    void updateMetadata(Asset asset, long expectedVersion);
    AssetPage search(AssetSearchCriteria criteria);
    List<AssetCustodyEvent> custodyHistory(DomainIdentifier assetId, long afterSequence, int limit);
}
