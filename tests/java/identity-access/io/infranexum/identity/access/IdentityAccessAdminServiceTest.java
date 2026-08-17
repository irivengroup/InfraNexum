package io.infranexum.identity.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.audit.AuditScope;
import io.infranexum.core.audit.InMemoryAppendOnlyAuditJournal;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.OffsetPage;
import io.infranexum.core.contracts.PaginationConstraints;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.InMemoryEventStore;
import io.infranexum.core.events.OutboxRecord;
import io.infranexum.core.events.OutboxStatus;
import io.infranexum.core.events.RetryPolicy;
import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.core.events.TransactionalWork;
import io.infranexum.identity.access.application.IdentityAccessAdminService;
import io.infranexum.identity.access.application.IdentityAccessCommandContext;
import io.infranexum.identity.access.domain.AssignmentActorType;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.IdentityAccessException;
import io.infranexum.identity.access.domain.IdentityGroup;
import io.infranexum.identity.access.domain.IdentityUser;
import io.infranexum.identity.access.domain.IdentityUserStatus;
import io.infranexum.identity.access.domain.Permission;
import io.infranexum.identity.access.domain.Role;
import io.infranexum.identity.access.domain.RoleAssignment;
import io.infranexum.identity.access.domain.ScopeKind;
import io.infranexum.identity.access.domain.UserMembership;
import io.infranexum.identity.access.ports.IdentityAccessFeaturePolicy;
import io.infranexum.identity.access.ports.OrganizationScopeReferencePort;
import io.infranexum.identity.access.ports.RoleAssignmentPolicyGuard;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Security-focused regression tests for the PGM-03-E03 administration use cases. */
class IdentityAccessAdminServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final DomainIdentifier ORG = IdentityAccessDomainTest.id(100);
    private static final DomainIdentifier OTHER_ORG = IdentityAccessDomainTest.id(101);
    private static final DomainIdentifier SUBDIVISION = IdentityAccessDomainTest.id(102);
    private static final DomainIdentifier ACTOR = IdentityAccessDomainTest.id(103);

    private IdentityAccessTestRepository repository;
    private InMemoryEventStore events;
    private InMemoryAppendOnlyAuditJournal audit;
    private IdentityAccessAdminService service;
    private IdentityAccessCommandContext context;

    @BeforeEach
    void setUp() {
        repository = new IdentityAccessTestRepository();
        events = new InMemoryEventStore();
        audit = new InMemoryAppendOnlyAuditJournal();
        service = service(new Features(true, true));
        context = new IdentityAccessCommandContext(ACTOR, IdentityAccessDomainTest.id(104), "RBAC administration test", "TEST");
        repository.insertUser(activeUser(ACTOR, "platform.admin"));
    }

    @Test
    void usersMembershipsPaginationAndBootstrapPreserveIdentityAndAudit() {
        IdentityUser created = service.createUser(" Alice.Admin ", "Alice@Example.COM", "Alice", true, context);
        assertEquals("alice.admin", created.login());
        assertEquals(IdentityUserStatus.ACTIVE, created.status());
        assertEquals(created, service.getUser(created.id()));
        assertEquals(1, service.listUsers(0, 200).stream().filter(user -> user.id().equals(created.id())).count());
        assertThrows(IllegalArgumentException.class, () -> service.listUsers(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> service.listUsers(0, 201));
        assertCode("IAM_LOGIN_CONFLICT", () -> service.createUser("ALICE.ADMIN", null, "Duplicate", false, context));

        IdentityUser updated = service.updateUser(created.id(), null, "Alice Updated", context);
        assertEquals("Alice Updated", updated.displayName());
        assertEquals(IdentityUserStatus.SUSPENDED, service.suspendUser(created.id(), context).status());
        assertEquals(IdentityUserStatus.ACTIVE, service.activateUser(created.id(), context).status());

        UserMembership membership = service.addMembership(created.id(), ORG, SUBDIVISION, NOW, NOW.plusSeconds(60), context);
        assertEquals(SUBDIVISION, membership.subdivisionId());
        assertEquals(1, service.memberships(created.id()).size());
        service.addMembership(created.id(), OTHER_ORG, null, NOW.plusSeconds(120), null, context);
        OffsetPage<UserMembership> membershipPage1 = service.memberships(created.id(), 0, 1);
        assertEquals(1, membershipPage1.items().size());
        assertEquals(1, membershipPage1.nextOffset());
        OffsetPage<UserMembership> membershipPage2 = service.memberships(created.id(), membershipPage1.nextOffset(), 1);
        assertEquals(1, membershipPage2.items().size());
        assertEquals(null, membershipPage2.nextOffset());
        assertThrows(IllegalArgumentException.class, () -> service.memberships(created.id(), PaginationConstraints.MAX_OFFSET + 1, 1));
        assertTrue(repository.hasEffectiveMembership(created.id(), AuthorizationScope.subdivision(ORG, SUBDIVISION), NOW));
        assertFalse(repository.hasEffectiveMembership(created.id(), AuthorizationScope.subdivision(ORG, SUBDIVISION), NOW.plusSeconds(60)));

        IdentityUser deleted = service.deleteUser(created.id(), context);
        assertEquals(IdentityUserStatus.DELETED, deleted.status());
        assertCode("IAM_USER_DELETED", () -> service.updateUser(created.id(), null, "No", context));
        assertCode("IAM_USER_NOT_FOUND", () -> service.getUser(IdentityAccessDomainTest.id(999)));

        DomainIdentifier bootstrap = IdentityAccessDomainTest.id(105);
        service.ensureBootstrapPlatformAdministrator(bootstrap, "bootstrap.admin", "Bootstrap Admin");
        service.ensureBootstrapPlatformAdministrator(bootstrap, "bootstrap.admin", "Bootstrap Admin");
        assertEquals(bootstrap, repository.findUserByLogin("bootstrap.admin").orElseThrow().id());
        assertTrue(repository.hasEffectiveSystemRole(bootstrap, Role.PLATFORM_ADMIN_CODE, NOW));
        assertTrue(audit.verify(AuditScope.platform()).valid());
        assertTrue(events.outboxSnapshot().size() >= 6);
    }

    @Test
    void statusFilteringKeepsSuspendedAccountsRecoverableAndBlocksSelfLockout() {
        IdentityUser target = service.createUser("recoverable.user", null, "Recoverable User", true, context);
        service.suspendUser(target.id(), context);

        assertTrue(service.listUsers(IdentityUserStatus.SUSPENDED, 0, 200).stream()
                .anyMatch(user -> user.id().equals(target.id())));
        assertTrue(service.listUsers(IdentityUserStatus.ACTIVE, 0, 200).stream()
                .noneMatch(user -> user.id().equals(target.id())));

        IdentityUser reactivated = service.activateUser(target.id(), context);
        assertEquals(IdentityUserStatus.ACTIVE, reactivated.status());
        assertTrue(service.listUsers(IdentityUserStatus.ACTIVE, 0, 200).stream()
                .anyMatch(user -> user.id().equals(target.id())));

        assertCode("IAM_SELF_LOCKOUT_FORBIDDEN", () -> service.suspendUser(ACTOR, context));
        assertCode("IAM_SELF_LOCKOUT_FORBIDDEN", () -> service.deleteUser(ACTOR, context));
        assertEquals(IdentityUserStatus.ACTIVE, service.getUser(ACTOR).status());
    }

    @Test
    void liteRejectsSecondConcurrentMembershipButAllowsSameScopeHistory() {
        service = service(new Features(false, false));
        IdentityUser user = service.createUser("lite.user", null, "Lite User", true, context);
        service.addMembership(user.id(), ORG, null, NOW, null, context);
        assertCode("IAM_MULTI_MEMBERSHIP_UNAVAILABLE",
                () -> service.addMembership(user.id(), OTHER_ORG, null, NOW.plusSeconds(1), null, context));
        assertNotNull(service.addMembership(user.id(), ORG, null, NOW.plusSeconds(1), null, context));

        IdentityUser historyOnly = service.createUser("lite.history", null, "Lite History", true, context);
        service.addMembership(historyOnly.id(), ORG, null, NOW.minusSeconds(20), NOW.minusSeconds(10), context);
        assertNotNull(service.addMembership(historyOnly.id(), OTHER_ORG, null, NOW, null, context));

        IdentityUser subdivisionConflict = service.createUser("lite.subdivision", null, "Lite Subdivision", true, context);
        service.addMembership(subdivisionConflict.id(), ORG, null, NOW, null, context);
        assertCode("IAM_MULTI_MEMBERSHIP_UNAVAILABLE",
                () -> service.addMembership(subdivisionConflict.id(), ORG, SUBDIVISION, NOW.plusSeconds(1), null, context));
    }

    @Test
    void groupMembershipRequiresOrganizationMembershipAndNestedGroupsRemainAcyclic() {
        IdentityUser member = service.createUser("group.user", null, "Group User", true, context);
        IdentityGroup parent = service.createGroup(ORG, "ops.parent", "Parent", context);
        IdentityGroup child = service.createGroup(ORG, "ops.child", "Child", context);

        assertCode("IAM_GROUP_MEMBERSHIP_SCOPE_MISMATCH", () -> service.addUserToGroup(ORG, child.id(), member.id(), context));
        service.addMembership(member.id(), ORG, null, NOW, null, context);
        service.addUserToGroup(ORG, child.id(), member.id(), context);
        service.addGroupToGroup(ORG, parent.id(), child.id(), context);
        assertTrue(service.effectiveGroupMembers(parent.id()).contains(member.id()));
        assertCode("IAM_GROUP_CYCLE", () -> service.addGroupToGroup(ORG, child.id(), parent.id(), context));
        assertCode("IAM_GROUP_CYCLE", () -> service.addGroupToGroup(ORG, parent.id(), parent.id(), context));
        assertCode("IAM_GROUP_NOT_EMPTY", () -> service.deleteGroup(ORG, parent.id(), context));

        service.removeGroupFromGroup(ORG, parent.id(), child.id(), context);
        assertTrue(service.deleteGroup(ORG, parent.id(), context).deleted());
        service.removeUserFromGroup(ORG, child.id(), member.id(), context);
        assertTrue(service.deleteGroup(ORG, child.id(), context).deleted());

        long removedEvents = events.outboxSnapshot().stream()
                .filter(record -> record.event().eventType().value().equals("iam.group.member_removed.v1"))
                .count();
        assertEquals(2L, removedEvents);
    }

    @Test
    void profileWithoutNestedGroupsFailsClosedBeforeGraphMutation() {
        service = service(new Features(false, true));
        IdentityGroup parent = service.createGroup(ORG, "lite.parent", "Parent", context);
        IdentityGroup child = service.createGroup(ORG, "lite.child", "Child", context);
        assertCode("IAM_NESTED_GROUPS_UNAVAILABLE", () -> service.addGroupToGroup(ORG, parent.id(), child.id(), context));
        assertEquals(0L, repository.groupMemberCount(ORG, parent.id()));
    }

    @Test
    void permissionsAndRolesProtectSystemObjectsAndRequireNonEmptyPermissionSets() {
        Permission permission = service.createPermission(ORG, "asset.read", "asset", "read", "normal", ScopeKind.ORGANIZATION, context);
        assertEquals("asset.read", permission.code());
        assertFalse(service.updatePermission(permission.id(), "asset", "read", "elevated", ScopeKind.SUBDIVISION, false, context).active());
        assertTrue(service.deletePermission(permission.id(), context).deleted());

        Permission systemPermission = new Permission(IdentityAccessDomainTest.id(110), null, "iam.user.read", "iam_user", "read", "normal",
                ScopeKind.PLATFORM, true, true, NOW, NOW, null);
        repository.insertPermission(systemPermission);
        assertCode("IAM_SYSTEM_PERMISSION_PROTECTED",
                () -> service.updatePermission(systemPermission.id(), "iam_user", "read", "normal", ScopeKind.PLATFORM, true, context));
        assertCode("IAM_SYSTEM_PERMISSION_PROTECTED", () -> service.deletePermission(systemPermission.id(), context));

        assertCode("IAM_ROLE_EMPTY", () -> service.createRole(ORG, "ops.empty", "Empty", ScopeKind.ORGANIZATION, Set.of(), context));
        Role role = service.createRole(ORG, "ops.reader", "Reader", ScopeKind.ORGANIZATION, Set.of(" ASSET.READ "), context);
        assertEquals(Set.of("asset.read"), service.rolePermissionCodes(role.id()));
        Role updated = service.updateRole(role.id(), "ops.viewer", "Viewer", Set.of("asset.read"), context);
        assertEquals("ops.viewer", updated.code());
    }

    @Test
    void roleAssignmentEnforcesMembershipRoleScopeAndGroupOwnership() {
        Permission permission = seedPermission("asset.read", ScopeKind.ORGANIZATION);
        Role orgRole = service.createRole(ORG, "ops.reader", "Reader", ScopeKind.ORGANIZATION, Set.of(permission.code()), context);
        IdentityUser user = service.createUser("assigned.user", null, "Assigned User", true, context);

        assertCode("IAM_ASSIGNMENT_SCOPE_MISMATCH",
                () -> service.assignRole(orgRole.id(), AssignmentActorType.USER, user.id(), AuthorizationScope.organization(ORG), NOW, null, context));
        service.addMembership(user.id(), ORG, null, NOW, null, context);
        RoleAssignment userAssignment = service.assignRole(orgRole.id(), AssignmentActorType.USER, user.id(), AuthorizationScope.organization(ORG), NOW, null, context);
        assertTrue(userAssignment.effectiveAt(NOW));
        assertTrue(service.effectivePermissionCodes(user.id(), AuthorizationScope.organization(ORG)).contains(permission.code()));
        assertCode("IAM_ASSIGNMENT_SCOPE_MISMATCH",
                () -> service.assignRole(orgRole.id(), AssignmentActorType.USER, user.id(), AuthorizationScope.platform(), NOW, null, context));
        assertCode("IAM_ASSIGNMENT_SCOPE_MISMATCH",
                () -> service.assignRole(orgRole.id(), AssignmentActorType.USER, user.id(), AuthorizationScope.organization(OTHER_ORG), NOW, null, context));

        IdentityGroup group = service.createGroup(ORG, "ops.assignment", "Assignment group", context);
        RoleAssignment groupAssignment = service.assignRole(orgRole.id(), AssignmentActorType.GROUP, group.id(), AuthorizationScope.organization(ORG), NOW, null, context);
        assertEquals(AssignmentActorType.GROUP, groupAssignment.actorType());
        OffsetPage<RoleAssignment> assignmentPage1 = service.assignments(orgRole.id(), 0, 1);
        assertEquals(1, assignmentPage1.items().size());
        assertEquals(1, assignmentPage1.nextOffset());
        OffsetPage<RoleAssignment> assignmentPage2 = service.assignments(orgRole.id(), assignmentPage1.nextOffset(), 1);
        assertEquals(1, assignmentPage2.items().size());
        assertEquals(null, assignmentPage2.nextOffset());
        assertCode("IAM_ASSIGNMENT_SCOPE_MISMATCH",
                () -> service.assignRole(orgRole.id(), AssignmentActorType.GROUP, group.id(), AuthorizationScope.platform(), NOW, null, context));

        Role subdivisionRole = service.createRole(ORG, "ops.subreader", "Subdivision Reader", ScopeKind.SUBDIVISION, Set.of(permission.code()), context);
        assertCode("IAM_ASSIGNMENT_SCOPE_MISMATCH",
                () -> service.assignRole(subdivisionRole.id(), AssignmentActorType.USER, user.id(), AuthorizationScope.organization(ORG), NOW, null, context));
    }

    @Test
    void systemRoleCannotBeAssignedOrRevokedByNonAdministrator() {
        Role systemRole = seedSystemPlatformAdminRole();
        IdentityUser target = service.createUser("target.admin", null, "Target", true, context);
        assertCode("IAM_SYSTEM_ROLE_ASSIGNMENT_FORBIDDEN",
                () -> service.assignRole(systemRole.id(), AssignmentActorType.USER, target.id(), AuthorizationScope.platform(), NOW, null, context));

        RoleAssignment actorAdmin = new RoleAssignment(IdentityAccessDomainTest.id(120), systemRole.id(), AssignmentActorType.USER, ACTOR,
                AuthorizationScope.platform(), NOW, null, null, null);
        repository.insertAssignment(actorAdmin);
        RoleAssignment targetAdmin = service.assignRole(systemRole.id(), AssignmentActorType.USER, target.id(), AuthorizationScope.platform(), NOW, null, context);
        assertTrue(repository.hasEffectiveSystemRole(target.id(), Role.PLATFORM_ADMIN_CODE, NOW));

        repository.revokeAssignment(actorAdmin.id(), ACTOR, NOW);
        assertCode("IAM_SYSTEM_ROLE_ASSIGNMENT_FORBIDDEN", () -> service.revokeAssignment(systemRole.id(), targetAdmin.id(), context));
    }

    @Test
    void revokeAssignmentRejectsCrossRoleIdentifiersAndDoubleRevocation() {
        Permission permission = seedPermission("asset.read", ScopeKind.ORGANIZATION);
        Role roleA = service.createRole(ORG, "ops.a", "Role A", ScopeKind.ORGANIZATION, Set.of(permission.code()), context);
        Role roleB = service.createRole(ORG, "ops.b", "Role B", ScopeKind.ORGANIZATION, Set.of(permission.code()), context);
        IdentityUser user = service.createUser("revoke.user", null, "Revoke User", true, context);
        service.addMembership(user.id(), ORG, null, NOW, null, context);
        RoleAssignment assignment = service.assignRole(roleA.id(), AssignmentActorType.USER, user.id(), AuthorizationScope.organization(ORG), NOW, null, context);

        assertCode("IAM_ASSIGNMENT_ROLE_MISMATCH", () -> service.revokeAssignment(roleB.id(), assignment.id(), context));
        assertTrue(repository.findAssignment(assignment.id()).orElseThrow().revokedAt() == null);
        service.revokeAssignment(roleA.id(), assignment.id(), context);
        assertNotNull(repository.findAssignment(assignment.id()).orElseThrow().revokedAt());
        assertCode("IAM_ASSIGNMENT_ALREADY_REVOKED", () -> service.revokeAssignment(roleA.id(), assignment.id(), context));
        assertCode("IAM_ASSIGNMENT_NOT_FOUND", () -> service.getAssignment(IdentityAccessDomainTest.id(999)));
    }

    @Test
    void deletingAssignedRoleRequiresForceAndForceRevokesAssignments() {
        Permission permission = seedPermission("asset.read", ScopeKind.ORGANIZATION);
        Role role = service.createRole(ORG, "ops.removable", "Removable", ScopeKind.ORGANIZATION, Set.of(permission.code()), context);
        IdentityUser user = service.createUser("delete.role.user", null, "Delete Role User", true, context);
        service.addMembership(user.id(), ORG, null, NOW, null, context);
        RoleAssignment assignment = service.assignRole(role.id(), AssignmentActorType.USER, user.id(), AuthorizationScope.organization(ORG), NOW, null, context);

        assertCode("IAM_ROLE_ASSIGNED", () -> service.deleteRole(role.id(), false, context));
        assertTrue(service.deleteRole(role.id(), true, context).deleted());
        assertNotNull(repository.findAssignment(assignment.id()).orElseThrow().revokedAt());
        assertTrue(repository.rolePermissionCodes(role.id()).isEmpty());
    }

    @Test
    void mutationsRejectDanglingOrganizationAndSubdivisionReferencesBeforePersistence() {
        IdentityUser user = service.createUser("scope.user", null, "Scope User", true, context);
        DomainIdentifier missingOrg = IdentityAccessDomainTest.id(901);
        DomainIdentifier missingSubdivision = IdentityAccessDomainTest.id(902);

        assertCode("IAM_ORGANIZATION_NOT_FOUND",
                () -> service.addMembership(user.id(), missingOrg, null, NOW, null, context));
        assertCode("IAM_SUBDIVISION_NOT_FOUND",
                () -> service.addMembership(user.id(), ORG, missingSubdivision, NOW, null, context));
        assertCode("IAM_ORGANIZATION_NOT_FOUND",
                () -> service.createGroup(missingOrg, "dangling.group", "Dangling", context));
        assertCode("IAM_ORGANIZATION_NOT_FOUND",
                () -> service.createPermission(missingOrg, "asset.missing", "asset", "read", "normal", ScopeKind.ORGANIZATION, context));
        assertCode("IAM_ORGANIZATION_NOT_FOUND",
                () -> service.createRole(missingOrg, "dangling.role", "Dangling", ScopeKind.ORGANIZATION, Set.of("iam.user.read"), context));
    }


    @Test
    void remainingAdministrationGuardsCoverEmptyPaginationUnassignedRolesAndScopeShapes() {
        assertThrows(IllegalArgumentException.class, () -> service.listUsers(0, 0));
        assertCode("IAM_ROLE_EMPTY", () -> service.createRole(ORG, "ops.null", "Null", ScopeKind.ORGANIZATION, null, context));

        Permission permission = seedPermission("asset.guard", ScopeKind.ORGANIZATION);
        Role unassigned = service.createRole(ORG, "ops.unassigned", "Unassigned", ScopeKind.ORGANIZATION,
                Set.of(permission.code()), context);
        assertTrue(service.deleteRole(unassigned.id(), false, context).deleted());

        IdentityUser scoped = service.createUser("scope.guard", null, "Scope Guard", true, context);
        assertCode("IAM_SCOPE_REFERENCE_INVALID",
                () -> service.addMembership(scoped.id(), null, SUBDIVISION, NOW, null, context));

        Role platformNonSystem = new Role(IdentityAccessDomainTest.id(410), null, "ops.platform", "Platform",
                ScopeKind.PLATFORM, false, true, NOW, NOW, null);
        repository.insertRole(platformNonSystem, Set.of(permission.code()));
        IdentityGroup group = service.createGroup(ORG, "ops.platform-group", "Platform Group", context);
        assertCode("IAM_ASSIGNMENT_SCOPE_MISMATCH", () -> service.assignRole(platformNonSystem.id(), AssignmentActorType.GROUP,
                group.id(), AuthorizationScope.platform(), NOW, null, context));

        Role globalOrganizationRole = new Role(IdentityAccessDomainTest.id(411), null, "ops.global-org", "Global Org",
                ScopeKind.ORGANIZATION, false, true, NOW, NOW, null);
        repository.insertRole(globalOrganizationRole, Set.of(permission.code()));
        assertCode("IAM_ASSIGNMENT_SCOPE_MISMATCH", () -> service.assignRole(globalOrganizationRole.id(), AssignmentActorType.USER,
                scoped.id(), AuthorizationScope.platform(), NOW, null, context));

        Role system = seedSystemPlatformAdminRole();
        assertCode("IAM_ASSIGNMENT_SCOPE_MISMATCH", () -> service.assignRole(system.id(), AssignmentActorType.USER,
                scoped.id(), AuthorizationScope.organization(ORG), NOW, null, context));

        Role subdivision = service.createRole(ORG, "ops.sub-ok", "Subdivision", ScopeKind.SUBDIVISION,
                Set.of(permission.code()), context);
        service.addMembership(scoped.id(), ORG, SUBDIVISION, NOW, null, context);
        RoleAssignment subAssignment = service.assignRole(subdivision.id(), AssignmentActorType.USER, scoped.id(),
                AuthorizationScope.subdivision(ORG, SUBDIVISION), NOW, null, context);
        assertEquals(ScopeKind.SUBDIVISION, subAssignment.scope().kind());

        IdentityUser history = service.createUser("history.guard", null, "History", true, context);
        service.addMembership(history.id(), ORG, null, NOW.minusSeconds(20), NOW.minusSeconds(10), context);
        assertNotNull(service.addMembership(history.id(), OTHER_ORG, null, NOW, null, context));
    }

    @Test
    void implicitEffectiveFromUsesPlatformClockForMembershipAndRoleAssignment() {
        IdentityUser user = service.createUser("implicit.time", "implicit@example.test", "Implicit Time", true, context);
        UserMembership membership = service.addMembership(user.id(), ORG, null, null, null, context);
        assertEquals(NOW, membership.effectiveFrom());
        Permission permission = seedPermission("asset.implicit.read", ScopeKind.ORGANIZATION);
        Role role = service.createRole(ORG, "ops.implicit", "Implicit", ScopeKind.ORGANIZATION, Set.of(permission.code()), context);
        RoleAssignment assignment = service.assignRole(role.id(), AssignmentActorType.USER, user.id(),
                AuthorizationScope.organization(ORG), null, null, context);
        assertEquals(NOW, assignment.effectiveFrom());
    }

    @Test
    void transactionBoundaryRethrowsDomainRuntimeCausesAndPreservesCheckedFailureWrapper() {
        IdentityAccessException domain = new IdentityAccessException("IAM_FORCED", "forced");
        IdentityAccessAdminService domainFailing = service(new Features(true, true), new FailingEventStore(domain));
        assertEquals(domain, assertThrows(IdentityAccessException.class,
                () -> domainFailing.createUser("forced.domain", null, "Forced", true, context)));

        Exception checked = new Exception("checked persistence failure");
        IdentityAccessAdminService checkedFailing = service(new Features(true, true), new FailingEventStore(checked));
        TransactionExecutionException wrapped = assertThrows(TransactionExecutionException.class,
                () -> checkedFailing.createUser("forced.checked", null, "Forced", true, context));
        assertEquals(checked, wrapped.getCause());
    }

    private IdentityAccessAdminService service(IdentityAccessFeaturePolicy features) {
        return service(features, events);
    }

    private IdentityAccessAdminService service(IdentityAccessFeaturePolicy features, TransactionalEventStore eventStore) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        OrganizationScopeReferencePort organizationScopes = new OrganizationScopeReferencePort() {
            @Override
            public boolean organizationExists(DomainIdentifier organizationId) {
                return organizationId.equals(ORG) || organizationId.equals(OTHER_ORG);
            }

            @Override
            public boolean subdivisionExists(DomainIdentifier organizationId, DomainIdentifier subdivisionId) {
                return organizationId.equals(ORG) && subdivisionId.equals(SUBDIVISION);
            }
        };
        return new IdentityAccessAdminService(repository, features, organizationScopes, RoleAssignmentPolicyGuard.allowAll(), eventStore, audit,
                new UuidV7Generator(clock, new SecureRandom(new byte[] {7, 3, 1, 9})), clock);
    }

    private Permission seedPermission(String code, ScopeKind scope) {
        Permission permission = new Permission(IdentityAccessDomainTest.id(200 + repository.permissions.size()), null, code,
                "asset", "read", "normal", scope, true, true, NOW, NOW, null);
        repository.insertPermission(permission);
        return permission;
    }

    private Role seedSystemPlatformAdminRole() {
        Permission permission = seedPermission("iam.user.read", ScopeKind.PLATFORM);
        Role role = new Role(IdentityAccessDomainTest.id(300), null, Role.PLATFORM_ADMIN_CODE, "Platform administrator",
                ScopeKind.PLATFORM, true, true, NOW, NOW, null);
        repository.insertRole(role, Set.of(permission.code()));
        return role;
    }

    private static IdentityUser activeUser(DomainIdentifier id, String login) {
        return IdentityUser.pending(id, login, null, login, NOW).activate(NOW);
    }

    private static void assertCode(String expected, Runnable operation) {
        IdentityAccessException error = assertThrows(IdentityAccessException.class, operation::run);
        assertEquals(expected, error.code());
    }

    private record Features(boolean supportsNestedGroups, boolean supportsMultiMembership) implements IdentityAccessFeaturePolicy {}
    private static final class FailingEventStore implements TransactionalEventStore {
        private final Throwable cause;
        FailingEventStore(Throwable cause) { this.cause = cause; }
        @Override public <T> io.infranexum.core.events.TransactionOutcome<T> execute(TransactionalWork<T> work) {
            throw new TransactionExecutionException("forced IAM test failure", cause);
        }
        @Override public List<OutboxRecord> claimBatch(String workerId, int limit, Instant now, Duration leaseDuration) {
            throw new UnsupportedOperationException("not used");
        }
        @Override public void markPublished(DomainIdentifier eventId, String workerId, Instant publishedAt) {
            throw new UnsupportedOperationException("not used");
        }
        @Override public OutboxStatus markFailed(
                DomainIdentifier eventId, String workerId, Instant failedAt, RetryPolicy retryPolicy, Throwable failure) {
            throw new UnsupportedOperationException("not used");
        }
    }

}
