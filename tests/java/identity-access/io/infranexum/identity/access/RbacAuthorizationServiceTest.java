package io.infranexum.identity.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.audit.AuditRecord;
import io.infranexum.core.audit.AuditScope;
import io.infranexum.core.audit.InMemoryAppendOnlyAuditJournal;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.identity.access.application.AuthorizationDecision;
import io.infranexum.identity.access.application.RbacAuthorizationService;
import io.infranexum.identity.access.domain.AssignmentActorType;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.IdentityGroup;
import io.infranexum.identity.access.domain.IdentityUser;
import io.infranexum.identity.access.domain.Permission;
import io.infranexum.identity.access.domain.Role;
import io.infranexum.identity.access.domain.RoleAssignment;
import io.infranexum.identity.access.domain.ScopeKind;
import io.infranexum.identity.access.domain.UserMembership;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Contract tests for explainable deny-by-default RBAC decisions and their audit trail. */
class RbacAuthorizationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:30:00Z");
    private static final DomainIdentifier ORG = IdentityAccessDomainTest.id(400);
    private static final DomainIdentifier USER = IdentityAccessDomainTest.id(401);
    private static final DomainIdentifier EVALUATOR = IdentityAccessDomainTest.id(402);
    private static final DomainIdentifier CORRELATION = IdentityAccessDomainTest.id(403);

    private IdentityAccessTestRepository repository;
    private InMemoryAppendOnlyAuditJournal audit;
    private RbacAuthorizationService service;

    @BeforeEach
    void setUp() {
        repository = new IdentityAccessTestRepository();
        audit = new InMemoryAppendOnlyAuditJournal();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new RbacAuthorizationService(repository, audit,
                new UuidV7Generator(clock, new SecureRandom(new byte[] {4, 0, 2, 6})), clock);
        repository.insertUser(active(USER, "rbac.user"));
        repository.insertUser(active(EVALUATOR, "rbac.evaluator"));
    }

    @Test
    void directAssignmentGrantsOnlyEffectiveScopedPermission() {
        seedMembership(USER);
        Role role = seedRole("ops.reader", "asset.read", ScopeKind.ORGANIZATION);
        repository.insertAssignment(new RoleAssignment(IdentityAccessDomainTest.id(410), role.id(), AssignmentActorType.USER, USER,
                AuthorizationScope.organization(ORG), NOW, null, null, null));

        AuthorizationDecision granted = service.decide(USER, "asset.read", AuthorizationScope.organization(ORG), CORRELATION,
                "asset", "asset-1", "HTTP");
        AuthorizationDecision denied = service.decide(USER, "asset.write", AuthorizationScope.organization(ORG), CORRELATION,
                "asset", "asset-1", "HTTP");
        assertTrue(granted.allowed());
        assertEquals("RBAC_PERMISSION_GRANTED", granted.code());
        assertFalse(denied.allowed());
        assertEquals("RBAC_PERMISSION_DENIED", denied.code());
        assertTrue(audit.verify(AuditScope.organization(ORG.toString())).valid());
    }

    @Test
    void nestedGroupAssignmentIsInheritedButStillRequiresUserMembership() {
        seedMembership(USER);
        Role role = seedRole("ops.group-reader", "asset.read", ScopeKind.ORGANIZATION);
        IdentityGroup parent = group(IdentityAccessDomainTest.id(420), "ops.parent");
        IdentityGroup child = group(IdentityAccessDomainTest.id(421), "ops.child");
        repository.insertGroup(parent);
        repository.insertGroup(child);
        repository.addUserToGroup(ORG, child.id(), USER, NOW);
        repository.addGroupToGroup(ORG, parent.id(), child.id(), NOW);
        repository.insertAssignment(new RoleAssignment(IdentityAccessDomainTest.id(422), role.id(), AssignmentActorType.GROUP, parent.id(),
                AuthorizationScope.organization(ORG), NOW, null, null, null));

        assertTrue(service.decide(USER, "asset.read", AuthorizationScope.organization(ORG), CORRELATION, "asset", "asset-2", "CLI").allowed());
        repository.userMemberships.clear();
        assertFalse(service.decide(USER, "asset.read", AuthorizationScope.organization(ORG), CORRELATION, "asset", "asset-2", "CLI").allowed());
    }

    @Test
    void organizationVisibilityUsesMembershipBecauseNoOrganizationReadPermissionExists() {
        AuthorizationDecision denied = service.decideOrganizationVisibility(USER, ORG, CORRELATION, "HTTP");
        assertFalse(denied.allowed());
        seedMembership(USER);
        assertTrue(service.decideOrganizationVisibility(USER, ORG, CORRELATION, "HTTP").allowed());

        DomainIdentifier admin = IdentityAccessDomainTest.id(430);
        repository.insertUser(active(admin, "platform.visible"));
        seedPlatformAdmin(admin);
        assertTrue(service.decideOrganizationVisibility(admin, ORG, CORRELATION, "HTTP").allowed());
    }

    @Test
    void groupPermissionResolvesGroupOrganizationAndHidesMissingOrDeletedGroups() {
        seedMembership(USER);
        Role role = seedRole("ops.group-admin", "iam.group.read", ScopeKind.ORGANIZATION);
        repository.insertAssignment(new RoleAssignment(IdentityAccessDomainTest.id(440), role.id(), AssignmentActorType.USER, USER,
                AuthorizationScope.organization(ORG), NOW, null, null, null));
        IdentityGroup group = group(IdentityAccessDomainTest.id(441), "ops.visible");
        repository.insertGroup(group);

        assertTrue(service.decideGroupPermission(USER, "iam.group.read", group.id(), CORRELATION, "HTTP").allowed());
        assertEquals("RBAC_GROUP_NOT_VISIBLE", service.decideGroupPermission(USER, "iam.group.read", IdentityAccessDomainTest.id(499), CORRELATION, "HTTP").code());
        repository.updateGroup(group.delete(NOW));
        assertFalse(service.decideGroupPermission(USER, "iam.group.read", group.id(), CORRELATION, "HTTP").allowed());
    }

    @Test
    void evaluatorNotEvaluatedUserIsRecordedAsAuditActor() {
        seedMembership(USER);
        Role role = seedRole("ops.evaluate", "asset.read", ScopeKind.ORGANIZATION);
        repository.insertAssignment(new RoleAssignment(IdentityAccessDomainTest.id(450), role.id(), AssignmentActorType.USER, USER,
                AuthorizationScope.organization(ORG), NOW, null, null, null));

        AuthorizationDecision decision = service.evaluatePermission(USER, "asset.read", AuthorizationScope.organization(ORG), EVALUATOR,
                CORRELATION, "HTTP");
        assertTrue(decision.allowed());
        List<AuditRecord> records = audit.readRange(AuditScope.organization(ORG.toString()), 1, 100, 100);
        assertEquals(1, records.size());
        assertEquals(EVALUATOR.toString(), records.get(0).entry().actorId());
        assertEquals(USER.toString(), records.get(0).entry().targetId());
        assertEquals("iam.permission.evaluate", records.get(0).entry().action());
    }

    @Test
    void effectivePermissionsPlatformAdministratorAndUnregisteredRouteAreExplicit() {
        seedMembership(USER);
        Role role = seedRole("ops.multi", "asset.read", ScopeKind.ORGANIZATION);
        repository.insertAssignment(new RoleAssignment(IdentityAccessDomainTest.id(460), role.id(), AssignmentActorType.USER, USER,
                AuthorizationScope.organization(ORG), NOW, null, null, null));
        Set<String> effective = service.effectivePermissions(USER, AuthorizationScope.organization(ORG), EVALUATOR, CORRELATION, "HTTP");
        assertEquals(Set.of("asset.read"), effective);

        assertFalse(service.decidePlatformAdministrator(USER, CORRELATION, "system", "platform", "HTTP").allowed());
        seedPlatformAdmin(USER);
        assertTrue(service.isPlatformAdministrator(USER));
        assertTrue(service.decidePlatformAdministrator(USER, CORRELATION, "system", "platform", "HTTP").allowed());

        AuthorizationDecision unknown = service.denyUnregisteredRoute(USER, CORRELATION, "/api/v1/not-registered", "HTTP");
        assertFalse(unknown.allowed());
        assertEquals("RBAC_ROUTE_UNREGISTERED", unknown.code());
    }

    private void seedMembership(DomainIdentifier userId) {
        repository.insertMembership(new UserMembership(IdentityAccessDomainTest.id(500 + repository.userMemberships.size()), userId, ORG,
                null, NOW, null, null));
    }

    private Role seedRole(String roleCode, String permissionCode, ScopeKind scope) {
        Permission permission = new Permission(IdentityAccessDomainTest.id(520 + repository.permissions.size()), null, permissionCode,
                permissionCode.startsWith("iam.group") ? "iam_group" : "asset", "read", "normal", scope, true, true, NOW, NOW, null);
        repository.insertPermission(permission);
        Role role = new Role(IdentityAccessDomainTest.id(540 + repository.roles.size()), ORG, roleCode, roleCode,
                scope, false, true, NOW, NOW, null);
        repository.insertRole(role, Set.of(permission.code()));
        return role;
    }

    private void seedPlatformAdmin(DomainIdentifier userId) {
        Permission permission = repository.permissions.values().stream().filter(item -> item.code().equals("iam.user.read")).findFirst()
                .orElseGet(() -> {
                    Permission created = new Permission(IdentityAccessDomainTest.id(560), null, "iam.user.read", "iam_user", "read", "normal",
                            ScopeKind.PLATFORM, true, true, NOW, NOW, null);
                    repository.insertPermission(created);
                    return created;
                });
        Role role = repository.roles.values().stream().filter(item -> item.code().equals(Role.PLATFORM_ADMIN_CODE)).findFirst()
                .orElseGet(() -> {
                    Role created = new Role(IdentityAccessDomainTest.id(561), null, Role.PLATFORM_ADMIN_CODE, "Platform administrator",
                            ScopeKind.PLATFORM, true, true, NOW, NOW, null);
                    repository.insertRole(created, Set.of(permission.code()));
                    return created;
                });
        repository.insertAssignment(new RoleAssignment(IdentityAccessDomainTest.id(562 + repository.roleAssignments.size()), role.id(),
                AssignmentActorType.USER, userId, AuthorizationScope.platform(), NOW, null, null, null));
    }

    private static IdentityGroup group(DomainIdentifier id, String code) {
        return new IdentityGroup(id, ORG, code, code, false, NOW, NOW, null);
    }

    private static IdentityUser active(DomainIdentifier id, String login) {
        return IdentityUser.pending(id, login, null, login, NOW).activate(NOW);
    }
}
