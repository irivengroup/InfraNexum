package io.infranexum.identity.access;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.domain.AssignmentActorType;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.IdentityGroup;
import io.infranexum.identity.access.domain.IdentityUser;
import io.infranexum.identity.access.domain.IdentityUserStatus;
import io.infranexum.identity.access.domain.Permission;
import io.infranexum.identity.access.domain.Role;
import io.infranexum.identity.access.domain.RoleAssignment;
import io.infranexum.identity.access.domain.ScopeKind;
import io.infranexum.identity.access.domain.UserMembership;
import io.infranexum.identity.access.ports.IdentityAccessRepository;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic in-memory repository exercising the same scope and inheritance semantics as JDBC. */
final class IdentityAccessTestRepository implements IdentityAccessRepository {
    final Map<DomainIdentifier, IdentityUser> users = new HashMap<>();
    final Map<DomainIdentifier, IdentityGroup> groups = new HashMap<>();
    final Map<DomainIdentifier, Permission> permissions = new HashMap<>();
    final Map<DomainIdentifier, Role> roles = new HashMap<>();
    final Map<DomainIdentifier, RoleAssignment> roleAssignments = new HashMap<>();
    final List<UserMembership> userMemberships = new ArrayList<>();
    final Map<DomainIdentifier, Set<DomainIdentifier>> groupUsers = new HashMap<>();
    final Map<DomainIdentifier, Set<DomainIdentifier>> groupChildren = new HashMap<>();
    final Map<DomainIdentifier, Set<String>> rolePermissions = new HashMap<>();

    @Override
    public List<IdentityUser> listUsers(IdentityUserStatus status, int offset, int limit) {
        return users.values().stream()
                .filter(user -> status == null || user.status() == status)
                .sorted(Comparator.comparing(IdentityUser::login))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override public Optional<IdentityUser> findUser(DomainIdentifier id) { return Optional.ofNullable(users.get(id)); }
    @Override public Optional<IdentityUser> findUserByLogin(String login) { return users.values().stream().filter(user -> user.login().equals(login)).findFirst(); }
    @Override public void insertUser(IdentityUser user) { users.put(user.id(), user); }
    @Override public void updateUser(IdentityUser user) { users.put(user.id(), user); }

    @Override
    public void ensureBootstrapUser(DomainIdentifier id, String login, String displayName, Instant now) {
        users.putIfAbsent(id, IdentityUser.pending(id, login, null, displayName, now).activate(now));
    }

    @Override
    public void ensurePlatformAdministrator(DomainIdentifier userId, Instant now) {
        Optional<Role> existing = roles.values().stream().filter(role -> Role.PLATFORM_ADMIN_CODE.equals(role.code())).findFirst();
        Role role = existing.orElseGet(() -> {
            Role seeded = new Role(userId, null, Role.PLATFORM_ADMIN_CODE, "Platform administrator", ScopeKind.PLATFORM, true, true, now, now, null);
            roles.put(seeded.id(), seeded);
            rolePermissions.put(seeded.id(), Set.of("iam.user.read"));
            return seeded;
        });
        boolean assigned = roleAssignments.values().stream().anyMatch(assignment -> assignment.roleId().equals(role.id())
                && assignment.actorType() == AssignmentActorType.USER && assignment.actorId().equals(userId)
                && assignment.effectiveAt(now));
        if (!assigned) {
            roleAssignments.put(userId, new RoleAssignment(userId, role.id(), AssignmentActorType.USER, userId,
                    AuthorizationScope.platform(), now, null, null, null));
        }
    }

    @Override public List<UserMembership> memberships(DomainIdentifier userId) { return userMemberships.stream().filter(value -> value.userId().equals(userId)).toList(); }

    @Override
    public List<UserMembership> memberships(DomainIdentifier userId, int offset, int limit) {
        return userMemberships.stream().filter(value -> value.userId().equals(userId))
                .sorted(Comparator.comparing(UserMembership::effectiveFrom).thenComparing(UserMembership::id))
                .skip(offset).limit(limit).toList();
    }
    @Override public void insertMembership(UserMembership membership) { userMemberships.add(membership); }

    @Override
    public boolean hasEffectiveMembership(DomainIdentifier userId, AuthorizationScope scope, Instant at) {
        IdentityUser user = users.get(userId);
        if (user == null || user.status() != IdentityUserStatus.ACTIVE || scope.kind() == ScopeKind.PLATFORM) return false;
        return userMemberships.stream().filter(membership -> membership.userId().equals(userId) && membership.effectiveAt(at))
                .anyMatch(membership -> membership.organizationId().equals(scope.organizationId())
                        && (scope.kind() == ScopeKind.ORGANIZATION || membership.subdivisionId() == null
                                || membership.subdivisionId().equals(scope.subdivisionId())));
    }

    @Override
    public List<IdentityGroup> listGroups(DomainIdentifier organizationId, int offset, int limit) {
        return groups.values().stream().filter(group -> group.organizationId().equals(organizationId))
                .sorted(Comparator.comparing(IdentityGroup::code)).skip(offset).limit(limit).toList();
    }

    @Override public Optional<IdentityGroup> findGroup(DomainIdentifier organizationId, DomainIdentifier groupId) {
        return Optional.ofNullable(groups.get(groupId)).filter(group -> group.organizationId().equals(organizationId));
    }
    @Override public Optional<IdentityGroup> findGroup(DomainIdentifier groupId) { return Optional.ofNullable(groups.get(groupId)); }
    @Override public void insertGroup(IdentityGroup group) { groups.put(group.id(), group); }
    @Override public void updateGroup(IdentityGroup group) { groups.put(group.id(), group); }
    @Override public void addUserToGroup(DomainIdentifier organizationId, DomainIdentifier groupId, DomainIdentifier userId, Instant now) { groupUsers.computeIfAbsent(groupId, ignored -> new HashSet<>()).add(userId); }
    @Override public void addGroupToGroup(DomainIdentifier organizationId, DomainIdentifier parentGroupId, DomainIdentifier childGroupId, Instant now) { groupChildren.computeIfAbsent(parentGroupId, ignored -> new HashSet<>()).add(childGroupId); }
    @Override
    public void removeUserFromGroup(DomainIdentifier organizationId, DomainIdentifier groupId, DomainIdentifier userId) {
        Set<DomainIdentifier> usersForGroup = groupUsers.get(groupId);
        if (usersForGroup != null) usersForGroup.remove(userId);
    }
    @Override
    public void removeGroupFromGroup(DomainIdentifier organizationId, DomainIdentifier parentGroupId, DomainIdentifier childGroupId) {
        Set<DomainIdentifier> children = groupChildren.get(parentGroupId);
        if (children != null) children.remove(childGroupId);
    }

    @Override
    public boolean wouldCreateGroupCycle(DomainIdentifier organizationId, DomainIdentifier parentGroupId, DomainIdentifier childGroupId) {
        ArrayDeque<DomainIdentifier> pending = new ArrayDeque<>();
        Set<DomainIdentifier> seen = new HashSet<>();
        pending.add(childGroupId);
        while (!pending.isEmpty()) {
            DomainIdentifier current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (current.equals(parentGroupId)) return true;
            pending.addAll(groupChildren.getOrDefault(current, Set.of()));
        }
        return false;
    }

    @Override public long groupMemberCount(DomainIdentifier organizationId, DomainIdentifier groupId) { return groupUsers.getOrDefault(groupId, Set.of()).size() + groupChildren.getOrDefault(groupId, Set.of()).size(); }

    @Override
    public Set<DomainIdentifier> effectiveGroupMembers(DomainIdentifier groupId) {
        IdentityGroup root = groups.get(groupId);
        if (root == null || root.deleted()) return Set.of();
        Set<DomainIdentifier> result = new HashSet<>();
        Set<DomainIdentifier> seen = new HashSet<>();
        ArrayDeque<DomainIdentifier> pending = new ArrayDeque<>();
        pending.add(groupId);
        while (!pending.isEmpty()) {
            DomainIdentifier current = pending.removeFirst();
            if (!seen.add(current)) continue;
            for (DomainIdentifier userId : groupUsers.getOrDefault(current, Set.of())) {
                IdentityUser user = users.get(userId);
                if (user != null && user.status() == IdentityUserStatus.ACTIVE) result.add(userId);
            }
            for (DomainIdentifier child : groupChildren.getOrDefault(current, Set.of())) {
                IdentityGroup group = groups.get(child);
                if (group != null && !group.deleted() && group.organizationId().equals(root.organizationId())) pending.addLast(child);
            }
        }
        return Set.copyOf(result);
    }

    @Override
    public List<Role> listRoles(DomainIdentifier organizationId, int offset, int limit) {
        return roles.values().stream().filter(role -> role.organizationId() == null || role.organizationId().equals(organizationId))
                .sorted(Comparator.comparing(Role::code)).skip(offset).limit(limit).toList();
    }
    @Override public Optional<Role> findRole(DomainIdentifier roleId) { return Optional.ofNullable(roles.get(roleId)); }
    @Override public void insertRole(Role role, Set<String> permissionCodes) { roles.put(role.id(), role); rolePermissions.put(role.id(), Set.copyOf(permissionCodes)); }
    @Override public void updateRole(Role role, Set<String> permissionCodes) { roles.put(role.id(), role); rolePermissions.put(role.id(), Set.copyOf(permissionCodes)); }
    @Override public long activeAssignmentCount(DomainIdentifier roleId, Instant at) { return roleAssignments.values().stream().filter(value -> value.roleId().equals(roleId) && value.effectiveAt(at)).count(); }

    @Override
    public void revokeAssignmentsForRole(DomainIdentifier roleId, DomainIdentifier revokedBy, Instant now) {
        roleAssignments.replaceAll((id, value) -> value.roleId().equals(roleId) && value.effectiveAt(now)
                ? new RoleAssignment(value.id(), value.roleId(), value.actorType(), value.actorId(), value.scope(), value.effectiveFrom(), value.effectiveTo(), now, revokedBy)
                : value);
    }

    @Override
    public List<Permission> listPermissions(DomainIdentifier organizationId, int offset, int limit) {
        return permissions.values().stream().filter(permission -> permission.organizationId() == null || permission.organizationId().equals(organizationId))
                .sorted(Comparator.comparing(Permission::code)).skip(offset).limit(limit).toList();
    }
    @Override public Optional<Permission> findPermission(DomainIdentifier permissionId) { return Optional.ofNullable(permissions.get(permissionId)); }
    @Override public Optional<Permission> findPermissionByCode(DomainIdentifier organizationId, String code) { return permissions.values().stream().filter(permission -> permission.code().equals(code) && (permission.organizationId() == null || permission.organizationId().equals(organizationId))).findFirst(); }
    @Override public void insertPermission(Permission permission) { permissions.put(permission.id(), permission); }
    @Override public void updatePermission(Permission permission) { permissions.put(permission.id(), permission); }

    @Override public List<RoleAssignment> assignments(DomainIdentifier roleId) { return roleAssignments.values().stream().filter(value -> value.roleId().equals(roleId)).toList(); }

    @Override
    public List<RoleAssignment> assignments(DomainIdentifier roleId, int offset, int limit) {
        return roleAssignments.values().stream().filter(value -> value.roleId().equals(roleId))
                .sorted(Comparator.comparing(RoleAssignment::effectiveFrom).thenComparing(RoleAssignment::id))
                .skip(offset).limit(limit).toList();
    }
    @Override public Optional<RoleAssignment> findAssignment(DomainIdentifier assignmentId) { return Optional.ofNullable(roleAssignments.get(assignmentId)); }
    @Override public Set<String> rolePermissionCodes(DomainIdentifier roleId) { return rolePermissions.getOrDefault(roleId, Set.of()); }
    @Override public void insertAssignment(RoleAssignment assignment) { roleAssignments.put(assignment.id(), assignment); }
    @Override public void revokeAssignment(DomainIdentifier assignmentId, DomainIdentifier revokedBy, Instant now) {
        RoleAssignment value = roleAssignments.get(assignmentId);
        roleAssignments.put(assignmentId, new RoleAssignment(value.id(), value.roleId(), value.actorType(), value.actorId(), value.scope(), value.effectiveFrom(), value.effectiveTo(), now, revokedBy));
    }

    @Override
    public boolean hasEffectivePermission(DomainIdentifier userId, String permissionCode, AuthorizationScope scope, Instant at) {
        return effectivePermissionCodes(userId, scope, at).contains(permissionCode);
    }

    @Override
    public Set<String> effectivePermissionCodes(DomainIdentifier userId, AuthorizationScope scope, Instant at) {
        IdentityUser user = users.get(userId);
        if (user == null || user.status() != IdentityUserStatus.ACTIVE) return Set.of();
        if (!mayEvaluateScopedAuthorization(userId, scope, at)) return Set.of();
        TreeSet<String> result = new TreeSet<>();
        for (RoleAssignment assignment : roleAssignments.values()) {
            if (!assignment.effectiveAt(at) || !assignment.scope().covers(scope)) continue;
            if (assignment.actorType() == AssignmentActorType.USER && assignment.actorId().equals(userId)) {
                addRolePermissions(result, assignment.roleId());
            }
        }
        for (DomainIdentifier groupId : effectiveGroupsForUser(userId)) {
            for (RoleAssignment assignment : roleAssignments.values()) {
                if (assignment.actorType() == AssignmentActorType.GROUP && assignment.actorId().equals(groupId)
                        && assignment.effectiveAt(at) && assignment.scope().covers(scope)) addRolePermissions(result, assignment.roleId());
            }
        }
        return Set.copyOf(result);
    }

    @Override
    public boolean hasEffectiveRole(DomainIdentifier userId, DomainIdentifier roleId, AuthorizationScope scope, Instant at) {
        IdentityUser user = users.get(userId);
        if (user == null || user.status() != IdentityUserStatus.ACTIVE) return false;
        if (!mayEvaluateScopedAuthorization(userId, scope, at)) return false;
        for (RoleAssignment assignment : roleAssignments.values()) {
            if (!assignment.roleId().equals(roleId) || !assignment.effectiveAt(at) || !assignment.scope().covers(scope)) continue;
            if (assignment.actorType() == AssignmentActorType.USER && assignment.actorId().equals(userId)) return true;
            if (assignment.actorType() == AssignmentActorType.GROUP && effectiveGroupsForUser(userId).contains(assignment.actorId())) return true;
        }
        return false;
    }

    @Override
    public boolean hasEffectiveSystemRole(DomainIdentifier userId, String roleCode, Instant at) {
        IdentityUser user = users.get(userId);
        if (user == null || user.status() != IdentityUserStatus.ACTIVE) return false;
        return roleAssignments.values().stream().filter(assignment -> assignment.actorType() == AssignmentActorType.USER
                        && assignment.actorId().equals(userId) && assignment.scope().kind() == ScopeKind.PLATFORM && assignment.effectiveAt(at))
                .map(assignment -> roles.get(assignment.roleId())).anyMatch(role -> role != null && role.systemRole() && role.active()
                        && !role.deleted() && role.code().equals(roleCode));
    }

    /** Mirrors the production repository's fail-closed platform administrator exception. */
    private boolean mayEvaluateScopedAuthorization(DomainIdentifier userId, AuthorizationScope scope, Instant at) {
        return scope.kind() == ScopeKind.PLATFORM
                || hasEffectiveMembership(userId, scope, at)
                || hasEffectiveSystemRole(userId, Role.PLATFORM_ADMIN_CODE, at);
    }

    private void addRolePermissions(Set<String> result, DomainIdentifier roleId) {
        Role role = roles.get(roleId);
        if (role == null || !role.active() || role.deleted()) return;
        for (String code : rolePermissions.getOrDefault(roleId, Set.of())) {
            Optional<Permission> permission = permissions.values().stream().filter(value -> value.code().equals(code)).findFirst();
            if (permission.isPresent() && permission.get().active() && !permission.get().deleted()) result.add(code);
        }
    }

    private Set<DomainIdentifier> effectiveGroupsForUser(DomainIdentifier userId) {
        Set<DomainIdentifier> result = new HashSet<>();
        for (Map.Entry<DomainIdentifier, Set<DomainIdentifier>> entry : groupUsers.entrySet()) if (entry.getValue().contains(userId)) result.add(entry.getKey());
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<DomainIdentifier, Set<DomainIdentifier>> entry : groupChildren.entrySet()) {
                if (entry.getValue().stream().anyMatch(result::contains)) changed |= result.add(entry.getKey());
            }
        } while (changed);
        return result;
    }
}
