package io.infranexum.itam.partner.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.partner.application.PartnerPage;
import io.infranexum.itam.partner.application.PartnerSearchCriteria;
import io.infranexum.itam.partner.domain.Partner;
import io.infranexum.itam.partner.domain.PartnerCode;
import java.util.Optional;
import java.util.Set;

/** Authoritative ITAM persistence port for Partner aggregates and their governed details. */
public interface PartnerRepository {
    long count();
    boolean existsByCode(DomainIdentifier governingOrganizationId, PartnerCode code);
    boolean hasIdentityTokenCollision(DomainIdentifier governingOrganizationId, Set<String> identityTokens);
    Optional<Partner> findById(DomainIdentifier id);
    void insert(Partner partner);
    void updateLifecycle(Partner partner, long expectedVersion);
    PartnerPage search(PartnerSearchCriteria criteria);
}
