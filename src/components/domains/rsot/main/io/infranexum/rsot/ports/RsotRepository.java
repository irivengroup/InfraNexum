package io.infranexum.rsot.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.rsot.domain.AttributeAuthorityPolicy;
import io.infranexum.rsot.domain.AuthorityMatrixEntry;
import io.infranexum.rsot.domain.CanonicalObject;
import io.infranexum.rsot.domain.ContextRelationship;
import java.util.List;
import java.util.Optional;

/** RSOT-owned persistence port for the PGM-06-E01 canonical/authority foundation. */
public interface RsotRepository {
    Optional<CanonicalObject> findCanonicalObject(DomainIdentifier canonicalId);

    List<CanonicalObject> listCanonicalObjects(int offset, int limit);

    List<CanonicalObject> listCanonicalObjects(DomainIdentifier organizationId, int offset, int limit);

    List<AttributeAuthorityPolicy> authorityPolicies();

    List<AuthorityMatrixEntry> authorityMatrix();

    List<ContextRelationship> contextMap();
}
