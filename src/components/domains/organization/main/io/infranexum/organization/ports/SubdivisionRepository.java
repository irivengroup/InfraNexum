package io.infranexum.organization.ports;
import io.infranexum.core.contracts.DomainIdentifier; import io.infranexum.organization.domain.*; import java.util.*;
/** Authoritative persistence port for organization-local subdivisions. */
public interface SubdivisionRepository { long countByOrganization(DomainIdentifier organizationId); boolean existsCode(DomainIdentifier organizationId,SubdivisionCode code); Optional<Subdivision> findById(DomainIdentifier organizationId,DomainIdentifier id); void insert(Subdivision subdivision); void update(Subdivision subdivision,long expectedVersion); List<Subdivision> list(DomainIdentifier organizationId,int offset,int limit); }
