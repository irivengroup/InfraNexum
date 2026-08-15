package io.infranexum.itam.asset.ports;

import io.infranexum.itam.asset.domain.Asset;
import io.infranexum.itam.asset.domain.AssetLifecycleStatus;

/**
 * Compliance readiness gate required before an asset becomes operational.
 * PGM-07-E03 will supply warranty/license-aware runtime implementation.
 */
public interface AssetOperationalReadinessPolicy {
    void requireReady(Asset asset, AssetLifecycleStatus targetStatus);
}
