package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
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
import io.infranexum.identity.access.ports.IdentityAccessRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;

/** JDBC IAM RBAC repository sharing every mutation with the transactional outbox unit of work. */
public final class JdbcIdentityAccessRepository implements IdentityAccessRepository {
    private static final int MAX_EFFECTIVE_GROUPS = 512;
    private final DataSource dataSource;
    private final JdbcConnectionAccess transaction;
    private final JdbcDatabaseDialect dialect;

    public JdbcIdentityAccessRepository(
            DataSource dataSource, JdbcConnectionAccess transaction, JdbcDatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public List<IdentityUser> listUsers(int offset, int limit) {
        return withRead(connection -> {
            String sql = "SELECT id,login,email,display_name,status,created_at,updated_at,deleted_at FROM "
                    + userTable() + " ORDER BY login,id " + pagination();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindPage(statement, offset, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    List<IdentityUser> result = new ArrayList<>();
                    while (rows.next()) result.add(readUser(rows));
                    return List.copyOf(result);
                }
            }
        }, "list IAM users");
    }

    @Override
    public Optional<IdentityUser> findUser(DomainIdentifier id) {
        Objects.requireNonNull(id, "id");
        return withRead(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id,login,email,display_name,status,created_at,updated_at,deleted_at FROM " + userTable() + " WHERE id=?")) {
                dialect.bindIdentifier(statement, 1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(readUser(rows)) : Optional.empty();
                }
            }
        }, "find IAM user");
    }

    @Override
    public Optional<IdentityUser> findUserByLogin(String canonicalLogin) {
        Objects.requireNonNull(canonicalLogin, "canonicalLogin");
        return withRead(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id,login,email,display_name,status,created_at,updated_at,deleted_at FROM " + userTable() + " WHERE login=?")) {
                statement.setString(1, canonicalLogin);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(readUser(rows)) : Optional.empty();
                }
            }
        }, "find IAM user by login");
    }

    @Override
    public void insertUser(IdentityUser user) {
        Objects.requireNonNull(user, "user");
        String sql = "INSERT INTO " + userTable()
                + " (id,login,email,display_name,status,created_at,updated_at,deleted_at) VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = writeConnection().prepareStatement(sql)) {
            bindUser(statement, user);
            requireOne(statement.executeUpdate(), "IAM user insert");
        } catch (SQLException failure) {
            if (dialect.isUniqueViolation(failure)) throw conflict("IAM_LOGIN_CONFLICT", "login or email already exists");
            throw fail("insert IAM user", failure);
        }
    }

    @Override
    public void updateUser(IdentityUser user) {
        Objects.requireNonNull(user, "user");
        String sql = "UPDATE " + userTable() + " SET login=?,email=?,display_name=?,status=?,updated_at=?,deleted_at=? WHERE id=?";
        try (PreparedStatement statement = writeConnection().prepareStatement(sql)) {
            statement.setString(1, user.login()); statement.setString(2, user.email()); statement.setString(3, user.displayName());
            statement.setString(4, user.status().name()); JdbcTemporal.bindInstant(statement, 5, user.updatedAt());
            bindNullableInstant(statement, 6, user.deletedAt()); dialect.bindIdentifier(statement, 7, user.id());
            requireOne(statement.executeUpdate(), "IAM user update");
        } catch (SQLException failure) {
            if (dialect.isUniqueViolation(failure)) throw conflict("IAM_LOGIN_CONFLICT", "login or email already exists");
            throw fail("update IAM user", failure);
        }
    }

    @Override
    public void ensureBootstrapUser(DomainIdentifier id, String login, String displayName, Instant now) {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(now, "now");
        if (findUser(id).isPresent()) return;
        insertUser(new IdentityUser(id, login, null, displayName, IdentityUserStatus.ACTIVE, now, now, null));
    }

    @Override
    public void ensurePlatformAdministrator(DomainIdentifier userId, Instant now) {
        Objects.requireNonNull(userId, "userId"); Objects.requireNonNull(now, "now");
        DomainIdentifier roleId = systemRoleId(Role.PLATFORM_ADMIN_CODE);
        if (hasActiveAssignment(userId, roleId, now)) return;
        String sql = "INSERT INTO " + assignmentTable()
                + " (id,role_id,actor_type,actor_id,scope_kind,organization_id,subdivision_id,effective_from,effective_to,revoked_at,revoked_by)"
                + " VALUES (?,?, 'USER',?,'PLATFORM',NULL,NULL,?,NULL,NULL,NULL)";
        try (PreparedStatement statement = writeConnection().prepareStatement(sql)) {
            // Reusing the bootstrap user UUID is deterministic and safe because assignment IDs have their own namespace.
            dialect.bindIdentifier(statement, 1, userId); dialect.bindIdentifier(statement, 2, roleId);
            dialect.bindIdentifier(statement, 3, userId); JdbcTemporal.bindInstant(statement, 4, now);
            requireOne(statement.executeUpdate(), "platform administrator assignment insert");
        } catch (SQLException failure) {
            if (!dialect.isUniqueViolation(failure)) throw fail("ensure platform administrator", failure);
        }
    }

    @Override
    public List<UserMembership> memberships(DomainIdentifier userId) {
        Objects.requireNonNull(userId, "userId");
        return withRead(connection -> {
            String sql = "SELECT id,user_id,organization_id,subdivision_id,effective_from,effective_to,revoked_at FROM "
                    + membershipTable() + " WHERE user_id=? ORDER BY effective_from,id";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                dialect.bindIdentifier(statement, 1, userId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<UserMembership> result = new ArrayList<>(); while (rows.next()) result.add(readMembership(rows)); return List.copyOf(result);
                }
            }
        }, "list IAM memberships");
    }

    @Override
    public void insertMembership(UserMembership membership) {
        Objects.requireNonNull(membership, "membership");
        String sql = "INSERT INTO " + membershipTable()
                + " (id,user_id,organization_id,subdivision_id,effective_from,effective_to,revoked_at) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement statement = writeConnection().prepareStatement(sql)) {
            dialect.bindIdentifier(statement,1,membership.id()); dialect.bindIdentifier(statement,2,membership.userId());
            dialect.bindIdentifier(statement,3,membership.organizationId()); dialect.bindNullableIdentifier(statement,4,membership.subdivisionId());
            JdbcTemporal.bindInstant(statement,5,membership.effectiveFrom()); bindNullableInstant(statement,6,membership.effectiveTo());
            bindNullableInstant(statement,7,membership.revokedAt()); requireOne(statement.executeUpdate(),"membership insert");
        } catch (SQLException failure) { throw fail("insert IAM membership",failure); }
    }

    @Override
    public boolean hasEffectiveMembership(DomainIdentifier userId, AuthorizationScope scope, Instant at) {
        Objects.requireNonNull(userId,"userId"); Objects.requireNonNull(scope,"scope"); Objects.requireNonNull(at,"at");
        if (!isActiveUser(userId) || scope.kind()==ScopeKind.PLATFORM) return false;
        return withRead(connection -> {
            StringBuilder sql=new StringBuilder("SELECT subdivision_id FROM ").append(membershipTable())
                    .append(" WHERE user_id=? AND organization_id=? AND revoked_at IS NULL AND effective_from<=? AND (effective_to IS NULL OR effective_to>?)");
            try(PreparedStatement statement=connection.prepareStatement(sql.toString())){
                dialect.bindIdentifier(statement,1,userId); dialect.bindIdentifier(statement,2,scope.organizationId());
                JdbcTemporal.bindInstant(statement,3,at); JdbcTemporal.bindInstant(statement,4,at);
                try(ResultSet rows=statement.executeQuery()){
                    while(rows.next()){
                        DomainIdentifier subdivision=nullableIdentifier(rows,"subdivision_id");
                        if(scope.kind()==ScopeKind.ORGANIZATION || subdivision==null || subdivision.equals(scope.subdivisionId())) return true;
                    }
                    return false;
                }
            }
        },"evaluate IAM membership");
    }

    @Override
    public List<IdentityGroup> listGroups(DomainIdentifier organizationId,int offset,int limit) {
        Objects.requireNonNull(organizationId,"organizationId");
        return withRead(connection -> {
            String sql="SELECT id,organization_id,code,display_name,system_group,created_at,updated_at,deleted_at FROM "+groupTable()
                    +" WHERE organization_id=? ORDER BY code,id "+pagination();
            try(PreparedStatement statement=connection.prepareStatement(sql)){
                dialect.bindIdentifier(statement,1,organizationId); bindPage(statement,offset,limit,2);
                try(ResultSet rows=statement.executeQuery()){List<IdentityGroup> result=new ArrayList<>();while(rows.next())result.add(readGroup(rows));return List.copyOf(result);}
            }
        },"list IAM groups");
    }

    @Override
    public Optional<IdentityGroup> findGroup(DomainIdentifier organizationId,DomainIdentifier groupId) {
        Objects.requireNonNull(organizationId,"organizationId"); Objects.requireNonNull(groupId,"groupId");
        return withRead(connection -> {
            try(PreparedStatement statement=connection.prepareStatement("SELECT id,organization_id,code,display_name,system_group,created_at,updated_at,deleted_at FROM "+groupTable()+" WHERE organization_id=? AND id=?")){
                dialect.bindIdentifier(statement,1,organizationId);dialect.bindIdentifier(statement,2,groupId);
                try(ResultSet rows=statement.executeQuery()){return rows.next()?Optional.of(readGroup(rows)):Optional.empty();}
            }
        },"find IAM group");
    }

    @Override
    public Optional<IdentityGroup> findGroup(DomainIdentifier groupId) {
        Objects.requireNonNull(groupId, "groupId");
        return withRead(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id,organization_id,code,display_name,system_group,created_at,updated_at,deleted_at FROM "
                            + groupTable() + " WHERE id=?")) {
                dialect.bindIdentifier(statement, 1, groupId);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(readGroup(rows)) : Optional.empty();
                }
            }
        }, "find IAM group globally");
    }

    @Override
    public void insertGroup(IdentityGroup group) {
        String sql="INSERT INTO "+groupTable()+" (id,organization_id,code,display_name,system_group,created_at,updated_at,deleted_at) VALUES (?,?,?,?,?,?,?,?)";
        try(PreparedStatement statement=writeConnection().prepareStatement(sql)){
            dialect.bindIdentifier(statement,1,group.id());dialect.bindIdentifier(statement,2,group.organizationId());statement.setString(3,group.code());statement.setString(4,group.displayName());
            bindBoolean(statement,5,group.systemGroup());JdbcTemporal.bindInstant(statement,6,group.createdAt());JdbcTemporal.bindInstant(statement,7,group.updatedAt());bindNullableInstant(statement,8,group.deletedAt());
            requireOne(statement.executeUpdate(),"IAM group insert");
        }catch(SQLException failure){if(dialect.isUniqueViolation(failure))throw conflict("IAM_GROUP_CODE_CONFLICT","group code already exists in organization");throw fail("insert IAM group",failure);}
    }

    @Override
    public void updateGroup(IdentityGroup group) {
        try(PreparedStatement statement=writeConnection().prepareStatement("UPDATE "+groupTable()+" SET display_name=?,updated_at=?,deleted_at=? WHERE id=? AND organization_id=?")){
            statement.setString(1,group.displayName());JdbcTemporal.bindInstant(statement,2,group.updatedAt());bindNullableInstant(statement,3,group.deletedAt());dialect.bindIdentifier(statement,4,group.id());dialect.bindIdentifier(statement,5,group.organizationId());
            requireOne(statement.executeUpdate(),"IAM group update");
        }catch(SQLException failure){throw fail("update IAM group",failure);}
    }

    @Override
    public void addUserToGroup(DomainIdentifier orgId,DomainIdentifier groupId,DomainIdentifier userId,Instant now){
        insertMembershipEdge(groupUserTable(),"group_id","user_id",orgId,groupId,userId,now);
    }

    @Override
    public void addGroupToGroup(DomainIdentifier orgId,DomainIdentifier parent,DomainIdentifier child,Instant now){
        insertMembershipEdge(groupGroupTable(),"parent_group_id","child_group_id",orgId,parent,child,now);
    }

    @Override
    public void removeUserFromGroup(DomainIdentifier orgId,DomainIdentifier groupId,DomainIdentifier userId){deleteEdge(groupUserTable(),"group_id","user_id",orgId,groupId,userId);}
    @Override
    public void removeGroupFromGroup(DomainIdentifier orgId,DomainIdentifier parent,DomainIdentifier child){deleteEdge(groupGroupTable(),"parent_group_id","child_group_id",orgId,parent,child);}

    @Override
    public boolean wouldCreateGroupCycle(DomainIdentifier orgId,DomainIdentifier parent,DomainIdentifier child){
        Objects.requireNonNull(orgId,"orgId"); if(parent.equals(child))return true;
        Set<DomainIdentifier> seen=new HashSet<>(); ArrayDeque<DomainIdentifier> queue=new ArrayDeque<>();queue.add(parent);
        while(!queue.isEmpty()){
            DomainIdentifier current=queue.removeFirst();if(!seen.add(current))continue;if(seen.size()>MAX_EFFECTIVE_GROUPS)throw conflict("IAM_GROUP_GRAPH_TOO_LARGE","group graph exceeds safety bound");
            if(current.equals(child))return true;
            // Existing parents of current represent paths current -> ... upward. Adding parent <- child cycles if child is already an ancestor of parent.
            queue.addAll(parentGroups(orgId,current));
        }
        return false;
    }


    @Override
    public long groupMemberCount(DomainIdentifier organizationId, DomainIdentifier groupId) {
        Objects.requireNonNull(organizationId, "organizationId"); Objects.requireNonNull(groupId, "groupId");
        return withRead(connection -> {
            String sql = "SELECT (SELECT COUNT(*) FROM " + groupUserTable()
                    + " WHERE organization_id=? AND group_id=?) + (SELECT COUNT(*) FROM " + groupGroupTable()
                    + " WHERE organization_id=? AND parent_group_id=?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                dialect.bindIdentifier(statement, 1, organizationId); dialect.bindIdentifier(statement, 2, groupId);
                dialect.bindIdentifier(statement, 3, organizationId); dialect.bindIdentifier(statement, 4, groupId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new SQLException("group member count returned no row");
                    return rows.getLong(1);
                }
            }
        }, "count IAM group members");
    }

    @Override
    public Set<DomainIdentifier> effectiveGroupMembers(DomainIdentifier groupId) {
        Objects.requireNonNull(groupId, "groupId");
        DomainIdentifier organizationId = groupOrganization(groupId);
        Set<DomainIdentifier> users = new TreeSet<>();
        Set<DomainIdentifier> visited = new HashSet<>();
        ArrayDeque<DomainIdentifier> queue = new ArrayDeque<>();
        queue.add(groupId);
        while (!queue.isEmpty()) {
            DomainIdentifier current = queue.removeFirst();
            if (!visited.add(current)) continue;
            if (visited.size() > MAX_EFFECTIVE_GROUPS) {
                throw conflict("IAM_GROUP_GRAPH_TOO_LARGE", "effective member graph exceeds safety bound");
            }
            users.addAll(directActiveUsers(organizationId, current));
            queue.addAll(childGroups(organizationId, current));
        }
        return Set.copyOf(users);
    }

    @Override
    public List<Role> listRoles(DomainIdentifier orgId,int offset,int limit){
        return withRead(connection->{String where=orgId==null?"organization_id IS NULL":"(organization_id=? OR organization_id IS NULL)";
            String sql="SELECT id,organization_id,code,display_name,scope_kind,system_role,active,created_at,updated_at,deleted_at FROM "+roleTable()+" WHERE "+where+" ORDER BY system_role DESC,code,id "+pagination();
            try(PreparedStatement statement=connection.prepareStatement(sql)){int first=1;if(orgId!=null){dialect.bindIdentifier(statement,first++,orgId);}bindPage(statement,offset,limit,first);
                try(ResultSet rows=statement.executeQuery()){List<Role> result=new ArrayList<>();while(rows.next())result.add(readRole(rows));return List.copyOf(result);}}
        },"list IAM roles");
    }

    @Override
    public Optional<Role> findRole(DomainIdentifier roleId){Objects.requireNonNull(roleId,"roleId");return withRead(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT id,organization_id,code,display_name,scope_kind,system_role,active,created_at,updated_at,deleted_at FROM "+roleTable()+" WHERE id=?")){dialect.bindIdentifier(statement,1,roleId);try(ResultSet rows=statement.executeQuery()){return rows.next()?Optional.of(readRole(rows)):Optional.empty();}}},"find IAM role");}

    @Override
    public void insertRole(Role role,Set<String> permissionCodes){
        Objects.requireNonNull(role,"role");Set<DomainIdentifier> permissionIds=resolvePermissionIds(role.organizationId(),permissionCodes);
        try(PreparedStatement statement=writeConnection().prepareStatement("INSERT INTO "+roleTable()+" (id,organization_id,code,display_name,scope_kind,system_role,active,created_at,updated_at,deleted_at) VALUES (?,?,?,?,?,?,?,?,?,?)")){
            dialect.bindIdentifier(statement,1,role.id());dialect.bindNullableIdentifier(statement,2,role.organizationId());statement.setString(3,role.code());statement.setString(4,role.displayName());statement.setString(5,role.scopeKind().name());bindBoolean(statement,6,role.systemRole());bindBoolean(statement,7,role.active());JdbcTemporal.bindInstant(statement,8,role.createdAt());JdbcTemporal.bindInstant(statement,9,role.updatedAt());bindNullableInstant(statement,10,role.deletedAt());requireOne(statement.executeUpdate(),"IAM role insert");
            replaceRolePermissions(role.id(),permissionIds);
        }catch(SQLException failure){if(dialect.isUniqueViolation(failure))throw conflict("IAM_ROLE_CODE_CONFLICT","role code already exists in scope");throw fail("insert IAM role",failure);}
    }

    @Override
    public void updateRole(Role role,Set<String> permissionCodes){
        Objects.requireNonNull(role,"role");Set<DomainIdentifier> ids=role.deleted()?Set.of():resolvePermissionIds(role.organizationId(),permissionCodes);
        try(PreparedStatement statement=writeConnection().prepareStatement("UPDATE "+roleTable()+" SET code=?,display_name=?,active=?,updated_at=?,deleted_at=? WHERE id=?")){
            statement.setString(1,role.code());statement.setString(2,role.displayName());bindBoolean(statement,3,role.active());JdbcTemporal.bindInstant(statement,4,role.updatedAt());bindNullableInstant(statement,5,role.deletedAt());dialect.bindIdentifier(statement,6,role.id());requireOne(statement.executeUpdate(),"IAM role update");replaceRolePermissions(role.id(),ids);
        }catch(SQLException failure){if(dialect.isUniqueViolation(failure))throw conflict("IAM_ROLE_CODE_CONFLICT","role code already exists in scope");throw fail("update IAM role",failure);}
    }

    @Override
    public long activeAssignmentCount(DomainIdentifier roleId,Instant at){return withRead(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT COUNT(*) FROM "+assignmentTable()+" WHERE role_id=? AND revoked_at IS NULL AND effective_from<=? AND (effective_to IS NULL OR effective_to>?)")){dialect.bindIdentifier(statement,1,roleId);JdbcTemporal.bindInstant(statement,2,at);JdbcTemporal.bindInstant(statement,3,at);try(ResultSet rows=statement.executeQuery()){if(!rows.next())throw new SQLException("assignment count returned no row");return rows.getLong(1);}}},"count IAM assignments");}

    @Override
    public void revokeAssignmentsForRole(DomainIdentifier roleId,DomainIdentifier revokedBy,Instant now){try(PreparedStatement statement=writeConnection().prepareStatement("UPDATE "+assignmentTable()+" SET revoked_at=?,revoked_by=? WHERE role_id=? AND revoked_at IS NULL AND effective_from<=? AND (effective_to IS NULL OR effective_to>?)")){JdbcTemporal.bindInstant(statement,1,now);dialect.bindIdentifier(statement,2,revokedBy);dialect.bindIdentifier(statement,3,roleId);JdbcTemporal.bindInstant(statement,4,now);JdbcTemporal.bindInstant(statement,5,now);statement.executeUpdate();}catch(SQLException failure){throw fail("revoke role assignments",failure);}}

    @Override
    public List<Permission> listPermissions(DomainIdentifier orgId,int offset,int limit){return withRead(connection->{String where=orgId==null?"organization_id IS NULL":"(organization_id=? OR organization_id IS NULL)";String sql="SELECT id,organization_id,code,resource_type,action_name,sensitivity,scope_kind,system_defined,active,created_at,updated_at,deleted_at FROM "+permissionTable()+" WHERE "+where+" ORDER BY system_defined DESC,code,id "+pagination();try(PreparedStatement statement=connection.prepareStatement(sql)){int first=1;if(orgId!=null)dialect.bindIdentifier(statement,first++,orgId);bindPage(statement,offset,limit,first);try(ResultSet rows=statement.executeQuery()){List<Permission> result=new ArrayList<>();while(rows.next())result.add(readPermission(rows));return List.copyOf(result);}}},"list IAM permissions");}
    @Override
    public Optional<Permission> findPermission(DomainIdentifier id){return withRead(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT id,organization_id,code,resource_type,action_name,sensitivity,scope_kind,system_defined,active,created_at,updated_at,deleted_at FROM "+permissionTable()+" WHERE id=?")){dialect.bindIdentifier(statement,1,id);try(ResultSet rows=statement.executeQuery()){return rows.next()?Optional.of(readPermission(rows)):Optional.empty();}}},"find IAM permission");}
    @Override
    public Optional<Permission> findPermissionByCode(DomainIdentifier orgId,String code){return withRead(connection->{String sql="SELECT id,organization_id,code,resource_type,action_name,sensitivity,scope_kind,system_defined,active,created_at,updated_at,deleted_at FROM "+permissionTable()+" WHERE code=? AND active="+trueLiteral()+" AND deleted_at IS NULL AND "+(orgId==null?"organization_id IS NULL":"(organization_id=? OR organization_id IS NULL)")+" ORDER BY system_defined ASC";try(PreparedStatement statement=connection.prepareStatement(sql)){statement.setString(1,code);if(orgId!=null)dialect.bindIdentifier(statement,2,orgId);try(ResultSet rows=statement.executeQuery()){if(!rows.next())return Optional.empty();Permission first=readPermission(rows);if(rows.next())throw conflict("IAM_PERMISSION_AMBIGUOUS","permission code resolves to multiple active definitions");return Optional.of(first);}}},"find IAM permission by code");}

    @Override
    public void insertPermission(Permission permission){Objects.requireNonNull(permission,"permission");if(anyPermissionCode(permission.code()))throw conflict("IAM_PERMISSION_CODE_CONFLICT","permission code already exists");try(PreparedStatement statement=writeConnection().prepareStatement("INSERT INTO "+permissionTable()+" (id,organization_id,code,resource_type,action_name,sensitivity,scope_kind,system_defined,active,created_at,updated_at,deleted_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")){bindPermission(statement,permission);requireOne(statement.executeUpdate(),"IAM permission insert");}catch(SQLException failure){if(dialect.isUniqueViolation(failure))throw conflict("IAM_PERMISSION_CODE_CONFLICT","permission code already exists");throw fail("insert IAM permission",failure);}}
    @Override
    public void updatePermission(Permission permission){try(PreparedStatement statement=writeConnection().prepareStatement("UPDATE "+permissionTable()+" SET resource_type=?,action_name=?,sensitivity=?,scope_kind=?,active=?,updated_at=?,deleted_at=? WHERE id=?")){statement.setString(1,permission.resourceType());statement.setString(2,permission.action());statement.setString(3,permission.sensitivity());statement.setString(4,permission.scopeKind().name());bindBoolean(statement,5,permission.active());JdbcTemporal.bindInstant(statement,6,permission.updatedAt());bindNullableInstant(statement,7,permission.deletedAt());dialect.bindIdentifier(statement,8,permission.id());requireOne(statement.executeUpdate(),"IAM permission update");}catch(SQLException failure){throw fail("update IAM permission",failure);}}

    @Override
    public List<RoleAssignment> assignments(DomainIdentifier roleId){return withRead(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT id,role_id,actor_type,actor_id,scope_kind,organization_id,subdivision_id,effective_from,effective_to,revoked_at,revoked_by FROM "+assignmentTable()+" WHERE role_id=? ORDER BY effective_from,id")){dialect.bindIdentifier(statement,1,roleId);try(ResultSet rows=statement.executeQuery()){List<RoleAssignment> result=new ArrayList<>();while(rows.next())result.add(readAssignment(rows));return List.copyOf(result);}}},"list IAM role assignments");}
    @Override
    public Optional<RoleAssignment> findAssignment(DomainIdentifier assignmentId){return withRead(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT id,role_id,actor_type,actor_id,scope_kind,organization_id,subdivision_id,effective_from,effective_to,revoked_at,revoked_by FROM "+assignmentTable()+" WHERE id=?")){dialect.bindIdentifier(statement,1,assignmentId);try(ResultSet rows=statement.executeQuery()){return rows.next()?Optional.of(readAssignment(rows)):Optional.empty();}}},"find IAM role assignment");}
    @Override
    public Set<String> rolePermissionCodes(DomainIdentifier roleId){return withRead(connection->{String sql="SELECT p.code FROM "+rolePermissionTable()+" rp JOIN "+permissionTable()+" p ON p.id=rp.permission_id WHERE rp.role_id=? AND p.deleted_at IS NULL ORDER BY p.code";try(PreparedStatement statement=connection.prepareStatement(sql)){dialect.bindIdentifier(statement,1,roleId);try(ResultSet rows=statement.executeQuery()){Set<String> result=new TreeSet<>();while(rows.next())result.add(rows.getString("code"));return Set.copyOf(result);}}},"list IAM role permissions");}
    @Override
    public void insertAssignment(RoleAssignment assignment){try(PreparedStatement statement=writeConnection().prepareStatement("INSERT INTO "+assignmentTable()+" (id,role_id,actor_type,actor_id,scope_kind,organization_id,subdivision_id,effective_from,effective_to,revoked_at,revoked_by) VALUES (?,?,?,?,?,?,?,?,?,?,?)")){bindAssignment(statement,assignment);requireOne(statement.executeUpdate(),"IAM role assignment insert");}catch(SQLException failure){if(dialect.isUniqueViolation(failure))throw conflict("IAM_ASSIGNMENT_CONFLICT","role assignment already exists");throw fail("insert IAM role assignment",failure);}}
    @Override
    public void revokeAssignment(DomainIdentifier assignmentId,DomainIdentifier revokedBy,Instant now){try(PreparedStatement statement=writeConnection().prepareStatement("UPDATE "+assignmentTable()+" SET revoked_at=?,revoked_by=? WHERE id=? AND revoked_at IS NULL")){JdbcTemporal.bindInstant(statement,1,now);dialect.bindIdentifier(statement,2,revokedBy);dialect.bindIdentifier(statement,3,assignmentId);requireOne(statement.executeUpdate(),"IAM role assignment revoke");}catch(SQLException failure){throw fail("revoke IAM role assignment",failure);}}

    @Override
    public boolean hasEffectivePermission(DomainIdentifier userId,String permissionCode,AuthorizationScope scope,Instant at){
        Objects.requireNonNull(userId,"userId"); Objects.requireNonNull(permissionCode,"permissionCode"); Objects.requireNonNull(scope,"scope"); Objects.requireNonNull(at,"at");
        if(!isActiveUser(userId)) return false;
        if(scope.kind()!=ScopeKind.PLATFORM && !hasEffectiveMembership(userId,scope,at)) return false;
        if(assignmentGrants("USER",userId,permissionCode,scope,at))return true;
        for(DomainIdentifier group:effectiveGroups(userId))if(assignmentGrants("GROUP",group,permissionCode,scope,at))return true;
        return false;
    }


    @Override
    public Set<String> effectivePermissionCodes(DomainIdentifier userId, AuthorizationScope scope, Instant at) {
        Objects.requireNonNull(userId, "userId"); Objects.requireNonNull(scope, "scope"); Objects.requireNonNull(at, "at");
        if (!isActiveUser(userId)) return Set.of();
        if (scope.kind()!=ScopeKind.PLATFORM && !hasEffectiveMembership(userId, scope, at)) return Set.of();
        TreeSet<String> codes = new TreeSet<>();
        codes.addAll(grantedPermissionCodes("USER", userId, scope, at));
        for (DomainIdentifier group : effectiveGroups(userId)) {
            codes.addAll(grantedPermissionCodes("GROUP", group, scope, at));
        }
        return Set.copyOf(codes);
    }

    @Override
    public boolean hasEffectiveSystemRole(DomainIdentifier userId,String roleCode,Instant at){if(!isActiveUser(userId)) return false; return withRead(connection->{String sql="SELECT 1 FROM "+assignmentTable()+" a JOIN "+roleTable()+" r ON r.id=a.role_id WHERE a.actor_type='USER' AND a.actor_id=? AND a.scope_kind='PLATFORM' AND a.revoked_at IS NULL AND a.effective_from<=? AND (a.effective_to IS NULL OR a.effective_to>?) AND r.code=? AND r.system_role="+trueLiteral()+" AND r.active="+trueLiteral()+" AND r.deleted_at IS NULL";try(PreparedStatement statement=connection.prepareStatement(sql)){dialect.bindIdentifier(statement,1,userId);JdbcTemporal.bindInstant(statement,2,at);JdbcTemporal.bindInstant(statement,3,at);statement.setString(4,roleCode);try(ResultSet rows=statement.executeQuery()){return rows.next();}}},"evaluate IAM system role");}

    private boolean assignmentGrants(String actorType,DomainIdentifier actorId,String permissionCode,AuthorizationScope requested,Instant at){
        return withRead(connection->{String sql="SELECT a.scope_kind,a.organization_id,a.subdivision_id FROM "+assignmentTable()+" a JOIN "+roleTable()+" r ON r.id=a.role_id JOIN "+rolePermissionTable()+" rp ON rp.role_id=r.id JOIN "+permissionTable()+" p ON p.id=rp.permission_id WHERE a.actor_type=? AND a.actor_id=? AND a.revoked_at IS NULL AND a.effective_from<=? AND (a.effective_to IS NULL OR a.effective_to>?) AND r.active="+trueLiteral()+" AND r.deleted_at IS NULL AND p.code=? AND p.active="+trueLiteral()+" AND p.deleted_at IS NULL";try(PreparedStatement statement=connection.prepareStatement(sql)){statement.setString(1,actorType);dialect.bindIdentifier(statement,2,actorId);JdbcTemporal.bindInstant(statement,3,at);JdbcTemporal.bindInstant(statement,4,at);statement.setString(5,permissionCode);try(ResultSet rows=statement.executeQuery()){while(rows.next())if(readScope(rows).covers(requested))return true;return false;}}},"evaluate IAM permission");
    }

    private Set<DomainIdentifier> effectiveGroups(DomainIdentifier userId){
        Set<DomainIdentifier> groups=new HashSet<>(directGroups(userId));ArrayDeque<DomainIdentifier> queue=new ArrayDeque<>(groups);
        while(!queue.isEmpty()){
            DomainIdentifier child=queue.removeFirst();for(DomainIdentifier parent:parentGroups(null,child)){if(groups.add(parent)){if(groups.size()>MAX_EFFECTIVE_GROUPS)throw conflict("IAM_GROUP_GRAPH_TOO_LARGE","effective group graph exceeds safety bound");queue.addLast(parent);}}
        }
        return Set.copyOf(groups);
    }

    private List<DomainIdentifier> directGroups(DomainIdentifier userId){return withRead(connection->{String sql="SELECT m.group_id FROM "+groupUserTable()+" m JOIN "+groupTable()+" g ON g.id=m.group_id WHERE m.user_id=? AND g.deleted_at IS NULL";try(PreparedStatement statement=connection.prepareStatement(sql)){dialect.bindIdentifier(statement,1,userId);try(ResultSet rows=statement.executeQuery()){List<DomainIdentifier> result=new ArrayList<>();while(rows.next())result.add(dialect.readIdentifier(rows,"group_id"));return result;}}},"load direct IAM groups");}
    private List<DomainIdentifier> parentGroups(DomainIdentifier orgId,DomainIdentifier child){return withRead(connection->{String sql="SELECT m.parent_group_id FROM "+groupGroupTable()+" m JOIN "+groupTable()+" g ON g.id=m.parent_group_id WHERE m.child_group_id=? AND g.deleted_at IS NULL"+(orgId==null?"":" AND m.organization_id=?");try(PreparedStatement statement=connection.prepareStatement(sql)){dialect.bindIdentifier(statement,1,child);if(orgId!=null)dialect.bindIdentifier(statement,2,orgId);try(ResultSet rows=statement.executeQuery()){List<DomainIdentifier> result=new ArrayList<>();while(rows.next())result.add(dialect.readIdentifier(rows,"parent_group_id"));return result;}}},"load parent IAM groups");}



    private Set<String> grantedPermissionCodes(String actorType, DomainIdentifier actorId, AuthorizationScope requested, Instant at) {
        return withRead(connection -> {
            String sql = "SELECT DISTINCT p.code,a.scope_kind,a.organization_id,a.subdivision_id FROM " + assignmentTable()
                    + " a JOIN " + roleTable() + " r ON r.id=a.role_id JOIN " + rolePermissionTable()
                    + " rp ON rp.role_id=r.id JOIN " + permissionTable()
                    + " p ON p.id=rp.permission_id WHERE a.actor_type=? AND a.actor_id=?"
                    + " AND a.revoked_at IS NULL AND a.effective_from<=? AND (a.effective_to IS NULL OR a.effective_to>?)"
                    + " AND r.active=" + trueLiteral() + " AND r.deleted_at IS NULL"
                    + " AND p.active=" + trueLiteral() + " AND p.deleted_at IS NULL";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, actorType); dialect.bindIdentifier(statement, 2, actorId);
                JdbcTemporal.bindInstant(statement, 3, at); JdbcTemporal.bindInstant(statement, 4, at);
                try (ResultSet rows = statement.executeQuery()) {
                    TreeSet<String> result = new TreeSet<>();
                    while (rows.next()) if (readScope(rows).covers(requested)) result.add(rows.getString("code"));
                    return Set.copyOf(result);
                }
            }
        }, "resolve effective IAM permissions");
    }

    private DomainIdentifier groupOrganization(DomainIdentifier groupId) {
        return withRead(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT organization_id FROM " + groupTable() + " WHERE id=? AND deleted_at IS NULL")) {
                dialect.bindIdentifier(statement, 1, groupId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw conflict("IAM_GROUP_NOT_FOUND", "group not found");
                    return dialect.readIdentifier(rows, "organization_id");
                }
            }
        }, "resolve IAM group organization");
    }

    private List<DomainIdentifier> directActiveUsers(DomainIdentifier organizationId, DomainIdentifier groupId) {
        return withRead(connection -> {
            String sql = "SELECT m.user_id FROM " + groupUserTable() + " m JOIN " + userTable()
                    + " u ON u.id=m.user_id WHERE m.organization_id=? AND m.group_id=?"
                    + " AND u.status='ACTIVE' AND u.deleted_at IS NULL ORDER BY m.user_id";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                dialect.bindIdentifier(statement, 1, organizationId); dialect.bindIdentifier(statement, 2, groupId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<DomainIdentifier> result = new ArrayList<>();
                    while (rows.next()) result.add(dialect.readIdentifier(rows, "user_id"));
                    return result;
                }
            }
        }, "load direct active IAM group users");
    }

    private List<DomainIdentifier> childGroups(DomainIdentifier organizationId, DomainIdentifier parentGroupId) {
        return withRead(connection -> {
            String sql = "SELECT m.child_group_id FROM " + groupGroupTable() + " m JOIN " + groupTable()
                    + " g ON g.id=m.child_group_id WHERE m.organization_id=? AND m.parent_group_id=?"
                    + " AND g.deleted_at IS NULL ORDER BY m.child_group_id";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                dialect.bindIdentifier(statement, 1, organizationId); dialect.bindIdentifier(statement, 2, parentGroupId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<DomainIdentifier> result = new ArrayList<>();
                    while (rows.next()) result.add(dialect.readIdentifier(rows, "child_group_id"));
                    return result;
                }
            }
        }, "load child IAM groups");
    }

    private boolean isActiveUser(DomainIdentifier userId){return withRead(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT 1 FROM "+userTable()+" WHERE id=? AND status='ACTIVE' AND deleted_at IS NULL")){dialect.bindIdentifier(statement,1,userId);try(ResultSet rows=statement.executeQuery()){return rows.next();}}},"check active IAM user");}

    private DomainIdentifier systemRoleId(String code){return withRead(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT id FROM "+roleTable()+" WHERE organization_id IS NULL AND code=? AND system_role="+trueLiteral()+" AND active="+trueLiteral()+" AND deleted_at IS NULL")){statement.setString(1,code);try(ResultSet rows=statement.executeQuery()){if(!rows.next())throw conflict("IAM_SYSTEM_ROLE_MISSING","required system role is not seeded");return dialect.readIdentifier(rows,"id");}}},"find system IAM role");}
    private boolean hasActiveAssignment(DomainIdentifier userId,DomainIdentifier roleId,Instant at){return withRead(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT 1 FROM "+assignmentTable()+" WHERE actor_type='USER' AND actor_id=? AND role_id=? AND revoked_at IS NULL AND effective_from<=? AND (effective_to IS NULL OR effective_to>?)")){dialect.bindIdentifier(statement,1,userId);dialect.bindIdentifier(statement,2,roleId);JdbcTemporal.bindInstant(statement,3,at);JdbcTemporal.bindInstant(statement,4,at);try(ResultSet rows=statement.executeQuery()){return rows.next();}}},"check platform administrator assignment");}
    private boolean anyPermissionCode(String code){return withRead(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT 1 FROM "+permissionTable()+" WHERE code=? AND deleted_at IS NULL")){statement.setString(1,code);try(ResultSet rows=statement.executeQuery()){return rows.next();}}},"check permission code");}

    private Set<DomainIdentifier> resolvePermissionIds(DomainIdentifier orgId,Set<String> codes){TreeSet<DomainIdentifier> ids=new TreeSet<>();for(String code:codes){Permission p=findPermissionByCode(orgId,code).orElseThrow(()->conflict("IAM_PERMISSION_NOT_FOUND","permission not found: "+code));ids.add(p.id());}return Set.copyOf(ids);}
    private void replaceRolePermissions(DomainIdentifier roleId,Set<DomainIdentifier> permissionIds)throws SQLException{try(PreparedStatement delete=writeConnection().prepareStatement("DELETE FROM "+rolePermissionTable()+" WHERE role_id=?")){dialect.bindIdentifier(delete,1,roleId);delete.executeUpdate();}String sql="INSERT INTO "+rolePermissionTable()+" (role_id,permission_id) VALUES (?,?)";try(PreparedStatement insert=writeConnection().prepareStatement(sql)){for(DomainIdentifier permissionId:permissionIds){dialect.bindIdentifier(insert,1,roleId);dialect.bindIdentifier(insert,2,permissionId);insert.addBatch();}if(!permissionIds.isEmpty())insert.executeBatch();}}

    private void insertMembershipEdge(String table,String left,String right,DomainIdentifier orgId,DomainIdentifier leftId,DomainIdentifier rightId,Instant now){String sql="INSERT INTO "+table+" (organization_id,"+left+","+right+",created_at) VALUES (?,?,?,?)";try(PreparedStatement statement=writeConnection().prepareStatement(sql)){dialect.bindIdentifier(statement,1,orgId);dialect.bindIdentifier(statement,2,leftId);dialect.bindIdentifier(statement,3,rightId);JdbcTemporal.bindInstant(statement,4,now);requireOne(statement.executeUpdate(),"IAM membership edge insert");}catch(SQLException failure){if(!dialect.isUniqueViolation(failure))throw fail("insert IAM membership edge",failure);}}
    private void deleteEdge(String table,String left,String right,DomainIdentifier orgId,DomainIdentifier leftId,DomainIdentifier rightId){String sql="DELETE FROM "+table+" WHERE organization_id=? AND "+left+"=? AND "+right+"=?";try(PreparedStatement statement=writeConnection().prepareStatement(sql)){dialect.bindIdentifier(statement,1,orgId);dialect.bindIdentifier(statement,2,leftId);dialect.bindIdentifier(statement,3,rightId);statement.executeUpdate();}catch(SQLException failure){throw fail("remove IAM membership edge",failure);}}

    private IdentityUser readUser(ResultSet r)throws SQLException{return new IdentityUser(dialect.readIdentifier(r,"id"),r.getString("login"),r.getString("email"),r.getString("display_name"),IdentityUserStatus.valueOf(r.getString("status")),JdbcTemporal.readRequired(r,"created_at"),JdbcTemporal.readRequired(r,"updated_at"),JdbcTemporal.readNullable(r,"deleted_at"));}
    private UserMembership readMembership(ResultSet r)throws SQLException{return new UserMembership(dialect.readIdentifier(r,"id"),dialect.readIdentifier(r,"user_id"),dialect.readIdentifier(r,"organization_id"),nullableIdentifier(r,"subdivision_id"),JdbcTemporal.readRequired(r,"effective_from"),JdbcTemporal.readNullable(r,"effective_to"),JdbcTemporal.readNullable(r,"revoked_at"));}
    private IdentityGroup readGroup(ResultSet r)throws SQLException{return new IdentityGroup(dialect.readIdentifier(r,"id"),dialect.readIdentifier(r,"organization_id"),r.getString("code"),r.getString("display_name"),readBoolean(r,"system_group"),JdbcTemporal.readRequired(r,"created_at"),JdbcTemporal.readRequired(r,"updated_at"),JdbcTemporal.readNullable(r,"deleted_at"));}
    private Permission readPermission(ResultSet r)throws SQLException{return new Permission(dialect.readIdentifier(r,"id"),nullableIdentifier(r,"organization_id"),r.getString("code"),r.getString("resource_type"),r.getString("action_name"),r.getString("sensitivity"),ScopeKind.valueOf(r.getString("scope_kind")),readBoolean(r,"system_defined"),readBoolean(r,"active"),JdbcTemporal.readRequired(r,"created_at"),JdbcTemporal.readRequired(r,"updated_at"),JdbcTemporal.readNullable(r,"deleted_at"));}
    private Role readRole(ResultSet r)throws SQLException{return new Role(dialect.readIdentifier(r,"id"),nullableIdentifier(r,"organization_id"),r.getString("code"),r.getString("display_name"),ScopeKind.valueOf(r.getString("scope_kind")),readBoolean(r,"system_role"),readBoolean(r,"active"),JdbcTemporal.readRequired(r,"created_at"),JdbcTemporal.readRequired(r,"updated_at"),JdbcTemporal.readNullable(r,"deleted_at"));}
    private RoleAssignment readAssignment(ResultSet r)throws SQLException{return new RoleAssignment(dialect.readIdentifier(r,"id"),dialect.readIdentifier(r,"role_id"),AssignmentActorType.valueOf(r.getString("actor_type")),dialect.readIdentifier(r,"actor_id"),readScope(r),JdbcTemporal.readRequired(r,"effective_from"),JdbcTemporal.readNullable(r,"effective_to"),JdbcTemporal.readNullable(r,"revoked_at"),nullableIdentifier(r,"revoked_by"));}
    private AuthorizationScope readScope(ResultSet r)throws SQLException{ScopeKind kind=ScopeKind.valueOf(r.getString("scope_kind"));return switch(kind){case PLATFORM->AuthorizationScope.platform();case ORGANIZATION->AuthorizationScope.organization(dialect.readIdentifier(r,"organization_id"));case SUBDIVISION->AuthorizationScope.subdivision(dialect.readIdentifier(r,"organization_id"),dialect.readIdentifier(r,"subdivision_id"));};}

    private void bindUser(PreparedStatement s,IdentityUser u)throws SQLException{dialect.bindIdentifier(s,1,u.id());s.setString(2,u.login());s.setString(3,u.email());s.setString(4,u.displayName());s.setString(5,u.status().name());JdbcTemporal.bindInstant(s,6,u.createdAt());JdbcTemporal.bindInstant(s,7,u.updatedAt());bindNullableInstant(s,8,u.deletedAt());}
    private void bindBoolean(PreparedStatement statement,int index,boolean value)throws SQLException{if(dialect==JdbcDatabaseDialect.POSTGRESQL)statement.setBoolean(index,value);else statement.setInt(index,value?1:0);}
    private boolean readBoolean(ResultSet rows,String column)throws SQLException{return dialect==JdbcDatabaseDialect.POSTGRESQL?rows.getBoolean(column):rows.getInt(column)==1;}

    private void bindPermission(PreparedStatement s,Permission p)throws SQLException{dialect.bindIdentifier(s,1,p.id());dialect.bindNullableIdentifier(s,2,p.organizationId());s.setString(3,p.code());s.setString(4,p.resourceType());s.setString(5,p.action());s.setString(6,p.sensitivity());s.setString(7,p.scopeKind().name());bindBoolean(s,8,p.systemDefined());bindBoolean(s,9,p.active());JdbcTemporal.bindInstant(s,10,p.createdAt());JdbcTemporal.bindInstant(s,11,p.updatedAt());bindNullableInstant(s,12,p.deletedAt());}
    private void bindAssignment(PreparedStatement s,RoleAssignment a)throws SQLException{dialect.bindIdentifier(s,1,a.id());dialect.bindIdentifier(s,2,a.roleId());s.setString(3,a.actorType().name());dialect.bindIdentifier(s,4,a.actorId());s.setString(5,a.scope().kind().name());dialect.bindNullableIdentifier(s,6,a.scope().organizationId());dialect.bindNullableIdentifier(s,7,a.scope().subdivisionId());JdbcTemporal.bindInstant(s,8,a.effectiveFrom());bindNullableInstant(s,9,a.effectiveTo());bindNullableInstant(s,10,a.revokedAt());dialect.bindNullableIdentifier(s,11,a.revokedBy());}

    private <T>T withRead(SqlWork<T> work,String operation){Connection current=currentConnectionOrNull();if(current!=null){try{return work.run(current);}catch(SQLException failure){throw fail(operation,failure);}}try(Connection connection=dataSource.getConnection()){return work.run(connection);}catch(SQLException failure){throw fail(operation,failure);}}
    private Connection writeConnection(){return transaction.requireCurrentConnection();}
    private Connection currentConnectionOrNull(){try{return transaction.requireCurrentConnection();}catch(IllegalStateException absent){return null;}}
    private DomainIdentifier nullableIdentifier(ResultSet r,String column)throws SQLException{return r.getObject(column)==null?null:dialect.readIdentifier(r,column);}
    private static void bindNullableInstant(PreparedStatement s,int index,Instant value)throws SQLException{if(value==null)s.setNull(index,java.sql.Types.TIMESTAMP_WITH_TIMEZONE);else JdbcTemporal.bindInstant(s,index,value);}
    private String pagination(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"LIMIT ? OFFSET ?":"OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";}
    private void bindPage(PreparedStatement s,int offset,int limit)throws SQLException{bindPage(s,offset,limit,1);}
    private void bindPage(PreparedStatement s,int offset,int limit,int first)throws SQLException{if(dialect==JdbcDatabaseDialect.POSTGRESQL){s.setInt(first,limit);s.setInt(first+1,offset);}else{s.setInt(first,offset);s.setInt(first+1,limit);}}
    private String trueLiteral(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"TRUE":"1";}
    private String userTable(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_iam.iam_user":"INFRANEXUM_IAM_USER";}
    private String membershipTable(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_iam.user_membership":"INFRANEXUM_IAM_USER_MEMBERSHIP";}
    private String groupTable(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_iam.iam_group":"INFRANEXUM_IAM_GROUP";}
    private String groupUserTable(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_iam.group_user_member":"INFRANEXUM_IAM_GROUP_USER_MEMBER";}
    private String groupGroupTable(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_iam.group_group_member":"INFRANEXUM_IAM_GROUP_GROUP_MEMBER";}
    private String permissionTable(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_iam.permission":"INFRANEXUM_IAM_PERMISSION";}
    private String roleTable(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_iam.role":"INFRANEXUM_IAM_ROLE";}
    private String rolePermissionTable(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_iam.role_permission":"INFRANEXUM_IAM_ROLE_PERMISSION";}
    private String assignmentTable(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_iam.role_assignment":"INFRANEXUM_IAM_ROLE_ASSIGNMENT";}
    private static void requireOne(int count,String operation)throws SQLException{if(count!=1)throw new SQLException(operation+" affected unexpected rows: "+count);}
    private static IdentityAccessException conflict(String code,String message){return new IdentityAccessException(code,message);}
    private static JdbcPersistenceException fail(String operation,SQLException failure){return new JdbcPersistenceException(operation,failure);}
    @FunctionalInterface private interface SqlWork<T>{T run(Connection connection)throws SQLException;}
}
