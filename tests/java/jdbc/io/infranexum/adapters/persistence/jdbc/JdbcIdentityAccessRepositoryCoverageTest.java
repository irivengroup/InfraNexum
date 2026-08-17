package io.infranexum.adapters.persistence.jdbc;

import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.domain.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Deterministic JDBC coverage for IAM users, memberships, groups, RBAC and authorization reads. */
final class JdbcIdentityAccessRepositoryCoverageTest {
    private static final Instant T=Instant.parse("2026-08-16T12:00:00Z");
    private static final DomainIdentifier USER=id(1),ORG=id(2),SUB=id(3),GROUP=id(4),CHILD=id(5),ROLE=id(6),PERM=id(7),ASSIGN=id(8),ACTOR=id(9);

    @Test void statusFilterBindsLifecycleBeforePagination(){
        var c=connection(query(List.of(userRow())));
        var r=repo(c);
        assertEquals(1,r.listUsers(IdentityUserStatus.SUSPENDED,3,7).size());
        assertTrue(c.sql().getFirst().contains("WHERE status=?"));
        assertEquals("SUSPENDED",c.parameters().getFirst().get(1));
        assertEquals(7,c.parameters().getFirst().get(2));
        assertEquals(3,c.parameters().getFirst().get(3));
    }

    @Test void usersMembershipsBootstrapAndMembershipEvaluationCoverReadWritePaths(){
        var c=connection(query(List.of(userRow())),query(userRow()),query(userRow()),update(1),update(1),
                query(List.of(membershipRow())),query(List.of(membershipRow())),update(1),query(Map.of("x",1)),query(membershipRow()));
        var r=repo(c);
        assertEquals(1,r.listUsers(0,10).size());assertTrue(r.findUser(USER).isPresent());assertTrue(r.findUserByLogin("alice").isPresent());
        IdentityUser user=IdentityUser.pending(USER,"alice","alice@example.test","Alice",T);r.insertUser(user);r.updateUser(user);
        assertEquals(1,r.memberships(USER).size());assertEquals(1,r.memberships(USER,0,10).size());
        UserMembership membership=new UserMembership(id(20),USER,ORG,SUB,T,null,null);r.insertMembership(membership);
        assertTrue(r.hasEffectiveMembership(USER,AuthorizationScope.subdivision(ORG,SUB),T));

        var existing=connection(query(userRow()));new JdbcIdentityAccessRepository(dataSource(existing.connection()),transaction(existing.connection()),JdbcDatabaseDialect.POSTGRESQL).ensureBootstrapUser(USER,"alice","Alice",T);
        var absent=connection(query(List.of()),update(1));new JdbcIdentityAccessRepository(dataSource(absent.connection()),transaction(absent.connection()),JdbcDatabaseDialect.POSTGRESQL).ensureBootstrapUser(USER,"alice","Alice",T);
    }

    @Test void groupsEdgesCycleCountAndEffectiveMembersCoverGraphOperations(){
        var c=connection(query(List.of(groupRow(GROUP))),query(groupRow(GROUP)),query(groupRow(GROUP)),update(1),update(1),update(1),update(1),update(1),update(1),
                query(List.of(Map.of("parent_group_id",CHILD.value()))),query(Map.of("count",2L)),query(Map.of("organization_id",ORG.value())),query(List.of(Map.of("user_id",USER.value()))),query(List.of()));
        var r=repo(c);
        assertEquals(1,r.listGroups(ORG,0,10).size());assertTrue(r.findGroup(ORG,GROUP).isPresent());assertTrue(r.findGroup(GROUP).isPresent());
        IdentityGroup group=new IdentityGroup(GROUP,ORG,"admins","Admins",false,T,T,null);r.insertGroup(group);r.updateGroup(group);r.addUserToGroup(ORG,GROUP,USER,T);r.addGroupToGroup(ORG,GROUP,CHILD,T);r.removeUserFromGroup(ORG,GROUP,USER);r.removeGroupFromGroup(ORG,GROUP,CHILD);
        assertTrue(r.wouldCreateGroupCycle(ORG,GROUP,CHILD));assertTrue(r.wouldCreateGroupCycle(ORG,GROUP,GROUP));assertEquals(2,r.groupMemberCount(ORG,GROUP));assertEquals(Set.of(USER),r.effectiveGroupMembers(GROUP));
    }

    @Test void rolesPermissionsAssignmentsAndWritesCoverRbacPersistence(){
        var c=connection(query(List.of(roleRow())),query(roleRow()),update(1),update(1),update(1),update(1),update(1),update(1),query(Map.of("count",1L)),update(1),
                query(List.of(permissionRow())),query(permissionRow()),query(permissionRow()),query(List.of()),update(1),update(1),
                query(List.of(assignmentRow())),query(List.of(assignmentRow())),query(assignmentRow()),query(List.of(Map.of("code","iam.role.search"))),update(1),update(1));
        var r=repo(c);
        assertEquals(1,r.listRoles(ORG,0,10).size());assertTrue(r.findRole(ROLE).isPresent());
        Role role=new Role(ROLE,ORG,"ops.admin","Ops Admin",ScopeKind.ORGANIZATION,false,true,T,T,null);r.insertRole(role,Set.of());r.updateRole(role,Set.of());assertEquals(1,r.activeAssignmentCount(ROLE,T));r.revokeAssignmentsForRole(ROLE,ACTOR,T);
        assertEquals(1,r.listPermissions(ORG,0,10).size());assertTrue(r.findPermission(PERM).isPresent());assertTrue(r.findPermissionByCode(ORG,"iam.role.search").isPresent());
        Permission permission=new Permission(PERM,ORG,"iam.role.search","role","search","internal",ScopeKind.ORGANIZATION,false,true,T,T,null);r.insertPermission(permission);r.updatePermission(permission);
        assertEquals(1,r.assignments(ROLE).size());assertEquals(1,r.assignments(ROLE,0,10).size());assertTrue(r.findAssignment(ASSIGN).isPresent());assertEquals(Set.of("iam.role.search"),r.rolePermissionCodes(ROLE));
        RoleAssignment assignment=new RoleAssignment(ASSIGN,ROLE,AssignmentActorType.USER,USER,AuthorizationScope.organization(ORG),T,null,null,null);r.insertAssignment(assignment);r.revokeAssignment(ASSIGN,ACTOR,T);
    }

    @Test void authorizationAndPlatformAdministratorCoverActiveInactiveAndScopePaths(){
        var grants=connection(query(Map.of("x",1)),query(scopeRow("PLATFORM")),
                query(Map.of("x",1)),query(permissionGrantRow()),query(List.of()),
                query(Map.of("x",1)),query(scopeRow("PLATFORM")),
                query(Map.of("x",1)),query(Map.of("x",1)));
        var r=repo(grants);
        assertTrue(r.hasEffectivePermission(USER,"iam.role.search",AuthorizationScope.platform(),T));
        assertEquals(Set.of("iam.role.search"),r.effectivePermissionCodes(USER,AuthorizationScope.platform(),T));
        assertTrue(r.hasEffectiveRole(USER,ROLE,AuthorizationScope.platform(),T));assertTrue(r.hasEffectiveSystemRole(USER,Role.PLATFORM_ADMIN_CODE,T));

        var inactive=connection(query(List.of()),query(List.of()),query(List.of()),query(List.of()));
        var ir=repo(inactive);assertFalse(ir.hasEffectivePermission(USER,"iam.role.search",AuthorizationScope.platform(),T));assertTrue(ir.effectivePermissionCodes(USER,AuthorizationScope.platform(),T).isEmpty());assertFalse(ir.hasEffectiveRole(USER,ROLE,AuthorizationScope.platform(),T));assertFalse(ir.hasEffectiveSystemRole(USER,Role.PLATFORM_ADMIN_CODE,T));

        var admin=connection(query(Map.of("id",ROLE.value())),query(List.of()),update(1));
        repo(admin).ensurePlatformAdministrator(USER,T);assertTrue(admin.sql().getLast().contains("role_assignment"));
        var adminExisting=connection(query(Map.of("id",ROLE.value())),query(Map.of("x",1)));repo(adminExisting).ensurePlatformAdministrator(USER,T);
    }

    @Test void oracleMappingsUniqueConflictsAndSqlFailuresStayFailClosed(){
        var oracleUser=userRow();oracleize(oracleUser,"id");
        var c=connection(query(List.of(oracleUser)),query(List.of(groupRowOracle())),query(List.of(permissionRowOracle())),query(List.of(roleRowOracle())));
        var r=new JdbcIdentityAccessRepository(dataSource(c.connection()),noTransaction(),JdbcDatabaseDialect.ORACLE);
        assertEquals(1,r.listUsers(1,2).size());assertEquals(1,r.listGroups(ORG,1,2).size());assertEquals(1,r.listPermissions(null,1,2).size());assertEquals(1,r.listRoles(null,1,2).size());assertTrue(c.sql().stream().anyMatch(sql->sql.contains("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY")));

        var dup=connection(updateFailure(new SQLException("dup","23505")));assertEquals("IAM_LOGIN_CONFLICT",assertThrows(IdentityAccessException.class,()->new JdbcIdentityAccessRepository(dataSource(dup.connection()),transaction(dup.connection()),JdbcDatabaseDialect.POSTGRESQL).insertUser(IdentityUser.pending(USER,"alice",null,"Alice",T))).code());
        var fail=connection(queryFailure(new SQLException("offline","08006")));assertThrows(JdbcPersistenceException.class,()->new JdbcIdentityAccessRepository(dataSource(fail.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).listUsers(0,1));
        assertThrows(NullPointerException.class,()->new JdbcIdentityAccessRepository(null,noTransaction(),JdbcDatabaseDialect.POSTGRESQL));
    }


    @Test void mutationConflictAndInfrastructureBranchesRemainDistinct(){
        IdentityUser user=IdentityUser.pending(USER,"alice","alice@example.test","Alice",T);
        var updateDup=connection(updateFailure(new SQLException("dup","23505")));
        assertEquals("IAM_LOGIN_CONFLICT",assertThrows(IdentityAccessException.class,()->repo(updateDup).updateUser(user)).code());
        var updateFail=connection(updateFailure(new SQLException("offline","08006")));
        assertThrows(JdbcPersistenceException.class,()->repo(updateFail).updateUser(user));

        var adminDup=connection(query(Map.of("id",ROLE.value())),query(List.of()),updateFailure(new SQLException("dup","23505")));
        repo(adminDup).ensurePlatformAdministrator(USER,T);
        var adminFail=connection(query(Map.of("id",ROLE.value())),query(List.of()),updateFailure(new SQLException("offline","08006")));
        assertThrows(JdbcPersistenceException.class,()->repo(adminFail).ensurePlatformAdministrator(USER,T));

        IdentityGroup group=new IdentityGroup(GROUP,ORG,"admins","Admins",false,T,T,null);
        var groupDup=connection(updateFailure(new SQLException("dup","23505")));
        assertEquals("IAM_GROUP_CODE_CONFLICT",assertThrows(IdentityAccessException.class,()->repo(groupDup).insertGroup(group)).code());
        var groupFail=connection(updateFailure(new SQLException("offline","08006")));
        assertThrows(JdbcPersistenceException.class,()->repo(groupFail).insertGroup(group));

        Role role=new Role(ROLE,ORG,"ops.admin","Ops Admin",ScopeKind.ORGANIZATION,false,true,T,T,null);
        var roleInsertDup=connection(updateFailure(new SQLException("dup","23505")));
        assertEquals("IAM_ROLE_CODE_CONFLICT",assertThrows(IdentityAccessException.class,()->repo(roleInsertDup).insertRole(role,Set.of())).code());
        var roleInsertFail=connection(updateFailure(new SQLException("offline","08006")));
        assertThrows(JdbcPersistenceException.class,()->repo(roleInsertFail).insertRole(role,Set.of()));
        var roleUpdateDup=connection(updateFailure(new SQLException("dup","23505")));
        assertEquals("IAM_ROLE_CODE_CONFLICT",assertThrows(IdentityAccessException.class,()->repo(roleUpdateDup).updateRole(role,Set.of())).code());
        var roleUpdateFail=connection(updateFailure(new SQLException("offline","08006")));
        assertThrows(JdbcPersistenceException.class,()->repo(roleUpdateFail).updateRole(role,Set.of()));

        var edgeDup=connection(updateFailure(new SQLException("dup","23505")));
        repo(edgeDup).addUserToGroup(ORG,GROUP,USER,T);
        var edgeFail=connection(updateFailure(new SQLException("offline","08006")));
        assertThrows(JdbcPersistenceException.class,()->repo(edgeFail).addUserToGroup(ORG,GROUP,USER,T));
    }

    @Test void membershipEvaluationCoversInactivePlatformOrganizationNullAndMismatchSubdivisions(){
        assertFalse(repo(connection(query(List.of()))).hasEffectiveMembership(USER,AuthorizationScope.organization(ORG),T));
        assertFalse(repo(connection(query(Map.of("x",1)))).hasEffectiveMembership(USER,AuthorizationScope.platform(),T));

        var orgMembership=membershipRow();
        assertTrue(repo(connection(query(Map.of("x",1)),query(orgMembership)))
                .hasEffectiveMembership(USER,AuthorizationScope.organization(ORG),T));

        var nullSubdivision=membershipRow(); nullSubdivision.put("subdivision_id",null);
        assertTrue(repo(connection(query(Map.of("x",1)),query(nullSubdivision)))
                .hasEffectiveMembership(USER,AuthorizationScope.subdivision(ORG,SUB),T));

        var otherSubdivision=membershipRow(); otherSubdivision.put("subdivision_id",id(99).value());
        assertFalse(repo(connection(query(Map.of("x",1)),query(otherSubdivision)))
                .hasEffectiveMembership(USER,AuthorizationScope.subdivision(ORG,SUB),T));
    }

    @Test void countsMissingRowsAndGroupResolutionFailuresAreFailClosed(){
        assertThrows(JdbcPersistenceException.class,()->repo(connection(query(List.of()))).groupMemberCount(ORG,GROUP));
        assertThrows(JdbcPersistenceException.class,()->repo(connection(query(List.of()))).activeAssignmentCount(ROLE,T));
        assertEquals("IAM_GROUP_NOT_FOUND",assertThrows(IdentityAccessException.class,
                ()->repo(connection(query(List.of()))).effectiveGroupMembers(GROUP)).code());
        assertEquals("IAM_SYSTEM_ROLE_MISSING",assertThrows(IdentityAccessException.class,
                ()->repo(connection(query(List.of()))).ensurePlatformAdministrator(USER,T)).code());
    }

    @Test void permissionAmbiguityConflictsAndRolePermissionBatchAreCovered(){
        var ambiguous=connection(query(List.of(permissionRow(),permissionRow())));
        assertEquals("IAM_PERMISSION_AMBIGUOUS",assertThrows(IdentityAccessException.class,
                ()->repo(ambiguous).findPermissionByCode(ORG,"iam.role.search")).code());
        var platformPermission=permissionRow(); platformPermission.put("organization_id",null);
        assertTrue(repo(connection(query(platformPermission))).findPermissionByCode(null,"iam.role.search").isPresent());
        assertTrue(repo(connection(query(List.of()))).findPermissionByCode(null,"missing.permission").isEmpty());

        Permission permission=new Permission(PERM,ORG,"iam.role.search","role","search","internal",ScopeKind.ORGANIZATION,false,true,T,T,null);
        assertEquals("IAM_PERMISSION_CODE_CONFLICT",assertThrows(IdentityAccessException.class,
                ()->repo(connection(query(Map.of("x",1)))).insertPermission(permission)).code());
        var insertDup=connection(query(List.of()),updateFailure(new SQLException("dup","23505")));
        assertEquals("IAM_PERMISSION_CODE_CONFLICT",assertThrows(IdentityAccessException.class,()->repo(insertDup).insertPermission(permission)).code());
        var insertFail=connection(query(List.of()),updateFailure(new SQLException("offline","08006")));
        assertThrows(JdbcPersistenceException.class,()->repo(insertFail).insertPermission(permission));

        Role role=new Role(ROLE,ORG,"ops.admin","Ops Admin",ScopeKind.ORGANIZATION,false,true,T,T,null);
        var batch=connection(query(permissionRow()),update(1),update(1),batch());
        repo(batch).insertRole(role,Set.of("iam.role.search"));
        assertEquals(1,batch.batches().getLast().size());
    }

    @Test void assignmentConflictsAndAffectedRowGuardsAreCovered(){
        RoleAssignment assignment=new RoleAssignment(ASSIGN,ROLE,AssignmentActorType.USER,USER,AuthorizationScope.organization(ORG),T,null,null,null);
        var duplicate=connection(updateFailure(new SQLException("dup","23505")));
        assertEquals("IAM_ASSIGNMENT_CONFLICT",assertThrows(IdentityAccessException.class,()->repo(duplicate).insertAssignment(assignment)).code());
        var failure=connection(updateFailure(new SQLException("offline","08006")));
        assertThrows(JdbcPersistenceException.class,()->repo(failure).insertAssignment(assignment));
        assertThrows(JdbcPersistenceException.class,()->repo(connection(update(0))).insertMembership(new UserMembership(id(20),USER,ORG,null,T,null,null)));
        assertThrows(JdbcPersistenceException.class,()->repo(connection(update(0))).revokeAssignment(ASSIGN,ACTOR,T));
    }

    @Test void authorizationTraversesMembershipUserAndGroupGrantAlternatives(){
        AuthorizationScope org=AuthorizationScope.organization(ORG);
        var missingMembership=connection(query(Map.of("x",1)),query(Map.of("x",1)),query(List.of()));
        assertFalse(repo(missingMembership).hasEffectivePermission(USER,"iam.role.search",org,T));

        var groupGrant=connection(
                query(Map.of("x",1)),                 // active user
                query(List.of()),                     // user grant missing
                query(List.of(Map.of("group_id",GROUP.value()))), // direct groups
                query(List.of()),                     // parents
                query(scopeRow("PLATFORM")));         // group grant covers platform
        assertTrue(repo(groupGrant).hasEffectivePermission(USER,"iam.role.search",AuthorizationScope.platform(),T));

        var groupRole=connection(
                query(Map.of("x",1)), query(List.of()),
                query(List.of(Map.of("group_id",GROUP.value()))), query(List.of()), query(scopeRow("PLATFORM")));
        assertTrue(repo(groupRole).hasEffectiveRole(USER,ROLE,AuthorizationScope.platform(),T));

        var codes=connection(
                query(Map.of("x",1)),
                query(List.of(permissionGrantRow(),scopeCodeRow("ignored.permission", "ORGANIZATION", id(77), null))),
                query(List.of(Map.of("group_id",GROUP.value()))),query(List.of()),query(List.of(permissionGrantRow())));
        assertEquals(Set.of("iam.role.search"),repo(codes).effectivePermissionCodes(USER,AuthorizationScope.platform(),T));
    }


    @Test void authorizationNegativeBranchesCoverEffectiveMembershipAndGroupGrantMisses(){
        AuthorizationScope org=AuthorizationScope.organization(ORG);
        var userPermissionMiss=connection(
                query(Map.of("x",1)), query(Map.of("x",1)), query(membershipRow()),
                query(List.of()), query(List.of()));
        assertFalse(repo(userPermissionMiss).hasEffectivePermission(USER,"iam.role.search",org,T));

        var groupPermissionMiss=connection(
                query(Map.of("x",1)), query(List.of()),
                query(List.of(Map.of("group_id",GROUP.value()))), query(List.of()), query(List.of()));
        assertFalse(repo(groupPermissionMiss).hasEffectivePermission(USER,"iam.role.search",AuthorizationScope.platform(),T));

        var effectiveCodes=connection(
                query(Map.of("x",1)), query(Map.of("x",1)), query(membershipRow()),
                query(List.of()), query(List.of()));
        assertTrue(repo(effectiveCodes).effectivePermissionCodes(USER,org,T).isEmpty());

        var effectiveCodesWithoutMembership=connection(
                query(Map.of("x",1)), query(List.of()));
        assertTrue(repo(effectiveCodesWithoutMembership).effectivePermissionCodes(USER,org,T).isEmpty());

        var roleMembership=connection(
                query(Map.of("x",1)), query(Map.of("x",1)), query(membershipRow()),
                query(List.of()), query(List.of()));
        assertFalse(repo(roleMembership).hasEffectiveRole(USER,ROLE,org,T));

        var roleWithoutMembership=connection(
                query(Map.of("x",1)), query(List.of()));
        assertFalse(repo(roleWithoutMembership).hasEffectiveRole(USER,ROLE,org,T));

        var groupRoleMiss=connection(
                query(Map.of("x",1)), query(List.of()),
                query(List.of(Map.of("group_id",GROUP.value()))), query(List.of()), query(List.of()));
        assertFalse(repo(groupRoleMiss).hasEffectiveRole(USER,ROLE,AuthorizationScope.platform(),T));

        var nonCoveringRole=connection(
                query(Map.of("x",1)), query(scopeCodeRow(null,"ORGANIZATION",ORG,null)), query(List.of()));
        assertFalse(repo(nonCoveringRole).hasEffectiveRole(USER,ROLE,AuthorizationScope.platform(),T));

        var nonCoveringPermission=connection(
                query(Map.of("x",1)), query(scopeCodeRow(null,"ORGANIZATION",ORG,null)), query(List.of()));
        assertFalse(repo(nonCoveringPermission).hasEffectivePermission(USER,"iam.role.search",AuthorizationScope.platform(),T));
    }

    @Test void groupTraversalCoversDuplicateNodesAndGraphSafetyBounds(){
        var duplicateCycle=connection(query(List.of(Map.of("parent_group_id",GROUP.value()))));
        assertFalse(repo(duplicateCycle).wouldCreateGroupCycle(ORG,GROUP,CHILD));

        var duplicateMember=connection(
                query(Map.of("organization_id",ORG.value())),
                query(List.of()), query(List.of(Map.of("child_group_id",GROUP.value()))));
        assertTrue(repo(duplicateMember).effectiveGroupMembers(GROUP).isEmpty());

        var effectiveGroupsWithParentCycle=connection(
                query(Map.of("x",1)),
                query(List.of()),
                query(List.of(Map.of("group_id",GROUP.value()))),
                query(List.of(Map.of("parent_group_id",CHILD.value()))),
                query(List.of(Map.of("parent_group_id",GROUP.value()))),
                query(List.of()),
                query(List.of()));
        assertTrue(repo(effectiveGroupsWithParentCycle).effectivePermissionCodes(USER,AuthorizationScope.platform(),T).isEmpty());

        Script[] chain=new Script[512];
        for(int i=0;i<chain.length;i++) chain[i]=query(List.of(Map.of("parent_group_id",id(1000+i).value())));
        assertEquals("IAM_GROUP_GRAPH_TOO_LARGE",assertThrows(IdentityAccessException.class,
                ()->repo(connection(chain)).wouldCreateGroupCycle(ORG,id(999),CHILD)).code());
    }

    @Test void insertUserInfrastructureFailureCoversNonUniqueCatchBranch(){
        var failure=connection(updateFailure(new SQLException("offline","08006")));
        assertThrows(JdbcPersistenceException.class,()->repo(failure).insertUser(IdentityUser.pending(USER,"alice",null,"Alice",T)));
    }

    @Test void oracleMutationBindingsAndNullableTimestampsExerciseAlternateDialect(){
        IdentityGroup deleted=new IdentityGroup(GROUP,ORG,"admins","Admins",false,T,T,T.plusSeconds(1));
        var oracleUpdate=connection(update(1));
        new JdbcIdentityAccessRepository(dataSource(oracleUpdate.connection()),transaction(oracleUpdate.connection()),JdbcDatabaseDialect.ORACLE).updateGroup(deleted);
        assertTrue(oracleUpdate.parameters().getFirst().values().stream().anyMatch(value->value instanceof java.time.OffsetDateTime));

        IdentityGroup system=new IdentityGroup(id(44),ORG,"system_admins","System Admins",true,T,T,null);
        var oracleInsert=connection(update(1));
        new JdbcIdentityAccessRepository(dataSource(oracleInsert.connection()),transaction(oracleInsert.connection()),JdbcDatabaseDialect.ORACLE).insertGroup(system);
        assertTrue(oracleInsert.parameters().getFirst().values().stream().anyMatch(value->Integer.valueOf(1).equals(value)));
    }

    private static JdbcIdentityAccessRepository repo(JdbcScriptedSupport.ScriptedConnection c){return new JdbcIdentityAccessRepository(dataSource(c.connection()),transaction(c.connection()),JdbcDatabaseDialect.POSTGRESQL);}
    private static LinkedHashMap<String,Object> userRow(){var r=new LinkedHashMap<String,Object>();r.put("id",USER.value());r.put("login","alice");r.put("email","alice@example.test");r.put("display_name","Alice");r.put("status","ACTIVE");r.put("created_at",T);r.put("updated_at",T);r.put("deleted_at",null);return r;}
    private static LinkedHashMap<String,Object> membershipRow(){var r=new LinkedHashMap<String,Object>();r.put("id",id(20).value());r.put("user_id",USER.value());r.put("organization_id",ORG.value());r.put("subdivision_id",SUB.value());r.put("effective_from",T);r.put("effective_to",null);r.put("revoked_at",null);return r;}
    private static LinkedHashMap<String,Object> groupRow(DomainIdentifier id){var r=new LinkedHashMap<String,Object>();r.put("id",id.value());r.put("organization_id",ORG.value());r.put("code","admins");r.put("display_name","Admins");r.put("system_group",false);r.put("created_at",T);r.put("updated_at",T);r.put("deleted_at",null);return r;}
    private static LinkedHashMap<String,Object> roleRow(){var r=new LinkedHashMap<String,Object>();r.put("id",ROLE.value());r.put("organization_id",ORG.value());r.put("code","ops.admin");r.put("display_name","Ops Admin");r.put("scope_kind","ORGANIZATION");r.put("system_role",false);r.put("active",true);r.put("created_at",T);r.put("updated_at",T);r.put("deleted_at",null);return r;}
    private static LinkedHashMap<String,Object> permissionRow(){var r=new LinkedHashMap<String,Object>();r.put("id",PERM.value());r.put("organization_id",ORG.value());r.put("code","iam.role.search");r.put("resource_type","role");r.put("action_name","search");r.put("sensitivity","INTERNAL");r.put("scope_kind","ORGANIZATION");r.put("system_defined",false);r.put("active",true);r.put("created_at",T);r.put("updated_at",T);r.put("deleted_at",null);return r;}
    private static LinkedHashMap<String,Object> assignmentRow(){var r=new LinkedHashMap<String,Object>();r.put("id",ASSIGN.value());r.put("role_id",ROLE.value());r.put("actor_type","USER");r.put("actor_id",USER.value());r.put("scope_kind","ORGANIZATION");r.put("organization_id",ORG.value());r.put("subdivision_id",null);r.put("effective_from",T);r.put("effective_to",null);r.put("revoked_at",null);r.put("revoked_by",null);return r;}
    private static Map<String,Object> scopeRow(String kind){var r=new LinkedHashMap<String,Object>();r.put("scope_kind",kind);r.put("organization_id",kind.equals("PLATFORM")?null:ORG.value());r.put("subdivision_id",null);return r;}
    private static Map<String,Object> permissionGrantRow(){var r=new LinkedHashMap<String,Object>();r.put("code","iam.role.search");r.put("scope_kind","PLATFORM");r.put("organization_id",null);r.put("subdivision_id",null);return r;}
    private static Map<String,Object> scopeCodeRow(String code,String kind,DomainIdentifier organization,DomainIdentifier subdivision){var r=new LinkedHashMap<String,Object>();r.put("code",code);r.put("scope_kind",kind);r.put("organization_id",organization==null?null:organization.value());r.put("subdivision_id",subdivision==null?null:subdivision.value());return r;}
    private static Map<String,Object> groupRowOracle(){var r=groupRow(GROUP);oracleize(r,"id","organization_id");r.put("system_group",0);return r;}
    private static Map<String,Object> permissionRowOracle(){var r=permissionRow();oracleize(r,"id","organization_id");r.put("system_defined",0);r.put("active",1);return r;}
    private static Map<String,Object> roleRowOracle(){var r=roleRow();r.put("organization_id",null);oracleize(r,"id");r.put("system_role",1);r.put("active",1);r.put("code",Role.PLATFORM_ADMIN_CODE);return r;}
    private static void oracleize(Map<String,Object> r,String... keys){for(String k:keys){Object v=r.get(k);if(v instanceof UUID u)r.put(k,u.toString());}}
    private static DomainIdentifier id(int n){return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(n)));}
}
