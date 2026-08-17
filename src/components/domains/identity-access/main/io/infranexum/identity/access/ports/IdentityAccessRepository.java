package io.infranexum.identity.access.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Persistence port for the authoritative IAM RBAC foundation. */
public interface IdentityAccessRepository {
    /** Lists users across all lifecycle states when {@code status} is null. */
    List<IdentityUser> listUsers(IdentityUserStatus status, int offset, int limit);

    default List<IdentityUser> listUsers(int offset, int limit) {
        return listUsers(null, offset, limit);
    }
    Optional<IdentityUser> findUser(DomainIdentifier id);
    Optional<IdentityUser> findUserByLogin(String canonicalLogin);
    void insertUser(IdentityUser user);
    void updateUser(IdentityUser user);
    void ensureBootstrapUser(DomainIdentifier id, String login, String displayName, Instant now);
    void ensurePlatformAdministrator(DomainIdentifier userId, Instant now);

    List<UserMembership> memberships(DomainIdentifier userId);
    List<UserMembership> memberships(DomainIdentifier userId, int offset, int limit);
    void insertMembership(UserMembership membership);
    boolean hasEffectiveMembership(DomainIdentifier userId, AuthorizationScope scope, Instant at);

    List<IdentityGroup> listGroups(DomainIdentifier organizationId, int offset, int limit);
    Optional<IdentityGroup> findGroup(DomainIdentifier organizationId, DomainIdentifier groupId);
    Optional<IdentityGroup> findGroup(DomainIdentifier groupId);
    void insertGroup(IdentityGroup group);
    void updateGroup(IdentityGroup group);
    void addUserToGroup(DomainIdentifier organizationId, DomainIdentifier groupId, DomainIdentifier userId, Instant now);
    void addGroupToGroup(DomainIdentifier organizationId, DomainIdentifier parentGroupId, DomainIdentifier childGroupId, Instant now);
    void removeUserFromGroup(DomainIdentifier organizationId, DomainIdentifier groupId, DomainIdentifier userId);
    void removeGroupFromGroup(DomainIdentifier organizationId, DomainIdentifier parentGroupId, DomainIdentifier childGroupId);
    boolean wouldCreateGroupCycle(DomainIdentifier organizationId, DomainIdentifier parentGroupId, DomainIdentifier childGroupId);
    long groupMemberCount(DomainIdentifier organizationId, DomainIdentifier groupId);
    Set<DomainIdentifier> effectiveGroupMembers(DomainIdentifier groupId);

    List<Role> listRoles(DomainIdentifier organizationId, int offset, int limit);
    Optional<Role> findRole(DomainIdentifier roleId);
    void insertRole(Role role, Set<String> permissionCodes);
    void updateRole(Role role, Set<String> permissionCodes);
    long activeAssignmentCount(DomainIdentifier roleId, Instant at);
    void revokeAssignmentsForRole(DomainIdentifier roleId, DomainIdentifier revokedBy, Instant now);

    List<Permission> listPermissions(DomainIdentifier organizationId, int offset, int limit);
    Optional<Permission> findPermission(DomainIdentifier permissionId);
    Optional<Permission> findPermissionByCode(DomainIdentifier organizationId, String code);
    void insertPermission(Permission permission);
    void updatePermission(Permission permission);

    List<RoleAssignment> assignments(DomainIdentifier roleId);
    List<RoleAssignment> assignments(DomainIdentifier roleId, int offset, int limit);
    Optional<RoleAssignment> findAssignment(DomainIdentifier assignmentId);
    Set<String> rolePermissionCodes(DomainIdentifier roleId);
    void insertAssignment(RoleAssignment assignment);
    void revokeAssignment(DomainIdentifier assignmentId, DomainIdentifier revokedBy, Instant now);
    boolean hasEffectivePermission(DomainIdentifier userId, String permissionCode, AuthorizationScope scope, Instant at);
    Set<String> effectivePermissionCodes(DomainIdentifier userId, AuthorizationScope scope, Instant at);
    boolean hasEffectiveRole(DomainIdentifier userId, DomainIdentifier roleId, AuthorizationScope scope, Instant at);
    boolean hasEffectiveSystemRole(DomainIdentifier userId, String roleCode, Instant at);
}
