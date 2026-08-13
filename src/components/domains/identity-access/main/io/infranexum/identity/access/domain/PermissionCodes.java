package io.infranexum.identity.access.domain;

/** Approved draft.21 IAM permission codes used by the PGM-03-E03 enforcement boundary. */
public final class PermissionCodes {
    private PermissionCodes() {}
    public static final String USER_CREATE="iam.user.create", USER_READ="iam.user.read", USER_UPDATE="iam.user.update", USER_DELETE="iam.user.delete", USER_SUSPEND="iam.user.suspend", USER_ACTIVATE="iam.user.activate", USER_ASSIGN_ROLE="iam.user.assign_role", USER_MANAGE_MEMBERSHIP="iam.user.manage_membership", USER_SEARCH="iam.user.search";
    public static final String GROUP_CREATE="iam.group.create", GROUP_READ="iam.group.read", GROUP_UPDATE="iam.group.update", GROUP_DELETE="iam.group.delete", GROUP_ADD_MEMBER="iam.group.add_member", GROUP_REMOVE_MEMBER="iam.group.remove_member", GROUP_ADD_GROUP="iam.group.add_group", GROUP_REMOVE_GROUP="iam.group.remove_group", GROUP_ASSIGN_ROLE="iam.group.assign_role", GROUP_SEARCH="iam.group.search";
    public static final String ROLE_CREATE="iam.role.create", ROLE_READ="iam.role.read", ROLE_UPDATE="iam.role.update", ROLE_DELETE="iam.role.delete", ROLE_ASSIGN="iam.role.assign", ROLE_UNASSIGN="iam.role.unassign", ROLE_SEARCH="iam.role.search";
    public static final String PERMISSION_CREATE="iam.permission.create", PERMISSION_READ="iam.permission.read", PERMISSION_UPDATE="iam.permission.update", PERMISSION_DELETE="iam.permission.delete", PERMISSION_SEARCH="iam.permission.search", PERMISSION_ASSIGN="iam.permission.assign", PERMISSION_REVOKE="iam.permission.revoke", PERMISSION_EVALUATE="iam.permission.evaluate";
    public static final String PLATFORM_PROFILE_READ="platform.profile.read", PLATFORM_CAPABILITY_READ="platform.capability.read";
    public static final String ORGANIZATION_CREATE="organization.create", ORGANIZATION_SUSPEND="organization.suspend";
    public static final String SUBDIVISION_CREATE="organization.subdivision.create", SUBDIVISION_READ="organization.subdivision.read", SUBDIVISION_SEARCH="organization.subdivision.search", SUBDIVISION_UPDATE="organization.subdivision.update", SUBDIVISION_DELETE="organization.subdivision.delete", SUBDIVISION_VIEW_RESOURCES="organization.subdivision.view_resources";
}
