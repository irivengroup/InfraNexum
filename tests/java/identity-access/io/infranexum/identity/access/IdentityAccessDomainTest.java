package io.infranexum.identity.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.application.AuthorizationDecision;
import io.infranexum.identity.access.application.IdentityAccessCommandContext;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.AssignmentActorType;
import io.infranexum.identity.access.domain.IdentityAccessException;
import io.infranexum.identity.access.domain.IdentityGroup;
import io.infranexum.identity.access.domain.IdentityUser;
import io.infranexum.identity.access.domain.IdentityUserStatus;
import io.infranexum.identity.access.domain.Permission;
import io.infranexum.identity.access.domain.Role;
import io.infranexum.identity.access.domain.RoleAssignment;
import io.infranexum.identity.access.domain.ScopeKind;
import io.infranexum.identity.access.domain.UserMembership;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityAccessDomainTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Test
    void authorizationScopesValidateAndCoverOnlyCompatibleTargets() {
        AuthorizationScope platform = AuthorizationScope.platform();
        AuthorizationScope organization = AuthorizationScope.organization(id(1));
        AuthorizationScope subdivision = AuthorizationScope.subdivision(id(1), id(2));
        AuthorizationScope otherSubdivision = AuthorizationScope.subdivision(id(1), id(3));
        assertTrue(platform.covers(platform));
        assertTrue(platform.covers(subdivision));
        assertTrue(organization.covers(organization));
        assertTrue(organization.covers(subdivision));
        assertTrue(subdivision.covers(subdivision));
        assertFalse(subdivision.covers(otherSubdivision));
        assertFalse(organization.covers(AuthorizationScope.organization(id(9))));
        assertFalse(organization.covers(platform));
        assertThrows(NullPointerException.class, () -> platform.covers(null));
        assertThrows(IllegalArgumentException.class, () -> new AuthorizationScope(ScopeKind.PLATFORM, id(1), null));
        assertThrows(NullPointerException.class, () -> new AuthorizationScope(ScopeKind.ORGANIZATION, null, null));
        assertThrows(IllegalArgumentException.class, () -> new AuthorizationScope(ScopeKind.ORGANIZATION, id(1), id(2)));
        assertThrows(NullPointerException.class, () -> new AuthorizationScope(ScopeKind.SUBDIVISION, id(1), null));
    }

    @Test
    void userIdentityNormalizesFieldsAndEnforcesSoftDeleteLifecycle() {
        IdentityUser user = IdentityUser.pending(id(10), " Admin.User ", " Admin@Example.COM ", " Administrator ", NOW);
        assertEquals("admin.user", user.login());
        assertEquals("admin@example.com", user.email());
        assertEquals("Administrator", user.displayName());
        assertEquals(IdentityUserStatus.PENDING, user.status());
        user = user.activate(NOW.plusSeconds(1)).updateProfile(null, "Platform Admin", NOW.plusSeconds(2));
        assertNull(user.email());
        user = user.suspend(NOW.plusSeconds(3));
        assertEquals(IdentityUserStatus.SUSPENDED, user.status());
        IdentityUser deleted = user.delete(NOW.plusSeconds(4));
        assertEquals(IdentityUserStatus.DELETED, deleted.status());
        assertThrows(IdentityAccessException.class, () -> deleted.updateProfile(null, "No", NOW.plusSeconds(5)));
        assertThrows(IdentityAccessException.class, () -> deleted.activate(NOW.plusSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> IdentityUser.pending(id(11), "x", null, "Valid", NOW));
        assertThrows(IllegalArgumentException.class, () -> IdentityUser.pending(id(11), "valid-user", "invalid", "Valid", NOW));
        assertThrows(IllegalArgumentException.class, () -> IdentityUser.pending(id(11), "valid-user", null, " \n ", NOW));
        assertThrows(IllegalArgumentException.class, () -> new IdentityUser(id(12), "valid-user", null, "Valid", IdentityUserStatus.DELETED, NOW, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> new IdentityUser(id(12), "valid-user", null, "Valid", IdentityUserStatus.ACTIVE, NOW, NOW, NOW));
    }

    @Test
    void groupsProtectSystemAndDeletedStates() {
        IdentityGroup group = new IdentityGroup(id(20), id(1), " Ops.Team ", " Operations ", false, NOW, NOW, null);
        assertEquals("ops.team", group.code());
        assertEquals("Renamed", group.rename("Renamed", NOW.plusSeconds(1)).displayName());
        IdentityGroup deleted = group.delete(NOW.plusSeconds(2));
        assertTrue(deleted.deleted());
        assertEquals(deleted, deleted.delete(NOW.plusSeconds(3)));
        assertThrows(IdentityAccessException.class, () -> deleted.rename("No", NOW.plusSeconds(4)));
        IdentityGroup system = new IdentityGroup(id(21), id(1), "system.group", "System", true, NOW, NOW, null);
        assertThrows(IdentityAccessException.class, () -> system.delete(NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new IdentityGroup(id(22), id(1), "x", "Group", false, NOW, NOW, null));
    }

    @Test
    void permissionsNormalizeTokensAndProtectSystemDefinitions() {
        Permission permission = new Permission(id(30), id(1), " App.Read ", " Device_Type ", " Read_One ", " critical ", ScopeKind.ORGANIZATION, false, true, NOW, NOW, null);
        assertEquals("app.read", permission.code());
        assertEquals("device_type", permission.resourceType());
        assertEquals("read_one", permission.action());
        assertEquals("CRITICAL", permission.sensitivity());
        Permission updated = permission.update("asset", "write", "elevated", ScopeKind.SUBDIVISION, false, NOW.plusSeconds(1));
        assertFalse(updated.active());
        Permission deleted = updated.delete(NOW.plusSeconds(2));
        assertTrue(deleted.deleted());
        assertEquals(deleted, deleted.delete(NOW.plusSeconds(3)));
        assertThrows(IdentityAccessException.class, () -> deleted.update("asset", "read", "normal", ScopeKind.ORGANIZATION, true, NOW));
        Permission system = new Permission(id(31), null, "iam.user.read", "iam_user", "read", "normal", ScopeKind.PLATFORM, true, true, NOW, NOW, null);
        assertThrows(IdentityAccessException.class, () -> system.update("iam_user", "write", "normal", ScopeKind.PLATFORM, true, NOW));
        assertThrows(IdentityAccessException.class, () -> system.delete(NOW));
        assertThrows(IllegalArgumentException.class, () -> new Permission(id(32), id(1), "bad", "asset", "read", "normal", ScopeKind.ORGANIZATION, false, true, NOW, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> new Permission(id(32), id(1), "app.read", "X", "read", "normal", ScopeKind.ORGANIZATION, false, true, NOW, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> new Permission(id(32), id(1), "app.read", "asset", "X", "normal", ScopeKind.ORGANIZATION, false, true, NOW, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> new Permission(id(32), id(1), "app.read", "asset", "read", "X", ScopeKind.ORGANIZATION, false, true, NOW, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> new Permission(id(33), id(1), "iam.user.read", "iam_user", "read", "normal", ScopeKind.PLATFORM, true, true, NOW, NOW, null));
    }

    @Test
    void rolesProtectSystemCodeAndDeletion() {
        Role role = new Role(id(40), id(1), " Ops.Reader ", " Reader ", ScopeKind.ORGANIZATION, false, true, NOW, NOW, null);
        assertEquals("ops.reader", role.code());
        Role updated = role.update("ops.viewer", "Viewer", NOW.plusSeconds(1));
        assertEquals("ops.viewer", updated.code());
        Role deleted = updated.delete(NOW.plusSeconds(2));
        assertFalse(deleted.active());
        assertEquals(deleted, deleted.delete(NOW.plusSeconds(3)));
        Role system = new Role(id(41), null, Role.PLATFORM_ADMIN_CODE, "Platform admin", ScopeKind.PLATFORM, true, true, NOW, NOW, null);
        assertThrows(IdentityAccessException.class, () -> system.update("system.other", "Other", NOW));
        assertEquals(system.code(), system.update(system.code(), "Renamed display", NOW).code());
        assertThrows(IdentityAccessException.class, () -> system.delete(NOW));
        assertThrows(IllegalArgumentException.class, () -> new Role(id(42), id(1), Role.PLATFORM_ADMIN_CODE, "Bad owner", ScopeKind.PLATFORM, true, true, NOW, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> new Role(id(42), id(1), "bad", "Bad", ScopeKind.ORGANIZATION, false, true, NOW, NOW, null));
    }

    @Test
    void temporalMembershipsAndAssignmentsUseHalfOpenWindowsAndPairedRevocation() {
        UserMembership membership = new UserMembership(id(50), id(10), id(1), null, NOW, NOW.plusSeconds(10), null);
        assertTrue(membership.effectiveAt(NOW));
        assertTrue(membership.effectiveAt(NOW.plusSeconds(9)));
        assertFalse(membership.effectiveAt(NOW.minusNanos(1)));
        assertFalse(membership.effectiveAt(NOW.plusSeconds(10)));
        assertFalse(new UserMembership(id(51), id(10), id(1), null, NOW, null, NOW.plusSeconds(1)).effectiveAt(NOW.plusSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new UserMembership(id(51), id(10), id(1), null, NOW, NOW, null));

        RoleAssignment assignment = new RoleAssignment(id(60), id(40), AssignmentActorType.USER, id(10), AuthorizationScope.organization(id(1)), NOW, NOW.plusSeconds(10), null, null);
        assertTrue(assignment.effectiveAt(NOW));
        assertFalse(assignment.effectiveAt(NOW.minusNanos(1)));
        assertFalse(assignment.effectiveAt(NOW.plusSeconds(10)));
        assertThrows(IllegalArgumentException.class, () -> new RoleAssignment(id(61), id(40), AssignmentActorType.USER, id(10), AuthorizationScope.platform(), NOW, NOW, null, null));
        assertThrows(IllegalArgumentException.class, () -> new RoleAssignment(id(61), id(40), AssignmentActorType.USER, id(10), AuthorizationScope.platform(), NOW, null, NOW, null));
        assertFalse(new RoleAssignment(id(61), id(40), AssignmentActorType.USER, id(10), AuthorizationScope.platform(), NOW, null, NOW.plusSeconds(1), id(99)).effectiveAt(NOW.plusSeconds(2)));
    }

    @Test
    void applicationValueObjectsRejectAmbiguousMetadata() {
        IdentityAccessException exception = new IdentityAccessException("IAM_TEST_FAILURE", "failure");
        assertEquals("IAM_TEST_FAILURE", exception.code());
        assertThrows(IllegalArgumentException.class, () -> new IdentityAccessException("bad-code", "failure"));
        assertTrue(AuthorizationDecision.allow("ALLOW", "ok").allowed());
        assertFalse(AuthorizationDecision.deny("DENY", "no").allowed());
        assertThrows(NullPointerException.class, () -> new AuthorizationDecision(true, null, "x"));
        IdentityAccessCommandContext context = new IdentityAccessCommandContext(id(1), id(2), " reason ", " HTTP ");
        assertEquals("reason", context.reason());
        assertEquals("HTTP", context.origin());
        assertThrows(IllegalArgumentException.class, () -> new IdentityAccessCommandContext(id(1), id(2), " ", "HTTP"));
        assertThrows(IllegalArgumentException.class, () -> new IdentityAccessCommandContext(id(1), id(2), "ok", "bad\norigin"));
    }

    static DomainIdentifier id(long suffix) {
        return new DomainIdentifier(new UUID(0x0198_0000_0000_7000L + suffix, 0x8000_0000_0000_0000L + suffix));
    }
}
