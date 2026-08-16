package io.infranexum.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.organization.application.CreateOrganizationCommand;
import io.infranexum.organization.application.CreateSubdivisionCommand;
import io.infranexum.organization.application.CreateTemporalScopeCommand;
import io.infranexum.organization.application.OrganizationCommandContext;
import io.infranexum.organization.domain.Organization;
import io.infranexum.organization.domain.OrganizationCode;
import io.infranexum.organization.domain.OrganizationConflictException;
import io.infranexum.organization.domain.OrganizationNotFoundException;
import io.infranexum.organization.domain.OrganizationQuotaException;
import io.infranexum.organization.domain.OrganizationState;
import io.infranexum.organization.domain.OrganizationStateException;
import io.infranexum.organization.domain.ScopeType;
import io.infranexum.organization.domain.Subdivision;
import io.infranexum.organization.domain.SubdivisionCode;
import io.infranexum.organization.domain.SubdivisionState;
import io.infranexum.organization.domain.SubdivisionType;
import io.infranexum.organization.domain.TemporalScope;
import io.infranexum.organization.ports.IdempotencyRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Regression coverage for fail-closed Organization application and value-object branches. */
class OrganizationCoverageRegressionTest {
    @Test
    void organizationCreationCoversHierarchyConflictsAndReplayCorruption() {
        var fixture = new OrganizationApplicationServiceTest.ServiceFixture();
        Organization root = fixture.service.createOrganization(org("ROOT-01", null), fixture.context("org-root"));

        fixture.features.organizationLimit = 20;
        fixture.features.hierarchySupported = false;
        assertThrows(OrganizationConflictException.class, () -> fixture.service.createOrganization(
                org("CHILD-01", root.id()), fixture.context("org-child-disabled")));

        fixture.features.hierarchySupported = true;
        assertThrows(OrganizationNotFoundException.class, () -> fixture.service.createOrganization(
                org("CHILD-02", id(700)), fixture.context("org-child-missing")));

        Organization child = fixture.service.createOrganization(
                org("CHILD-03", root.id()), fixture.context("org-child-ok"));
        assertEquals(root.id(), child.parentOrganizationId());

        assertThrows(OrganizationConflictException.class, () -> fixture.service.createOrganization(
                org("ROOT-01", null), fixture.context("org-duplicate-code")));

        fixture.dedup.values.put("org-corrupt-type", new IdempotencyRepository.Record(
                "org-corrupt-type", "bad", "scope", root.id(), fixture.clock.instant()));
        assertThrows(OrganizationConflictException.class, () -> fixture.service.createOrganization(
                org("CORRUPT-01", null), fixture.context("org-corrupt-type")));

        fixture.dedup.values.put("org-missing-resource", new IdempotencyRepository.Record(
                "org-missing-resource", shaForCreateOrganization(org("MISSING-01", null)),
                "organization", id(701), fixture.clock.instant()));
        assertThrows(OrganizationNotFoundException.class, () -> fixture.service.createOrganization(
                org("MISSING-01", null), fixture.context("org-missing-resource")));
    }

    @Test
    void subdivisionCreationCoversOwnerStateQuotaCodeParentCycleAndReplayFailures() {
        var fixture = new OrganizationApplicationServiceTest.ServiceFixture();
        Organization owner = fixture.service.createOrganization(org("OWNER-01", null), fixture.context("owner-001"));

        fixture.features.hierarchyDepth = 0;
        assertThrows(OrganizationQuotaException.class, () -> fixture.service.createSubdivision(
                subdivision(owner.id(), "SUB-000", null), fixture.context("sub-depth-zero")));
        fixture.features.hierarchyDepth = 10;

        Subdivision root = fixture.service.createSubdivision(
                subdivision(owner.id(), "SUB-001", null), fixture.context("sub-root"));
        assertThrows(OrganizationConflictException.class, () -> fixture.service.createSubdivision(
                subdivision(owner.id(), "SUB-001", null), fixture.context("sub-duplicate")));

        fixture.features.subdivisionLimit = 1;
        assertThrows(OrganizationQuotaException.class, () -> fixture.service.createSubdivision(
                subdivision(owner.id(), "SUB-002", null), fixture.context("sub-limit")));
        fixture.features.subdivisionLimit = 20;

        assertThrows(OrganizationNotFoundException.class, () -> fixture.service.createSubdivision(
                subdivision(owner.id(), "SUB-003", id(702)), fixture.context("sub-parent-missing")));

        DomainIdentifier cycleAId = id(720);
        DomainIdentifier cycleBId = id(721);
        Subdivision cycleA = Subdivision.restore(
                cycleAId, owner.id(), new SubdivisionCode("CYCLE-A"), "Cycle A", null,
                SubdivisionType.DEPARTMENT, SubdivisionState.ACTIVE, cycleBId, 0,
                fixture.clock.instant(), fixture.clock.instant(), null);
        Subdivision cycleB = Subdivision.restore(
                cycleBId, owner.id(), new SubdivisionCode("CYCLE-B"), "Cycle B", null,
                SubdivisionType.DEPARTMENT, SubdivisionState.ACTIVE, cycleAId, 0,
                fixture.clock.instant(), fixture.clock.instant(), null);
        fixture.subdivisions.values.put(cycleAId, cycleA);
        fixture.subdivisions.values.put(cycleBId, cycleB);
        assertThrows(OrganizationConflictException.class, () -> fixture.service.createSubdivision(
                subdivision(owner.id(), "SUB-004", cycleAId), fixture.context("sub-cycle")));

        Organization suspended = fixture.service.suspend(owner.id(), owner.version(), fixture.context("suspend-owner"));
        assertThrows(OrganizationConflictException.class, () -> fixture.service.createSubdivision(
                subdivision(suspended.id(), "SUB-005", null), fixture.context("sub-owner-suspended")));

        fixture.dedup.values.put("sub-corrupt", new IdempotencyRepository.Record(
                "sub-corrupt", "invalid", "subdivision", root.id(), fixture.clock.instant()));
        assertThrows(OrganizationConflictException.class, () -> fixture.service.createSubdivision(
                subdivision(owner.id(), "SUB-006", null), fixture.context("sub-corrupt")));
    }

    @Test
    void temporalScopeCoversSubdivisionValidationInactiveOwnerAndReplayFailures() {
        var fixture = new OrganizationApplicationServiceTest.ServiceFixture();
        Organization owner = fixture.service.createOrganization(org("SCOPE-01", null), fixture.context("scope-owner"));
        Instant start = fixture.clock.instant();

        assertThrows(OrganizationNotFoundException.class, () -> fixture.service.createTemporalScope(
                new CreateTemporalScopeCommand(owner.id(), id(703), "data", start, null),
                fixture.context("scope-missing-sub")));

        Subdivision subdivision = fixture.service.createSubdivision(
                subdivision(owner.id(), "SCOPE-SUB", null), fixture.context("scope-sub"));
        TemporalScope openEnded = fixture.service.createTemporalScope(
                new CreateTemporalScopeCommand(owner.id(), subdivision.id(), "administrative", start, null),
                fixture.context("scope-open"));
        assertTrue(openEnded.effectiveAt(start.plusSeconds(10)));

        Organization suspended = fixture.service.suspend(owner.id(), owner.version(), fixture.context("scope-suspend"));
        assertThrows(OrganizationConflictException.class, () -> fixture.service.createTemporalScope(
                new CreateTemporalScopeCommand(suspended.id(), null, "data", start, null),
                fixture.context("scope-inactive")));

        fixture.dedup.values.put("scope-corrupt", new IdempotencyRepository.Record(
                "scope-corrupt", "invalid", "scope", openEnded.id(), fixture.clock.instant()));
        assertThrows(OrganizationConflictException.class, () -> fixture.service.createTemporalScope(
                new CreateTemporalScopeCommand(owner.id(), subdivision.id(), "administrative", start, null),
                fixture.context("scope-corrupt")));
    }

    @Test
    void queriesAndTransitionsCoverValidationNotFoundVersionStateAndReplayMismatch() {
        var fixture = new OrganizationApplicationServiceTest.ServiceFixture();
        Organization owner = fixture.service.createOrganization(org("QUERY-01", null), fixture.context("query-owner"));
        fixture.service.createSubdivision(subdivision(owner.id(), "QUERY-SUB", null), fixture.context("query-sub"));

        assertEquals(owner.id(), fixture.service.getOrganization(owner.id()).id());
        assertThrows(OrganizationNotFoundException.class, () -> fixture.service.getOrganization(id(704)));
        assertEquals(1, fixture.service.searchOrganizations("QUERY", OrganizationState.ACTIVE, 0, 10).size());
        assertEquals(1, fixture.service.listSubdivisions(owner.id(), 0, 10).size());
        assertEquals(0, fixture.service.effectiveScopes(owner.id(), fixture.clock.instant()).size());

        assertThrows(IllegalArgumentException.class, () -> fixture.service.searchOrganizations(null, null, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.searchOrganizations(null, null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.searchOrganizations(null, null, 0, 201));
        assertThrows(OrganizationNotFoundException.class, () -> fixture.service.listSubdivisions(id(705), 0, 10));
        assertThrows(NullPointerException.class, () -> fixture.service.effectiveScopes(owner.id(), null));

        assertThrows(IllegalArgumentException.class, () -> fixture.service.suspend(owner.id(), -1, fixture.context("bad-version")));
        assertThrows(OrganizationConflictException.class, () -> fixture.service.suspend(owner.id(), 99, fixture.context("stale-version")));

        Organization suspended = fixture.service.suspend(owner.id(), owner.version(), fixture.context("suspend-ok"));
        assertThrows(OrganizationStateException.class, () -> fixture.service.suspend(
                suspended.id(), suspended.version(), fixture.context("suspend-twice")));
        Organization resumed = fixture.service.resume(suspended.id(), suspended.version(), fixture.context("resume-ok"));
        assertEquals(OrganizationState.ACTIVE, resumed.state());
        assertThrows(OrganizationStateException.class, () -> fixture.service.resume(
                resumed.id(), resumed.version(), fixture.context("resume-twice")));

        fixture.dedup.values.put("transition-wrong-id", new IdempotencyRepository.Record(
                "transition-wrong-id", "irrelevant", "organization-transition", id(706), fixture.clock.instant()));
        assertThrows(OrganizationConflictException.class, () -> fixture.service.suspend(
                resumed.id(), resumed.version(), fixture.context("transition-wrong-id")));
    }

    @Test
    void domainValueObjectsCoverNullBlankRangesParsingAndLifecycleAlternatives() {
        Instant now = Instant.parse("2026-08-16T12:00:00Z");
        assertThrows(NullPointerException.class, () -> new OrganizationCode(null));
        assertThrows(IllegalArgumentException.class, () -> new OrganizationCode("!"));
        assertThrows(IllegalArgumentException.class, () -> new OrganizationCode("A".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> new SubdivisionCode("x"));
        assertThrows(IllegalArgumentException.class, () -> SubdivisionType.parse("unknown"));
        assertThrows(IllegalArgumentException.class, () -> ScopeType.parse("unknown"));
        assertThrows(NullPointerException.class, () -> ScopeType.parse(null));

        Organization provisioning = Organization.provisioning(
                id(710), new OrganizationCode("ORG-710"), "Display name", "Legal name", "FR", "en-US",
                "UTC", "USD", null, now);
        Organization deletionPending = provisioning.requestDeletion(now.plusSeconds(1));
        Organization deleted = deletionPending.markDeleted(now.plusSeconds(2));
        assertEquals(OrganizationState.DELETED, deleted.state());
        assertThrows(OrganizationStateException.class, () -> deleted.activate(now.plusSeconds(3)));

        Subdivision active = Subdivision.active(
                id(711), id(710), new SubdivisionCode("SUB-710"), "Subdivision", "description",
                SubdivisionType.SITE, null, now);
        assertThrows(IllegalStateException.class, () -> active.archive(now));
        Subdivision inactive = active.deactivate(now.plusSeconds(1));
        Subdivision reactivated = inactive.reactivate(now.plusSeconds(2));
        assertEquals(SubdivisionState.ACTIVE, reactivated.state());
        assertThrows(IllegalStateException.class, () -> reactivated.reactivate(now.plusSeconds(3)));

        assertThrows(NullPointerException.class, () -> new TemporalScope(
                id(712), id(710), null, ScopeType.DATA, null, null, 0, now));
        assertThrows(IllegalArgumentException.class, () -> new TemporalScope(
                id(712), id(710), null, ScopeType.DATA, now, now.minusSeconds(1), 0, now));
        TemporalScope closed = new TemporalScope(
                id(712), id(710), null, ScopeType.DATA, now, now.plusSeconds(1), 0, now);
        assertTrue(!closed.effectiveAt(now.minusSeconds(1)) && !closed.effectiveAt(now.plusSeconds(1)));
    }

    private static CreateOrganizationCommand org(String code, DomainIdentifier parent) {
        return new CreateOrganizationCommand(
                code, "Organization " + code, "Legal " + code, "FR", "fr", "Europe/Paris", "EUR", parent);
    }

    private static CreateSubdivisionCommand subdivision(
            DomainIdentifier organizationId, String code, DomainIdentifier parent) {
        return new CreateSubdivisionCommand(organizationId, code, "Subdivision " + code, "description", "department", parent);
    }

    private static DomainIdentifier id(long suffix) {
        return new DomainIdentifier(new UUID(0x0198_0000_0000_7000L + suffix, 0x8000_0000_0000_0000L + suffix));
    }

    /* Mirrors the production canonical fingerprint only for the intentionally corrupted replay fixture. */
    private static String shaForCreateOrganization(CreateOrganizationCommand command) {
        try {
            StringBuilder canonical = new StringBuilder();
            Object[] values = {"create-organization", new OrganizationCode(command.code()).value(), command.displayName(),
                    command.legalName(), command.countryCode(), command.defaultLanguage(), command.timezone(),
                    command.currency(), command.parentOrganizationId()};
            for (Object value : values) {
                String text = value == null ? "<null>" : value.toString();
                canonical.append(text.length()).append(':').append(text).append(';');
            }
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
