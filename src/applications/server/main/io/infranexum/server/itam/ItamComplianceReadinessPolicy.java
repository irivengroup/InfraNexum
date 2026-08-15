package io.infranexum.server.itam;

import io.infranexum.itam.asset.domain.Asset;
import io.infranexum.itam.asset.domain.AssetConflictException;
import io.infranexum.itam.asset.domain.AssetLifecycleStatus;
import io.infranexum.itam.asset.domain.AssetType;
import io.infranexum.itam.asset.ports.AssetOperationalReadinessPolicy;
import io.infranexum.itam.compliance.application.ComplianceApplicationService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;

/** Enforces the contextual PGM-07-E03 warranty/license gate before operational asset promotion. */
final class ItamComplianceReadinessPolicy implements AssetOperationalReadinessPolicy {
    private final ComplianceApplicationService compliance;
    private final Clock clock;

    ItamComplianceReadinessPolicy(ComplianceApplicationService compliance, @Qualifier("platformClock") Clock clock) {
        this.compliance=Objects.requireNonNull(compliance,"compliance");this.clock=Objects.requireNonNull(clock,"clock");
    }

    @Override
    public void requireReady(Asset asset, AssetLifecycleStatus targetStatus) {
        Objects.requireNonNull(asset,"asset");Objects.requireNonNull(targetStatus,"targetStatus");
        if(!targetStatus.operationalReadinessRequired()) return;
        LocalDate today=LocalDate.now(clock);
        boolean ready=asset.assetType()==AssetType.HARDWARE?compliance.hardwareReady(asset,today):compliance.softwareReady(asset,today);
        if(!ready){
            throw new AssetConflictException(asset.assetType()==AssetType.HARDWARE?"ITAM_ASSET_WARRANTY_REQUIRED":"ITAM_ASSET_LICENSE_REQUIRED",
                    asset.assetType()==AssetType.HARDWARE
                            ?"operational hardware requires verified manufacturer warranty or authorized third-party support coverage"
                            :"operational software requires a verified active license contract");
        }
    }
}
