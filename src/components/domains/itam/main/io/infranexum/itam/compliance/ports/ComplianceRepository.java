package io.infranexum.itam.compliance.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.compliance.domain.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Durable ITAM compliance authority including immutable version snapshots and alert deduplication. */
public interface ComplianceRepository {
    long contractRecordCount();
    Optional<Warranty> findWarranty(DomainIdentifier id); List<Warranty> warrantiesForAsset(DomainIdentifier assetId);
    List<Warranty> warrantyPage(DomainIdentifier assetId,DomainIdentifier afterId,int limit);
    void insertWarranty(Warranty warranty); void updateWarranty(Warranty warranty,long expectedVersion);
    Optional<SoftwareLicenseContract> findLicense(DomainIdentifier id); List<SoftwareLicenseContract> licensesForAsset(DomainIdentifier assetId);
    List<SoftwareLicenseContract> licensePage(DomainIdentifier assetId,DomainIdentifier afterId,int limit);
    void insertLicense(SoftwareLicenseContract license); void updateLicense(SoftwareLicenseContract license,long expectedVersion);
    Optional<SupportProviderAuthorization> findSupportAuthorization(DomainIdentifier id);
    List<SupportProviderAuthorization> supportAuthorizations(DomainIdentifier organizationId);
    Optional<SupportProviderAuthorization> findActiveSupportAuthorization(DomainIdentifier providerId,DomainIdentifier organizationId,LocalDate effectiveOn);
    void insertSupportAuthorization(SupportProviderAuthorization authorization); void updateSupportAuthorization(SupportProviderAuthorization authorization,long expectedVersion);
    Optional<SupportCoverage> findSupportCoverage(DomainIdentifier id); List<SupportCoverage> supportCoveragesForAsset(DomainIdentifier assetId);
    List<SupportCoverage> supportCoveragePage(DomainIdentifier assetId,DomainIdentifier afterId,int limit);
    List<SupportCoverage> supportCoveragesForAuthorization(DomainIdentifier authorizationId);
    void insertSupportCoverage(SupportCoverage coverage); void updateSupportCoverage(SupportCoverage coverage,long expectedVersion);
    Optional<WarrantyType> findWarrantyType(DomainIdentifier id); List<WarrantyType> warrantyTypes(boolean activeOnly); void insertWarrantyType(WarrantyType type);
    List<Warranty> warrantiesDueBetween(LocalDate start,LocalDate end);
    List<SoftwareLicenseContract> licensesDueBetween(LocalDate start,LocalDate end);
    List<SupportCoverage> supportCoveragesDueBetween(LocalDate start,LocalDate end);
    boolean reserveAlert(ComplianceAlert alert,LocalDate emittedOn);
    List<ComplianceRevision> revisions(String recordType,DomainIdentifier recordId,long afterVersion,int limit);
}
