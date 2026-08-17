package io.infranexum.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.organization.application.OrganizationCommandContext;
import io.infranexum.organization.domain.Organization;
import io.infranexum.organization.domain.OrganizationCode;
import io.infranexum.organization.domain.OrganizationConflictException;
import io.infranexum.organization.domain.OrganizationQuotaException;
import io.infranexum.organization.domain.OrganizationState;
import io.infranexum.organization.domain.ScopeType;
import io.infranexum.organization.domain.Subdivision;
import io.infranexum.organization.domain.SubdivisionCode;
import io.infranexum.organization.domain.SubdivisionState;
import io.infranexum.organization.domain.SubdivisionType;
import io.infranexum.organization.domain.TemporalScope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Saturates public organization value-object and lifecycle boundaries used by hosted JaCoCo gates. */
final class OrganizationBoundarySaturationTest {
    private static final Instant NOW = Instant.parse("2026-08-16T20:00:00Z");
    private static final DomainIdentifier ID = id(1);
    private static final DomainIdentifier ORG = id(2);

    @Test
    void organizationLifecycleValidationAndScalarBoundsAreExplicit() {
        Organization base = Organization.provisioning(ID, new OrganizationCode("ORG-001"), "Display", "Legal name",
                "FR", "fr", "Europe/Paris", "EUR", null, NOW);
        assertEquals(0, base.version());
        assertNull(base.parentOrganizationId());
        assertThrows(IllegalArgumentException.class, () -> Organization.restore(ID, new OrganizationCode("ORG-001"),
                "Display", "Legal name", "FR", "fr", "UTC", "EUR", null,
                OrganizationState.ACTIVE, -1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> Organization.restore(ID, new OrganizationCode("ORG-001"),
                "Display", "Legal name", "FR", "fr", "UTC", "EUR", null,
                OrganizationState.ACTIVE, 1, NOW, NOW.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> Organization.provisioning(ID, new OrganizationCode("ORG-001"),
                "Display", "Legal name", "FR", "fr", "UTC", "EUR", ID, NOW));
        assertThrows(NullPointerException.class, () -> Organization.provisioning(ID, new OrganizationCode("ORG-001"),
                null, "Legal name", "FR", "fr", "UTC", "EUR", null, NOW));
        assertThrows(IllegalArgumentException.class, () -> Organization.provisioning(ID, new OrganizationCode("ORG-001"),
                "x", "Legal name", "FR", "fr", "UTC", "EUR", null, NOW));
        assertThrows(IllegalArgumentException.class, () -> Organization.provisioning(ID, new OrganizationCode("ORG-001"),
                "x".repeat(161), "Legal name", "FR", "fr", "UTC", "EUR", null, NOW));
        assertThrows(IllegalArgumentException.class, () -> Organization.provisioning(ID, new OrganizationCode("ORG-001"),
                "Bad\nName", "Legal name", "FR", "fr", "UTC", "EUR", null, NOW));
        assertThrows(IllegalArgumentException.class, () -> Organization.provisioning(ID, new OrganizationCode("ORG-001"),
                "Display", "Legal name", "ZZ", "fr", "UTC", "EUR", null, NOW));
        assertThrows(IllegalArgumentException.class, () -> Organization.provisioning(ID, new OrganizationCode("ORG-001"),
                "Display", "Legal name", "F", "fr", "UTC", "EUR", null, NOW));
        assertThrows(IllegalArgumentException.class, () -> Organization.provisioning(ID, new OrganizationCode("ORG-001"),
                "Display", "Legal name", "FR", "xx", "UTC", "EUR", null, NOW));
        assertThrows(IllegalArgumentException.class, () -> Organization.provisioning(ID, new OrganizationCode("ORG-001"),
                "Display", "Legal name", "FR", "   ", "UTC", "EUR", null, NOW));
        assertThrows(RuntimeException.class, () -> Organization.provisioning(ID, new OrganizationCode("ORG-001"),
                "Display", "Legal name", "FR", "fr", "No/Such_Zone", "EUR", null, NOW));
        assertThrows(IllegalArgumentException.class, () -> Organization.provisioning(ID, new OrganizationCode("ORG-001"),
                "Display", "Legal name", "FR", "fr", "UTC", "ZZZ", null, NOW));

        Organization active = base.activate(NOW.plusSeconds(1));
        Organization suspended = active.suspend(NOW.plusSeconds(2));
        Organization resumed = suspended.resume(NOW.plusSeconds(3));
        Organization archiving = resumed.beginArchiving(NOW.plusSeconds(4));
        Organization archived = archiving.completeArchiving(NOW.plusSeconds(5));
        Organization pending = archived.requestDeletion(NOW.plusSeconds(6));
        Organization deleted = pending.markDeleted(NOW.plusSeconds(7));
        assertEquals(OrganizationState.DELETED, deleted.state());
        assertThrows(IllegalArgumentException.class, () -> active.suspend(NOW));
        assertFalse(OrganizationState.DELETED.canTransitionTo(OrganizationState.ACTIVE));
        assertFalse(OrganizationState.ACTIVE.canTransitionTo(null));
        assertTrue(OrganizationState.PROVISIONING.canTransitionTo(OrganizationState.DELETION_PENDING));
    }

    @Test
    void subdivisionStatesRestoreValidationAndTextBranchesAreBounded() {
        Subdivision active = Subdivision.active(ID, ORG, new SubdivisionCode("SUB-001"), "Subdivision", null,
                SubdivisionType.DEPARTMENT, null, NOW);
        assertNull(active.description());
        assertNull(active.parentSubdivisionId());
        assertFalse(SubdivisionState.DELETED.canTransitionTo(SubdivisionState.ACTIVE));
        assertFalse(SubdivisionState.ACTIVE.canTransitionTo(null));
        assertTrue(SubdivisionState.INACTIVE.canTransitionTo(SubdivisionState.ARCHIVED));
        assertThrows(IllegalArgumentException.class, () -> Subdivision.active(ID, ORG, new SubdivisionCode("SUB-001"),
                "Subdivision", null, SubdivisionType.DEPARTMENT, ID, NOW));
        assertThrows(IllegalArgumentException.class, () -> Subdivision.restore(ID, ORG, new SubdivisionCode("SUB-001"),
                "Subdivision", null, SubdivisionType.DEPARTMENT, SubdivisionState.ACTIVE, null, -1, NOW, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> Subdivision.restore(ID, ORG, new SubdivisionCode("SUB-001"),
                "Subdivision", null, SubdivisionType.DEPARTMENT, SubdivisionState.ACTIVE, null, 1, NOW, NOW.minusSeconds(1), null));
        assertThrows(IllegalArgumentException.class, () -> Subdivision.restore(ID, ORG, new SubdivisionCode("SUB-001"),
                "Subdivision", null, SubdivisionType.DEPARTMENT, SubdivisionState.DELETED, null, 1, NOW, NOW, null));
        Subdivision deleted = Subdivision.restore(ID, ORG, new SubdivisionCode("SUB-001"), "Subdivision", "desc",
                SubdivisionType.DEPARTMENT, SubdivisionState.DELETED, null, 2, NOW, NOW.plusSeconds(2), NOW.plusSeconds(2));
        assertEquals(SubdivisionState.DELETED, deleted.state());
        assertThrows(IllegalArgumentException.class, () -> active.deactivate(NOW.minusSeconds(1)));
        assertThrows(NullPointerException.class, () -> active.deactivate(null));
        assertThrows(NullPointerException.class, () -> Subdivision.active(ID, ORG, new SubdivisionCode("SUB-001"),
                null, null, SubdivisionType.DEPARTMENT, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> Subdivision.active(ID, ORG, new SubdivisionCode("SUB-001"),
                "x", null, SubdivisionType.DEPARTMENT, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> Subdivision.active(ID, ORG, new SubdivisionCode("SUB-001"),
                "x".repeat(161), null, SubdivisionType.DEPARTMENT, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> Subdivision.active(ID, ORG, new SubdivisionCode("SUB-001"),
                "Bad\nName", null, SubdivisionType.DEPARTMENT, null, NOW));
        Subdivision blankDescription = Subdivision.active(id(3), ORG, new SubdivisionCode("SUB-002"), "Subdivision", "   ",
                SubdivisionType.SITE, null, NOW);
        assertNull(blankDescription.description());
        assertThrows(IllegalArgumentException.class, () -> Subdivision.active(id(4), ORG, new SubdivisionCode("SUB-003"),
                "Subdivision", "x".repeat(4001), SubdivisionType.SITE, null, NOW));
    }

    @Test
    void temporalScopeParsingExceptionsAndCommandContextCoverBothSidesOfGuards() {
        TemporalScope open = new TemporalScope(ID, ORG, null, ScopeType.DATA, NOW, null, 0, NOW);
        assertTrue(open.effectiveAt(NOW));
        assertTrue(open.effectiveAt(NOW.plusSeconds(100)));
        assertFalse(open.effectiveAt(NOW.minusNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> new TemporalScope(ID, ORG, null, ScopeType.DATA,
                NOW, NOW, 0, NOW));
        assertThrows(IllegalArgumentException.class, () -> new TemporalScope(ID, ORG, null, ScopeType.DATA,
                NOW, null, -1, NOW));
        assertThrows(IllegalArgumentException.class, () -> ScopeType.parse("   "));
        assertEquals("data", ScopeType.DATA.wireValue());

        OrganizationConflictException conflict = new OrganizationConflictException("CONFLICT", "message");
        assertEquals("CONFLICT", conflict.code());
        assertThrows(IllegalArgumentException.class, () -> new OrganizationConflictException(" ", "message"));
        OrganizationQuotaException quota = new OrganizationQuotaException("org.limit");
        assertEquals("org.limit", quota.quotaKey());
        assertThrows(IllegalArgumentException.class, () -> new OrganizationQuotaException(" "));

        OrganizationCommandContext simple = new OrganizationCommandContext("actor", ID, "idem-key", null);
        assertNull(simple.reason());
        OrganizationCommandContext normalized = new OrganizationCommandContext(" actor@example ", ID, " idem-key-2 ", " reason ");
        assertEquals("actor@example", normalized.actorId());
        assertEquals("reason", normalized.reason());
        assertThrows(IllegalArgumentException.class, () -> new OrganizationCommandContext(" ", ID, "idem-key", null));
        assertThrows(IllegalArgumentException.class, () -> new OrganizationCommandContext("actor", ID, "short", null));
        assertThrows(IllegalArgumentException.class, () -> new OrganizationCommandContext("actor", ID, "idem-key-3", " "));
        assertThrows(IllegalArgumentException.class, () -> new OrganizationCommandContext("actor", ID, "idem-key-4", "x".repeat(513)));
        assertThrows(IllegalArgumentException.class, () -> new OrganizationCommandContext("actor", ID, "idem-key-5", "bad\nreason"));
    }

    private static DomainIdentifier id(long suffix) {
        return new DomainIdentifier(new UUID(0x0198_1000_0000_7000L + suffix, 0x8000_0000_0000_0000L + suffix));
    }
}
