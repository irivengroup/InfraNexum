package io.infranexum.dcim.facility.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.dcim.facility.application.FacilityPage;
import io.infranexum.dcim.facility.application.FacilitySearchCriteria;
import io.infranexum.dcim.facility.domain.FacilityCode;
import io.infranexum.dcim.facility.domain.FacilityKind;
import io.infranexum.dcim.facility.domain.FacilityNode;
import java.util.Optional;

/** Authoritative persistence port for the DCIM physical hierarchy. */
public interface FacilityRepository {
    long count(FacilityKind kind);
    boolean existsByScopeCode(FacilityKind kind, DomainIdentifier scopeId, FacilityCode code);
    Optional<FacilityNode> findById(DomainIdentifier id);
    long activeBuildingsForSite(DomainIdentifier siteId);
    void insert(FacilityNode node);
    void update(FacilityNode node, long expectedVersion);
    FacilityPage search(FacilitySearchCriteria criteria);
}
