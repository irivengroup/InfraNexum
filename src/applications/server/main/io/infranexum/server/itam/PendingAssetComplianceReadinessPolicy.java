package io.infranexum.server.itam;

import io.infranexum.itam.asset.domain.Asset;
import io.infranexum.itam.asset.domain.AssetConflictException;
import io.infranexum.itam.asset.domain.AssetLifecycleStatus;
import io.infranexum.itam.asset.ports.AssetOperationalReadinessPolicy;
import java.util.Objects;

/**
 * Fail-closed bridge between PGM-07-E02 lifecycle and the PGM-07-E03 warranty/license authority.
 *
 * <p>Operational promotion is intentionally blocked until E03 can prove the contextual mandatory
 * warranty or software-license contract. Non-operational lifecycle operations remain available.</p>
 */
final class PendingAssetComplianceReadinessPolicy implements AssetOperationalReadinessPolicy {
    @Override
    public void requireReady(Asset asset, AssetLifecycleStatus targetStatus) {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(targetStatus, "targetStatus");
        if (targetStatus.operationalReadinessRequired()) {
            throw new AssetConflictException(
                    "ITAM_ASSET_COMPLIANCE_GATE_UNAVAILABLE",
                    "operational transition requires PGM-07-E03 warranty/license compliance evidence");
        }
    }
}
