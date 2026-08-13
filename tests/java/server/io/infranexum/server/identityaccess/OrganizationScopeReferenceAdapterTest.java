package io.infranexum.server.identityaccess;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.organization.domain.Organization;
import io.infranexum.organization.domain.OrganizationCode;
import io.infranexum.organization.domain.OrganizationState;
import io.infranexum.organization.domain.Subdivision;
import io.infranexum.organization.domain.SubdivisionCode;
import io.infranexum.organization.domain.SubdivisionState;
import io.infranexum.organization.domain.SubdivisionType;
import io.infranexum.organization.ports.OrganizationRepository;
import io.infranexum.organization.ports.SubdivisionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Regression tests for weak IAM references resolved through Organization public ports. */
class OrganizationScopeReferenceAdapterTest {
    private static final Instant NOW = Instant.parse("2026-08-13T16:00:00Z");
    private static final DomainIdentifier ORGANIZATION = id(1);
    private static final DomainIdentifier SUBDIVISION = id(2);

    @Test
    void organizationReferenceExistsOnlyForNonDeletedAggregate() {
        MutableOrganizationRepository organizations = new MutableOrganizationRepository();
        EmptySubdivisionRepository subdivisions = new EmptySubdivisionRepository();
        OrganizationScopeReferenceAdapter adapter = new OrganizationScopeReferenceAdapter(organizations, subdivisions);
        organizations.value = Optional.of(organization(OrganizationState.ACTIVE));
        assertTrue(adapter.organizationExists(ORGANIZATION));
        organizations.value = Optional.of(organization(OrganizationState.DELETED));
        assertFalse(adapter.organizationExists(ORGANIZATION));
        organizations.value = Optional.empty();
        assertFalse(adapter.organizationExists(ORGANIZATION));
    }

    @Test
    void subdivisionReferenceIsBoundToItsOrganizationAndExcludesDeletedRows() {
        MutableSubdivisionRepository subdivisions = new MutableSubdivisionRepository();
        OrganizationScopeReferenceAdapter adapter =
                new OrganizationScopeReferenceAdapter(new MutableOrganizationRepository(), subdivisions);
        subdivisions.value = Optional.of(subdivision(SubdivisionState.ACTIVE));
        assertTrue(adapter.subdivisionExists(ORGANIZATION, SUBDIVISION));
        subdivisions.value = Optional.of(subdivision(SubdivisionState.DELETED));
        assertFalse(adapter.subdivisionExists(ORGANIZATION, SUBDIVISION));
        subdivisions.value = Optional.empty();
        assertFalse(adapter.subdivisionExists(ORGANIZATION, SUBDIVISION));
        assertTrue(subdivisions.lastOrganization.equals(ORGANIZATION));
        assertTrue(subdivisions.lastSubdivision.equals(SUBDIVISION));
    }

    @Test
    void adapterRejectsMissingPortsAndIdentifiers() {
        MutableOrganizationRepository organizations = new MutableOrganizationRepository();
        EmptySubdivisionRepository subdivisions = new EmptySubdivisionRepository();
        assertThrows(NullPointerException.class, () -> new OrganizationScopeReferenceAdapter(null, subdivisions));
        assertThrows(NullPointerException.class, () -> new OrganizationScopeReferenceAdapter(organizations, null));
        OrganizationScopeReferenceAdapter adapter = new OrganizationScopeReferenceAdapter(organizations, subdivisions);
        assertThrows(NullPointerException.class, () -> adapter.organizationExists(null));
        assertThrows(NullPointerException.class, () -> adapter.subdivisionExists(null, SUBDIVISION));
        assertThrows(NullPointerException.class, () -> adapter.subdivisionExists(ORGANIZATION, null));
    }

    private static Organization organization(OrganizationState state) {
        return Organization.restore(
                ORGANIZATION, new OrganizationCode("ORG"), "Organization", "Organization SA", "FR", "fr",
                "Europe/Paris", "EUR", null, state, 1, NOW, NOW);
    }

    private static Subdivision subdivision(SubdivisionState state) {
        return Subdivision.restore(
                SUBDIVISION, ORGANIZATION, new SubdivisionCode("SUB"), "Subdivision", null,
                SubdivisionType.DEPARTMENT, state, null, 1, NOW, NOW,
                state == SubdivisionState.DELETED ? NOW : null);
    }

    private static DomainIdentifier id(int seed) {
        return DomainIdentifier.parse("019ffbda-2000-7000-8000-%012x".formatted(seed));
    }

    private static final class MutableOrganizationRepository implements OrganizationRepository {
        private Optional<Organization> value = Optional.empty();
        @Override public long count() { return value.isPresent() ? 1 : 0; }
        @Override public boolean existsByCode(OrganizationCode code) { return false; }
        @Override public Optional<Organization> findById(DomainIdentifier id) { return value; }
        @Override public Optional<Organization> findByCode(OrganizationCode code) { return value; }
        @Override public void insert(Organization organization) { throw new UnsupportedOperationException(); }
        @Override public void update(Organization organization, long expectedVersion) { throw new UnsupportedOperationException(); }
        @Override public List<Organization> search(String query, OrganizationState state, int offset, int limit) { return List.of(); }
    }

    private static class EmptySubdivisionRepository implements SubdivisionRepository {
        @Override public long countByOrganization(DomainIdentifier organizationId) { return 0; }
        @Override public boolean existsCode(DomainIdentifier organizationId, SubdivisionCode code) { return false; }
        @Override public Optional<Subdivision> findById(DomainIdentifier organizationId, DomainIdentifier id) { return Optional.empty(); }
        @Override public void insert(Subdivision subdivision) { throw new UnsupportedOperationException(); }
        @Override public void update(Subdivision subdivision, long expectedVersion) { throw new UnsupportedOperationException(); }
        @Override public List<Subdivision> list(DomainIdentifier organizationId, int offset, int limit) { return List.of(); }
    }

    private static final class MutableSubdivisionRepository extends EmptySubdivisionRepository {
        private Optional<Subdivision> value = Optional.empty();
        private DomainIdentifier lastOrganization;
        private DomainIdentifier lastSubdivision;
        @Override
        public Optional<Subdivision> findById(DomainIdentifier organizationId, DomainIdentifier id) {
            lastOrganization = organizationId;
            lastSubdivision = id;
            return value;
        }
    }
}
