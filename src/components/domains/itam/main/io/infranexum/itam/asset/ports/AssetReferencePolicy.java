package io.infranexum.itam.asset.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.asset.domain.AssetCustodian;
import java.time.LocalDate;

/** Cross-context weak-reference validation port; implementations never write another context. */
public interface AssetReferencePolicy {
    void validateCanonicalObject(DomainIdentifier rsotObjectId, DomainIdentifier organizationId);
    void validateSubdivision(DomainIdentifier organizationId, DomainIdentifier subdivisionId);
    void validateAcquisitionPartner(DomainIdentifier partnerId, DomainIdentifier organizationId, LocalDate effectiveOn);
    void validateCustodian(AssetCustodian custodian, DomainIdentifier organizationId, LocalDate effectiveOn, boolean maintenance);
}
