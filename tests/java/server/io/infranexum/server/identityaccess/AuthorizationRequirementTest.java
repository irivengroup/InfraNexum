package io.infranexum.server.identityaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.infranexum.identity.access.domain.PermissionCodes;
import io.infranexum.identity.access.domain.ScopeKind;
import org.junit.jupiter.api.Test;

/** Regression coverage for the explicit deny-by-default HTTP authorization registry. */
class AuthorizationRequirementTest {
    private static final String ORG = "01980000-0000-7001-8000-000000000101";
    private static final String USER = "01980000-0000-7001-8000-000000000102";
    private static final String GROUP = "01980000-0000-7001-8000-000000000103";
    private static final String ROLE = "01980000-0000-7001-8000-000000000104";
    private static final String ASSIGNMENT = "01980000-0000-7001-8000-000000000105";

    @Test
    void platformAndOrganizationFoundationRoutesHaveExplicitPolicies() {
        assertPermission("GET", "/api/v1/platform/capabilities", PermissionCodes.PLATFORM_CAPABILITY_READ, ScopeKind.PLATFORM);
        assertPermission("GET", "/api/v1/platform/quotas", PermissionCodes.PLATFORM_CAPABILITY_READ, ScopeKind.PLATFORM);
        assertPermission("GET", "/api/v1/platform/evaluation/status", PermissionCodes.PLATFORM_PROFILE_READ, ScopeKind.PLATFORM);
        assertPermission("POST", "/api/v1/iam/organizations", PermissionCodes.ORGANIZATION_CREATE, ScopeKind.PLATFORM);
        assertEquals(AuthorizationRequirement.Type.PLATFORM_ADMINISTRATOR,
                AuthorizationRequirement.resolve("GET", "/api/v1/iam/organizations").type());
        assertEquals(AuthorizationRequirement.Type.ORGANIZATION_VISIBILITY,
                AuthorizationRequirement.resolve("GET", "/api/v1/iam/organizations/" + ORG).type());
        assertPermission("POST", "/api/v1/iam/organizations/" + ORG + "/suspend", PermissionCodes.ORGANIZATION_SUSPEND, ScopeKind.ORGANIZATION);
        assertPermission("GET", "/api/v1/iam/organizations/" + ORG + "/subdivisions", PermissionCodes.SUBDIVISION_SEARCH, ScopeKind.ORGANIZATION);
        assertPermission("POST", "/api/v1/iam/organizations/" + ORG + "/subdivisions", PermissionCodes.SUBDIVISION_CREATE, ScopeKind.ORGANIZATION);
        assertEquals(AuthorizationRequirement.Type.PLATFORM_ADMINISTRATOR,
                AuthorizationRequirement.resolve("POST", "/api/v1/iam/organizations/" + ORG + "/scopes").type());
    }

    @Test
    void userRoutesMapToAtomicApprovedPermissions() {
        assertPermission("GET", "/api/v1/iam/users", PermissionCodes.USER_SEARCH, ScopeKind.PLATFORM);
        assertPermission("POST", "/api/v1/iam/users", PermissionCodes.USER_CREATE, ScopeKind.PLATFORM);
        assertPermission("GET", "/api/v1/iam/users/" + USER, PermissionCodes.USER_READ, ScopeKind.PLATFORM);
        assertPermission("PATCH", "/api/v1/iam/users/" + USER, PermissionCodes.USER_UPDATE, ScopeKind.PLATFORM);
        assertPermission("DELETE", "/api/v1/iam/users/" + USER, PermissionCodes.USER_DELETE, ScopeKind.PLATFORM);
        assertPermission("POST", "/api/v1/iam/users/" + USER + "/activate", PermissionCodes.USER_ACTIVATE, ScopeKind.PLATFORM);
        assertPermission("POST", "/api/v1/iam/users/" + USER + "/suspend", PermissionCodes.USER_SUSPEND, ScopeKind.PLATFORM);
        assertPermission("POST", "/api/v1/iam/users/" + USER + "/memberships", PermissionCodes.USER_MANAGE_MEMBERSHIP, ScopeKind.PLATFORM);
        assertPermission("POST", "/api/v1/iam/users/" + USER + "/roles", PermissionCodes.USER_ASSIGN_ROLE, ScopeKind.PLATFORM);
    }

    @Test
    void groupRoleAndPermissionRoutesResolveOrganizationScope() {
        assertPermission("GET", "/api/v1/organizations/" + ORG + "/groups", PermissionCodes.GROUP_SEARCH, ScopeKind.ORGANIZATION);
        assertPermission("POST", "/api/v1/organizations/" + ORG + "/groups", PermissionCodes.GROUP_CREATE, ScopeKind.ORGANIZATION);
        assertPermission("GET", "/api/v1/organizations/" + ORG + "/groups/" + GROUP + "/members", PermissionCodes.GROUP_READ, ScopeKind.ORGANIZATION);
        assertPermission("POST", "/api/v1/organizations/" + ORG + "/groups/" + GROUP + "/members", PermissionCodes.GROUP_ADD_MEMBER, ScopeKind.ORGANIZATION);
        assertPermission("DELETE", "/api/v1/organizations/" + ORG + "/groups/" + GROUP + "/members/" + USER, PermissionCodes.GROUP_REMOVE_MEMBER, ScopeKind.ORGANIZATION);
        assertEquals(AuthorizationRequirement.Type.GROUP_PERMISSION,
                AuthorizationRequirement.resolve("GET", "/api/v1/iam/groups/" + GROUP + "/effective-members").type());

        assertPermission("POST", "/api/v1/organizations/" + ORG + "/roles", PermissionCodes.ROLE_CREATE, ScopeKind.ORGANIZATION);
        assertPermission("PATCH", "/api/v1/organizations/" + ORG + "/roles/" + ROLE, PermissionCodes.ROLE_UPDATE, ScopeKind.ORGANIZATION);
        assertPermission("POST", "/api/v1/organizations/" + ORG + "/roles/" + ROLE + "/assignments", PermissionCodes.ROLE_ASSIGN, ScopeKind.ORGANIZATION);
        assertPermission("DELETE", "/api/v1/organizations/" + ORG + "/roles/" + ROLE + "/assignments/" + ASSIGNMENT, PermissionCodes.ROLE_UNASSIGN, ScopeKind.ORGANIZATION);

        assertPermission("GET", "/api/v1/organizations/" + ORG + "/permissions", PermissionCodes.PERMISSION_SEARCH, ScopeKind.ORGANIZATION);
        assertPermission("GET", "/api/v1/organizations/" + ORG + "/permissions/effective", PermissionCodes.PERMISSION_EVALUATE, ScopeKind.ORGANIZATION);
        assertPermission("POST", "/api/v1/organizations/" + ORG + "/permissions/validate", PermissionCodes.PERMISSION_EVALUATE, ScopeKind.ORGANIZATION);
    }

    @Test
    void jiraAssetsFederatedReadRoutesUseConnectorReadPermissionAndFailClosedOnWrites() {
        assertPermission("GET", "/api/v1/integrations/providers/jira-assets",
                PermissionCodes.INTEGRATIONS_CONNECTOR_READ, ScopeKind.PLATFORM);
        assertPermission("GET", "/api/v1/integrations/providers/jira-assets/jira-assets.test/health",
                PermissionCodes.INTEGRATIONS_CONNECTOR_READ, ScopeKind.PLATFORM);
        assertPermission("POST", "/api/v1/integrations/providers/jira-assets/jira-assets.test/objects/search",
                PermissionCodes.INTEGRATIONS_CONNECTOR_READ, ScopeKind.PLATFORM);
        assertEquals(AuthorizationRequirement.Type.UNREGISTERED,
                AuthorizationRequirement.resolve("POST", "/api/v1/integrations/providers/jira-assets/jira-assets.test/health").type());
        assertEquals(AuthorizationRequirement.Type.UNREGISTERED,
                AuthorizationRequirement.resolve("DELETE", "/api/v1/integrations/providers/jira-assets/jira-assets.test/objects/search").type());
        assertThrows(IllegalArgumentException.class,
                () -> AuthorizationRequirement.resolve("GET", "/api/v1/integrations/providers/jira-assets/unsafe%2Fkey/health"));
    }

    @Test
    void connectorGovernanceRoutesUseReadPermissionAndFailClosedOnUnsupportedVerbs() {
        assertPermission("GET", "/api/v1/integrations/governance",
                PermissionCodes.INTEGRATIONS_CONNECTOR_READ, ScopeKind.PLATFORM);
        assertPermission("GET", "/api/v1/integrations/governance/jira-prod",
                PermissionCodes.INTEGRATIONS_CONNECTOR_READ, ScopeKind.PLATFORM);
        assertPermission("POST", "/api/v1/integrations/governance/jira-prod/sync-plan",
                PermissionCodes.INTEGRATIONS_CONNECTOR_READ, ScopeKind.PLATFORM);
        assertEquals(AuthorizationRequirement.Type.UNREGISTERED,
                AuthorizationRequirement.resolve("DELETE", "/api/v1/integrations/governance/jira-prod").type());
        assertEquals(AuthorizationRequirement.Type.UNREGISTERED,
                AuthorizationRequirement.resolve("PUT", "/api/v1/integrations/governance/jira-prod/sync-plan").type());
        assertThrows(IllegalArgumentException.class,
                () -> AuthorizationRequirement.resolve("GET", "/api/v1/integrations/governance/unsafe%2Fkey"));
    }

    @Test
    void notificationRoutesAreRegisteredFailClosed() {
        assertPermission("GET", "/api/v1/integrations/notifications/endpoints",
                PermissionCodes.INTEGRATIONS_NOTIFICATION_READ, ScopeKind.PLATFORM);
        assertPermission("POST", "/api/v1/integrations/notifications/events",
                PermissionCodes.INTEGRATIONS_NOTIFICATION_PUBLISH, ScopeKind.PLATFORM);
        assertPermission("GET", "/api/v1/integrations/notifications/dlq",
                PermissionCodes.INTEGRATIONS_NOTIFICATION_READ, ScopeKind.PLATFORM);
        assertPermission("POST", "/api/v1/integrations/notifications/dlq/018bcfe5-6800-7000-8000-000000000700/replay",
                PermissionCodes.INTEGRATIONS_NOTIFICATION_REPLAY, ScopeKind.PLATFORM);
        assertPermission("GET", "/api/v1/integrations/notifications/endpoints/ops.webhook/runtime",
                PermissionCodes.INTEGRATIONS_NOTIFICATION_READ, ScopeKind.PLATFORM);
        assertPermission("POST", "/api/v1/integrations/notifications/endpoints/ops.webhook/resume",
                PermissionCodes.INTEGRATIONS_NOTIFICATION_RESUME, ScopeKind.PLATFORM);
        assertEquals(AuthorizationRequirement.Type.UNREGISTERED,
                AuthorizationRequirement.resolve("DELETE", "/api/v1/integrations/notifications/endpoints").type());
        assertEquals(AuthorizationRequirement.Type.UNREGISTERED,
                AuthorizationRequirement.resolve("POST", "/api/v1/integrations/notifications/dlq").type());
        assertThrows(IllegalArgumentException.class, () -> AuthorizationRequirement.resolve(
                "GET", "/api/v1/integrations/notifications/endpoints/unsafe%2Fkey/runtime"));
    }

    @Test
    void serviceNowFederatedReadRoutesUseConnectorReadPermissionAndFailClosedOnWrites() {
        assertPermission("GET", "/api/v1/integrations/providers/service-now",
                PermissionCodes.INTEGRATIONS_CONNECTOR_READ, ScopeKind.PLATFORM);
        assertPermission("GET", "/api/v1/integrations/providers/service-now/cmdb-prod/health",
                PermissionCodes.INTEGRATIONS_CONNECTOR_READ, ScopeKind.PLATFORM);
        assertPermission("POST", "/api/v1/integrations/providers/service-now/cmdb-prod/configuration-items/search",
                PermissionCodes.INTEGRATIONS_CONNECTOR_READ, ScopeKind.PLATFORM);
        assertEquals(AuthorizationRequirement.Type.UNREGISTERED,
                AuthorizationRequirement.resolve("POST", "/api/v1/integrations/providers/service-now/cmdb-prod/health").type());
        assertEquals(AuthorizationRequirement.Type.UNREGISTERED,
                AuthorizationRequirement.resolve("DELETE", "/api/v1/integrations/providers/service-now/cmdb-prod/configuration-items/search").type());
        assertThrows(IllegalArgumentException.class,
                () -> AuthorizationRequirement.resolve("GET", "/api/v1/integrations/providers/service-now/unsafe%2Fkey/health"));
    }

    @Test
    void unknownUnsupportedAndMalformedRoutesFailClosed() {
        assertEquals(AuthorizationRequirement.Type.UNREGISTERED,
                AuthorizationRequirement.resolve("GET", "/api/v1/not-registered").type());
        assertEquals(AuthorizationRequirement.Type.UNREGISTERED,
                AuthorizationRequirement.resolve("PUT", "/api/v1/iam/users/" + USER).type());
        assertEquals(AuthorizationRequirement.Type.UNREGISTERED,
                AuthorizationRequirement.resolve("PUT", "/api/v1/organizations/" + ORG + "/groups/" + GROUP).type());
        assertThrows(IllegalArgumentException.class,
                () -> AuthorizationRequirement.resolve("GET", "/api/v1/organizations/" + ORG + "/groups/unknown"));
        assertEquals(AuthorizationRequirement.Type.UNREGISTERED,
                AuthorizationRequirement.resolve("GET", "/api/v1/not-registered/").type());
        assertThrows(IllegalArgumentException.class,
                () -> AuthorizationRequirement.resolve("GET", "/api/v1/iam/users/00000000-0000-0000-0000------------"));
        assertThrows(NullPointerException.class, () -> AuthorizationRequirement.resolve(null, "/api/v1/iam/users"));
        assertThrows(NullPointerException.class, () -> AuthorizationRequirement.resolve("GET", null));
    }

    @Test
    void registeredRoutesExposeAuditSafeCanonicalTargetIdentifiers() {
        AuthorizationRequirement platform = AuthorizationRequirement.resolve("GET", "/api/v1/platform/capabilities");
        AuthorizationRequirement group = AuthorizationRequirement.resolve("PATCH", "/api/v1/organizations/" + ORG + "/groups/" + GROUP);
        AuthorizationRequirement role = AuthorizationRequirement.resolve("DELETE", "/api/v1/organizations/" + ORG + "/roles/" + ROLE + "/assignments/" + ASSIGNMENT);

        assertEquals("capabilities", platform.targetId());
        assertEquals(GROUP, group.targetId());
        assertEquals(ROLE, role.targetId());
    }

    @Test
    void trailingSlashIsNormalizedWithoutChangingAuthorization() {
        AuthorizationRequirement plain = AuthorizationRequirement.resolve("GET", "/api/v1/iam/users");
        AuthorizationRequirement trailing = AuthorizationRequirement.resolve("GET", "/api/v1/iam/users/");
        assertEquals(plain, trailing);
    }

    private static void assertPermission(String method, String path, String expectedCode, ScopeKind expectedScope) {
        AuthorizationRequirement requirement = AuthorizationRequirement.resolve(method, path);
        assertEquals(AuthorizationRequirement.Type.PERMISSION, requirement.type());
        assertEquals(expectedCode, requirement.permissionCode());
        assertEquals(expectedScope, requirement.scope().kind());
    }
}
