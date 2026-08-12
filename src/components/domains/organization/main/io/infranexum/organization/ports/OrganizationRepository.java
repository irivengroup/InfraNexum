package io.infranexum.organization.ports;
import io.infranexum.core.contracts.DomainIdentifier; import io.infranexum.organization.domain.*; import java.util.*;
/** Authoritative persistence port for organization aggregates. */
public interface OrganizationRepository {
    long count(); boolean existsByCode(OrganizationCode code); Optional<Organization> findById(DomainIdentifier id); Optional<Organization> findByCode(OrganizationCode code);
    void insert(Organization organization); void update(Organization organization,long expectedVersion); List<Organization> search(String query,OrganizationState state,int offset,int limit);
}
