package io.infranexum.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.organization.domain.Organization;
import io.infranexum.organization.domain.OrganizationCode;
import io.infranexum.organization.domain.OrganizationState;
import io.infranexum.organization.domain.OrganizationStateException;
import io.infranexum.organization.domain.ScopeType;
import io.infranexum.organization.domain.Subdivision;
import io.infranexum.organization.domain.SubdivisionCode;
import io.infranexum.organization.domain.SubdivisionState;
import io.infranexum.organization.domain.SubdivisionType;
import io.infranexum.organization.domain.TemporalScope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationDomainTest {
    @Test
    void codeValidationAndLifecycleAreStrict() {
        assertEquals("ACME-EU", new OrganizationCode(" acme-eu ").value());
        assertThrows(IllegalArgumentException.class, () -> new OrganizationCode("x"));

        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        Organization organization = Organization.provisioning(
                id(1),
                new OrganizationCode("ORG-001"),
                "Org One",
                "Organization One",
                "FR",
                "fr-FR",
                "Europe/Paris",
                "EUR",
                null,
                now);
        assertEquals(OrganizationState.PROVISIONING, organization.state());

        organization = organization.activate(now);
        assertEquals(1, organization.version());
        organization = organization.suspend(now.plusSeconds(1));
        assertEquals(OrganizationState.SUSPENDED, organization.state());
        organization = organization.resume(now.plusSeconds(2));
        assertEquals(OrganizationState.ACTIVE, organization.state());

        Organization active = organization;
        assertThrows(
                OrganizationStateException.class,
                () -> active.markDeleted(now.plusSeconds(3)));
    }

    @Test
    void organizationFieldValidationFailsClosed() {
        Instant now = Instant.EPOCH;
        assertThrows(
                IllegalArgumentException.class,
                () -> Organization.provisioning(
                        id(2),
                        new OrganizationCode("ORG-002"),
                        "A",
                        "Legal",
                        "FR",
                        "fr",
                        "Europe/Paris",
                        "EUR",
                        null,
                        now));
        assertThrows(
                IllegalArgumentException.class,
                () -> Organization.provisioning(
                        id(2),
                        new OrganizationCode("ORG-002"),
                        "Valid name",
                        "Legal",
                        "ZZ",
                        "fr",
                        "Europe/Paris",
                        "EUR",
                        null,
                        now));
        assertThrows(
                IllegalArgumentException.class,
                () -> Organization.provisioning(
                        id(2),
                        new OrganizationCode("ORG-002"),
                        "Valid name",
                        "Legal",
                        "FR",
                        "pt",
                        "Europe/Paris",
                        "EUR",
                        null,
                        now));
    }

    @Test
    void temporalScopeUsesHalfOpenValidity() {
        Instant start = Instant.parse("2026-08-12T00:00:00Z");
        assertThrows(
                IllegalArgumentException.class,
                () -> new TemporalScope(
                        id(3), id(2), null, ScopeType.DATA, start, start, 0, start));

        TemporalScope scope = new TemporalScope(
                id(3),
                id(2),
                null,
                ScopeType.DATA,
                start,
                start.plusSeconds(10),
                0,
                start);
        assertTrue(scope.effectiveAt(start));
        assertTrue(scope.effectiveAt(start.plusSeconds(9)));
        assertFalse(scope.effectiveAt(start.plusSeconds(10)));
    }

    @Test
    void subdivisionLifecycleIsStrictAndLogicalDeletionIsTimestamped() {
        Instant now = Instant.EPOCH;
        Subdivision subdivision = Subdivision.active(
                id(4),
                id(2),
                new SubdivisionCode("OPS-EU"),
                "Operations",
                null,
                SubdivisionType.DEPARTMENT,
                null,
                now);
        assertEquals(SubdivisionState.ACTIVE, subdivision.state());

        subdivision = subdivision.deactivate(now.plusSeconds(1))
                .archive(now.plusSeconds(2))
                .delete(now.plusSeconds(3));
        assertNotNull(subdivision.deletedAt());

        Subdivision deleted = subdivision;
        assertThrows(
                IllegalStateException.class,
                () -> deleted.reactivate(now.plusSeconds(4)));
    }

    private static DomainIdentifier id(long suffix) {
        return new DomainIdentifier(new UUID(
                0x0198_0000_0000_7000L + suffix,
                0x8000_0000_0000_0000L + suffix));
    }
}
