package io.infranexum.identity.access.application;

import io.infranexum.core.audit.*;
import io.infranexum.core.contracts.*;
import io.infranexum.core.events.*;
import io.infranexum.identity.access.domain.*;
import io.infranexum.identity.access.ports.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Complete PGM-03-E03 IAM administration use cases shared by API, CLI and Web. */
public final class IdentityAccessAdminService {
    private static final ContractVersion EVENT_VERSION=ContractVersion.parse("1.0.0");
    private static final EventSource SOURCE=new EventSource("infranexum.identity-access");

    private final IdentityAccessRepository repository;
    private final IdentityAccessFeaturePolicy features;
    private final OrganizationScopeReferencePort organizationScopes;
    private final RoleAssignmentPolicyGuard assignmentGuard;
    private final TransactionalEventStore events;
    private final AuditJournal audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public IdentityAccessAdminService(IdentityAccessRepository repository, IdentityAccessFeaturePolicy features,
            OrganizationScopeReferencePort organizationScopes, RoleAssignmentPolicyGuard assignmentGuard, TransactionalEventStore events,
            AuditJournal audit, UuidV7Generator ids, Clock clock) {
        this.repository=Objects.requireNonNull(repository,"repository"); this.features=Objects.requireNonNull(features,"features");
        this.organizationScopes=Objects.requireNonNull(organizationScopes,"organizationScopes");
        this.assignmentGuard=Objects.requireNonNull(assignmentGuard,"assignmentGuard");
        this.events=Objects.requireNonNull(events,"events"); this.audit=Objects.requireNonNull(audit,"audit");
        this.ids=Objects.requireNonNull(ids,"ids"); this.clock=Objects.requireNonNull(clock,"clock");
    }

    public List<IdentityUser> listUsers(int offset,int limit){ page(offset,limit); return repository.listUsers(offset,limit); }
    public IdentityUser getUser(DomainIdentifier id){ return repository.findUser(Objects.requireNonNull(id,"id")).orElseThrow(()->notFound("IAM_USER_NOT_FOUND","user not found")); }
    public List<UserMembership> memberships(DomainIdentifier userId){ getUser(userId); return repository.memberships(userId); }
    public OffsetPage<UserMembership> memberships(DomainIdentifier userId,int offset,int limit){getUser(userId);PaginationConstraints.requireOffset(offset);page(offset,limit);List<UserMembership> rows=repository.memberships(userId,offset,limit+1);boolean more=rows.size()>limit;List<UserMembership> items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new OffsetPage<>(items,more?Math.addExact(offset,limit):null);}
    public List<IdentityGroup> listGroups(DomainIdentifier orgId,int offset,int limit){ Objects.requireNonNull(orgId,"orgId"); page(offset,limit); return repository.listGroups(orgId,offset,limit); }
    public IdentityGroup getGroup(DomainIdentifier orgId,DomainIdentifier groupId){ return repository.findGroup(orgId,groupId).orElseThrow(()->notFound("IAM_GROUP_NOT_FOUND","group not found")); }
    public IdentityGroup getGroup(DomainIdentifier groupId){ return repository.findGroup(Objects.requireNonNull(groupId,"groupId")).orElseThrow(()->notFound("IAM_GROUP_NOT_FOUND","group not found")); }
    public List<Role> listRoles(DomainIdentifier orgId,int offset,int limit){ page(offset,limit); return repository.listRoles(orgId,offset,limit); }
    public Role getRole(DomainIdentifier roleId){ return repository.findRole(roleId).orElseThrow(()->notFound("IAM_ROLE_NOT_FOUND","role not found")); }
    public List<Permission> listPermissions(DomainIdentifier orgId,int offset,int limit){ page(offset,limit); return repository.listPermissions(orgId,offset,limit); }
    public Permission getPermission(DomainIdentifier id){ return repository.findPermission(id).orElseThrow(()->notFound("IAM_PERMISSION_NOT_FOUND","permission not found")); }
    public List<RoleAssignment> assignments(DomainIdentifier roleId){ getRole(roleId); return repository.assignments(roleId); }
    public OffsetPage<RoleAssignment> assignments(DomainIdentifier roleId,int offset,int limit){getRole(roleId);PaginationConstraints.requireOffset(offset);page(offset,limit);List<RoleAssignment> rows=repository.assignments(roleId,offset,limit+1);boolean more=rows.size()>limit;List<RoleAssignment> items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new OffsetPage<>(items,more?Math.addExact(offset,limit):null);}
    public RoleAssignment getAssignment(DomainIdentifier assignmentId){ return repository.findAssignment(Objects.requireNonNull(assignmentId,"assignmentId")).orElseThrow(()->notFound("IAM_ASSIGNMENT_NOT_FOUND","role assignment not found")); }
    public Set<String> rolePermissionCodes(DomainIdentifier roleId){ getRole(roleId); return repository.rolePermissionCodes(roleId); }
    public Set<DomainIdentifier> effectiveGroupMembers(DomainIdentifier groupId){ Objects.requireNonNull(groupId,"groupId"); return repository.effectiveGroupMembers(groupId); }
    public Set<String> effectivePermissionCodes(DomainIdentifier userId,AuthorizationScope scope){ getUser(userId); return repository.effectivePermissionCodes(userId,Objects.requireNonNull(scope,"scope"),clock.instant()); }

    /** Ensures a fresh local bootstrap account can pass the new deny-by-default boundary. */
    public void ensureBootstrapPlatformAdministrator(DomainIdentifier userId,String login,String displayName) {
        Objects.requireNonNull(userId,"userId"); Instant now=clock.instant();
        execute(tx->{ repository.ensureBootstrapUser(userId,login,displayName,now); repository.ensurePlatformAdministrator(userId,now); return null; });
    }

    public IdentityUser createUser(String login,String email,String displayName,boolean activate,IdentityAccessCommandContext context) {
        Objects.requireNonNull(context,"context"); Instant now=clock.instant();
        IdentityUser user=IdentityUser.pending(ids.next(),login,email,displayName,now);
        if(activate) user=user.activate(now);
        IdentityUser created=user;
        return execute(tx->{
            if(repository.findUserByLogin(created.login()).isPresent()) throw new IdentityAccessException("IAM_LOGIN_CONFLICT","login already exists");
            repository.insertUser(created); tx.append(event("iam.user.created.v1",created.id(),context.correlationId(),now,userPayload(created)));
            auditMutation(context,"iam.user.create","user",created.id().toString(),scopeFor(null),"SUCCESS",Map.of("status",created.status().name()));
            return created;
        });
    }


    public IdentityUser updateUser(DomainIdentifier userId,String email,String displayName,IdentityAccessCommandContext context) {
        IdentityUser changed=getUser(userId).updateProfile(email,displayName,clock.instant());
        return execute(tx->{ repository.updateUser(changed); tx.append(event("iam.user.updated.v1",userId,context.correlationId(),changed.updatedAt(),userPayload(changed)));
            auditMutation(context,"iam.user.update","user",userId.toString(),scopeFor(null),"SUCCESS",Map.of()); return changed; });
    }

    public IdentityUser activateUser(DomainIdentifier userId,IdentityAccessCommandContext context){ return userTransition(userId,context,"iam.user.activated.v1","iam.user.activate",IdentityUser::activate); }
    public IdentityUser suspendUser(DomainIdentifier userId,IdentityAccessCommandContext context){ return userTransition(userId,context,"iam.user.suspended.v1","iam.user.suspend",IdentityUser::suspend); }
    public IdentityUser deleteUser(DomainIdentifier userId,IdentityAccessCommandContext context){ return userTransition(userId,context,"iam.user.deleted.v1","iam.user.delete",IdentityUser::delete); }

    public UserMembership addMembership(DomainIdentifier userId,DomainIdentifier orgId,DomainIdentifier subdivisionId,
            Instant effectiveFrom,Instant effectiveTo,IdentityAccessCommandContext context) {
        Objects.requireNonNull(context,"context"); getUser(userId); requireOrganizationScope(orgId,subdivisionId);
        Instant from=effectiveFrom==null?clock.instant():effectiveFrom;
        UserMembership membership=new UserMembership(ids.next(),userId,orgId,subdivisionId,from,effectiveTo,null);
        if(!features.supportsMultiMembership()) {
            for(UserMembership current:repository.memberships(userId)) if(current.effectiveAt(from)
                    && (!current.organizationId().equals(orgId)||!Objects.equals(current.subdivisionId(),subdivisionId)))
                throw new IdentityAccessException("IAM_MULTI_MEMBERSHIP_UNAVAILABLE","active profile does not support multiple memberships");
        }
        return execute(tx->{ repository.insertMembership(membership);
            tx.append(event("iam.user.membership_changed.v1",userId,context.correlationId(),clock.instant(),membershipPayload(membership)));
            auditMutation(context,"iam.user.manage_membership","user",userId.toString(),scopeFor(orgId),"SUCCESS",Map.of("membership_id",membership.id().toString()));
            return membership; });
    }

    public IdentityGroup createGroup(DomainIdentifier orgId,String code,String displayName,IdentityAccessCommandContext context) {
        requireOrganizationScope(orgId,null);
        Instant now=clock.instant(); IdentityGroup group=new IdentityGroup(ids.next(),orgId,code,displayName,false,now,now,null);
        return execute(tx->{ repository.insertGroup(group); tx.append(event("iam.group.created.v1",group.id(),context.correlationId(),now,groupPayload(group)));
            auditMutation(context,"iam.group.create","group",group.id().toString(),scopeFor(orgId),"SUCCESS",Map.of("code",group.code())); return group; });
    }

    public IdentityGroup updateGroup(DomainIdentifier orgId,DomainIdentifier groupId,String displayName,IdentityAccessCommandContext context) {
        IdentityGroup current=getGroup(orgId,groupId); IdentityGroup changed=current.rename(displayName,clock.instant());
        return execute(tx->{ repository.updateGroup(changed); tx.append(event("iam.group.updated.v1",changed.id(),context.correlationId(),changed.updatedAt(),groupPayload(changed)));
            auditMutation(context,"iam.group.update","group",changed.id().toString(),scopeFor(orgId),"SUCCESS",Map.of()); return changed; });
    }

    public IdentityGroup deleteGroup(DomainIdentifier orgId,DomainIdentifier groupId,IdentityAccessCommandContext context) {
        IdentityGroup current=getGroup(orgId,groupId);
        if(repository.groupMemberCount(orgId,groupId)>0) throw new IdentityAccessException("IAM_GROUP_NOT_EMPTY","group members must be reassigned or removed before deletion");
        IdentityGroup changed=current.delete(clock.instant());
        return execute(tx->{ repository.updateGroup(changed); tx.append(event("iam.group.deleted.v1",changed.id(),context.correlationId(),changed.updatedAt(),groupPayload(changed)));
            auditMutation(context,"iam.group.delete","group",changed.id().toString(),scopeFor(orgId),"SUCCESS",Map.of()); return changed; });
    }

    public void addUserToGroup(DomainIdentifier orgId,DomainIdentifier groupId,DomainIdentifier userId,IdentityAccessCommandContext context) {
        getGroup(orgId,groupId); getUser(userId); Instant now=clock.instant();
        AuthorizationScope organizationScope = AuthorizationScope.organization(orgId);
        if (!repository.hasEffectiveMembership(userId, organizationScope, now)) {
            throw new IdentityAccessException(
                    "IAM_GROUP_MEMBERSHIP_SCOPE_MISMATCH",
                    "user must have an effective organization membership before joining an organization group");
        }
        execute(tx->{ repository.addUserToGroup(orgId,groupId,userId,now); tx.append(event("iam.group.member_added.v1",groupId,context.correlationId(),now,memberPayload(groupId,"USER",userId)));
            auditMutation(context,"iam.group.add_member","group",groupId.toString(),scopeFor(orgId),"SUCCESS",Map.of("member_id",userId.toString(),"member_type","USER")); return null; });
    }

    public void addGroupToGroup(DomainIdentifier orgId,DomainIdentifier parentGroupId,DomainIdentifier childGroupId,IdentityAccessCommandContext context) {
        if(!features.supportsNestedGroups()) throw new IdentityAccessException("IAM_NESTED_GROUPS_UNAVAILABLE","active profile does not support nested groups");
        getGroup(orgId,parentGroupId); getGroup(orgId,childGroupId);
        if(parentGroupId.equals(childGroupId)||repository.wouldCreateGroupCycle(orgId,parentGroupId,childGroupId)) throw new IdentityAccessException("IAM_GROUP_CYCLE","nested group membership would create a cycle");
        Instant now=clock.instant(); execute(tx->{ repository.addGroupToGroup(orgId,parentGroupId,childGroupId,now);
            tx.append(event("iam.group.member_added.v1",parentGroupId,context.correlationId(),now,memberPayload(parentGroupId,"GROUP",childGroupId)));
            auditMutation(context,"iam.group.add_group","group",parentGroupId.toString(),scopeFor(orgId),"SUCCESS",Map.of("member_id",childGroupId.toString(),"member_type","GROUP")); return null; });
    }

    public void removeUserFromGroup(DomainIdentifier orgId,DomainIdentifier groupId,DomainIdentifier userId,IdentityAccessCommandContext context) {
        getGroup(orgId,groupId); Instant now=clock.instant();
        execute(tx->{ repository.removeUserFromGroup(orgId,groupId,userId); tx.append(event("iam.group.member_removed.v1",groupId,context.correlationId(),now,memberPayload(groupId,"USER",userId))); auditMutation(context,"iam.group.remove_member","group",groupId.toString(),scopeFor(orgId),"SUCCESS",Map.of("member_id",userId.toString())); return null; });
    }

    public void removeGroupFromGroup(DomainIdentifier orgId,DomainIdentifier parentGroupId,DomainIdentifier childGroupId,IdentityAccessCommandContext context) {
        getGroup(orgId,parentGroupId); getGroup(orgId,childGroupId); Instant now=clock.instant();
        execute(tx->{ repository.removeGroupFromGroup(orgId,parentGroupId,childGroupId); tx.append(event("iam.group.member_removed.v1",parentGroupId,context.correlationId(),now,memberPayload(parentGroupId,"GROUP",childGroupId))); auditMutation(context,"iam.group.remove_group","group",parentGroupId.toString(),scopeFor(orgId),"SUCCESS",Map.of("member_id",childGroupId.toString())); return null; });
    }

    public Permission createPermission(DomainIdentifier orgId,String code,String resourceType,String action,String sensitivity,ScopeKind scopeKind,IdentityAccessCommandContext context) {
        requireOrganizationScope(orgId,null);
        Instant now=clock.instant(); Permission permission=new Permission(ids.next(),orgId,code,resourceType,action,sensitivity,scopeKind,false,true,now,now,null);
        return execute(tx->{ repository.insertPermission(permission); tx.append(event("iam.permission.created.v1",permission.id(),context.correlationId(),now,permissionPayload(permission)));
            auditMutation(context,"iam.permission.create","permission",permission.id().toString(),scopeFor(orgId),"SUCCESS",Map.of("code",permission.code())); return permission; });
    }


    public Permission updatePermission(DomainIdentifier permissionId,String resourceType,String action,String sensitivity,ScopeKind scopeKind,boolean active,IdentityAccessCommandContext context) {
        Permission changed=getPermission(permissionId).update(resourceType,action,sensitivity,scopeKind,active,clock.instant());
        return execute(tx->{ repository.updatePermission(changed); tx.append(event("iam.permission.updated.v1",changed.id(),context.correlationId(),changed.updatedAt(),permissionPayload(changed)));
            auditMutation(context,"iam.permission.update","permission",changed.id().toString(),scopeFor(changed.organizationId()),"SUCCESS",Map.of("code",changed.code())); return changed; });
    }

    public Permission deletePermission(DomainIdentifier permissionId,IdentityAccessCommandContext context) {
        Permission changed=getPermission(permissionId).delete(clock.instant());
        return execute(tx->{ repository.updatePermission(changed); tx.append(event("iam.permission.deleted.v1",changed.id(),context.correlationId(),changed.updatedAt(),permissionPayload(changed)));
            auditMutation(context,"iam.permission.delete","permission",changed.id().toString(),scopeFor(changed.organizationId()),"SUCCESS",Map.of("code",changed.code())); return changed; });
    }

    public Role createRole(DomainIdentifier orgId,String code,String displayName,ScopeKind scopeKind,Set<String> permissionCodes,IdentityAccessCommandContext context) {
        requireOrganizationScope(orgId,null); requirePermissionSet(permissionCodes); Instant now=clock.instant(); Role role=new Role(ids.next(),orgId,code,displayName,scopeKind,false,true,now,now,null);
        return execute(tx->{ repository.insertRole(role,normalizedPermissions(permissionCodes)); tx.append(event("iam.role.created.v1",role.id(),context.correlationId(),now,rolePayload(role)));
            auditMutation(context,"iam.role.create","role",role.id().toString(),scopeFor(orgId),"SUCCESS",Map.of("code",role.code())); return role; });
    }

    public Role updateRole(DomainIdentifier roleId,String code,String displayName,Set<String> permissionCodes,IdentityAccessCommandContext context) {
        requirePermissionSet(permissionCodes); Role changed=getRole(roleId).update(code,displayName,clock.instant());
        return execute(tx->{ repository.updateRole(changed,normalizedPermissions(permissionCodes)); tx.append(event("iam.role.updated.v1",changed.id(),context.correlationId(),changed.updatedAt(),rolePayload(changed)));
            auditMutation(context,"iam.role.update","role",changed.id().toString(),scopeFor(changed.organizationId()),"SUCCESS",Map.of("code",changed.code())); return changed; });
    }

    public Role deleteRole(DomainIdentifier roleId,boolean force,IdentityAccessCommandContext context) {
        Role role=getRole(roleId); Instant now=clock.instant(); long active=repository.activeAssignmentCount(roleId,now);
        if(active>0&&!force) throw new IdentityAccessException("IAM_ROLE_ASSIGNED","role has active assignments; explicit force is required");
        Role changed=role.delete(now);
        return execute(tx->{ if(active>0) repository.revokeAssignmentsForRole(roleId,context.actorId(),now); repository.updateRole(changed,Set.of());
            tx.append(event("iam.role.deleted.v1",changed.id(),context.correlationId(),now,rolePayload(changed)));
            auditMutation(context,"iam.role.delete","role",changed.id().toString(),scopeFor(changed.organizationId()),"SUCCESS",Map.of("forced",Boolean.toString(force),"revoked_assignments",Long.toString(active))); return changed; });
    }

    public RoleAssignment assignRole(DomainIdentifier roleId,AssignmentActorType actorType,DomainIdentifier actorId,AuthorizationScope scope,
            Instant effectiveFrom,Instant effectiveTo,IdentityAccessCommandContext context) {
        Role role=getRole(roleId); Instant from=effectiveFrom==null?clock.instant():effectiveFrom; requireCompatibleScope(role,scope);
        requireOrganizationScope(scope.organizationId(),scope.subdivisionId());
        requireSystemRoleAdministrator(role, context.actorId(), from);
        if(actorType==AssignmentActorType.USER) {
            getUser(actorId);
            if(scope.kind()!=ScopeKind.PLATFORM&&!repository.hasEffectiveMembership(actorId,scope,from)) throw new IdentityAccessException("IAM_ASSIGNMENT_SCOPE_MISMATCH","user has no effective membership for requested role scope");
        } else {
            if(scope.organizationId()==null) throw new IdentityAccessException("IAM_ASSIGNMENT_SCOPE_MISMATCH","group role assignments require organization scope");
            getGroup(scope.organizationId(),actorId);
        }
        assignmentGuard.check(roleId,actorType,actorId,scope,from);
        RoleAssignment assignment=new RoleAssignment(ids.next(),roleId,actorType,actorId,scope,from,effectiveTo,null,null);
        return execute(tx->{ repository.insertAssignment(assignment); tx.append(event("iam.role.assigned.v1",roleId,context.correlationId(),clock.instant(),assignmentPayload(assignment)));
            auditMutation(context,"iam.role.assign","role",roleId.toString(),auditScope(scope),"SUCCESS",Map.of("assignment_id",assignment.id().toString(),"actor_id",actorId.toString(),"actor_type",actorType.name())); return assignment; });
    }

    public void revokeAssignment(DomainIdentifier roleId,DomainIdentifier assignmentId,IdentityAccessCommandContext context) {
        Role role=getRole(roleId);
        RoleAssignment assignment=getAssignment(assignmentId);
        if(!assignment.roleId().equals(roleId)) throw new IdentityAccessException("IAM_ASSIGNMENT_ROLE_MISMATCH","role assignment does not belong to requested role");
        if(assignment.revokedAt()!=null) throw new IdentityAccessException("IAM_ASSIGNMENT_ALREADY_REVOKED","role assignment is already revoked");
        Instant now=clock.instant(); requireSystemRoleAdministrator(role, context.actorId(), now); execute(tx->{ repository.revokeAssignment(assignmentId,context.actorId(),now);
            tx.append(event("iam.role.unassigned.v1",roleId,context.correlationId(),now,"{\"role_id\":\""+roleId+"\",\"assignment_id\":\""+assignmentId+"\"}"));
            auditMutation(context,"iam.role.unassign","role",roleId.toString(),auditScope(assignment.scope()),"SUCCESS",Map.of("assignment_id",assignmentId.toString())); return null; });
    }

    private IdentityUser userTransition(DomainIdentifier userId,IdentityAccessCommandContext context,String eventName,String action,UserTransition transition){
        IdentityUser current=getUser(userId); IdentityUser changed=transition.apply(current,clock.instant());
        return execute(tx->{ repository.updateUser(changed); tx.append(event(eventName,userId,context.correlationId(),changed.updatedAt(),userPayload(changed)));
            auditMutation(context,action,"user",userId.toString(),scopeFor(null),"SUCCESS",Map.of("status",changed.status().name())); return changed; });
    }


    private void requireOrganizationScope(DomainIdentifier organizationId, DomainIdentifier subdivisionId) {
        if (organizationId == null) {
            if (subdivisionId != null) {
                throw new IdentityAccessException("IAM_SCOPE_REFERENCE_INVALID",
                        "subdivision reference requires an organization reference");
            }
            return;
        }
        if (!organizationScopes.organizationExists(organizationId)) {
            throw new IdentityAccessException("IAM_ORGANIZATION_NOT_FOUND",
                    "referenced organization does not exist");
        }
        if (subdivisionId != null && !organizationScopes.subdivisionExists(organizationId, subdivisionId)) {
            throw new IdentityAccessException("IAM_SUBDIVISION_NOT_FOUND",
                    "referenced subdivision does not exist in organization");
        }
    }

    private void requireSystemRoleAdministrator(Role role, DomainIdentifier actorId, Instant at) {
        if (role.systemRole() && !repository.hasEffectiveSystemRole(actorId, Role.PLATFORM_ADMIN_CODE, at)) {
            throw new IdentityAccessException("IAM_SYSTEM_ROLE_ASSIGNMENT_FORBIDDEN",
                    "only an effective platform administrator can assign or revoke a system role");
        }
    }

    private void requireCompatibleScope(Role role,AuthorizationScope scope){
        Objects.requireNonNull(scope,"scope");
        if(role.systemRole() && scope.kind()!=ScopeKind.PLATFORM) throw new IdentityAccessException("IAM_ASSIGNMENT_SCOPE_MISMATCH","system role requires platform scope");
        if(role.organizationId()!=null&&!role.organizationId().equals(scope.organizationId())) throw new IdentityAccessException("IAM_ASSIGNMENT_SCOPE_MISMATCH","role belongs to another organization");
        if(role.scopeKind()==ScopeKind.SUBDIVISION&&scope.kind()!=ScopeKind.SUBDIVISION) throw new IdentityAccessException("IAM_ASSIGNMENT_SCOPE_MISMATCH","subdivision role requires subdivision scope");
        if(role.scopeKind()==ScopeKind.ORGANIZATION&&scope.kind()==ScopeKind.PLATFORM) throw new IdentityAccessException("IAM_ASSIGNMENT_SCOPE_MISMATCH","organization role cannot be assigned globally");
    }

    private <T>T execute(TransactionalWork<T> work){ try{return events.execute(work).value();}catch(TransactionExecutionException ex){Throwable c=ex.getCause(); if(c instanceof RuntimeException r)throw r; throw ex;} }
    private void auditMutation(IdentityAccessCommandContext context,String action,String targetType,String targetId,AuditScope scope,String result,Map<String,String> metadata){
        audit.append(new AuditEntry(ids.next(),scope,context.actorId().toString(),"USER",action,targetType,targetId,"ALLOW",clock.instant(),context.correlationId(),result,context.origin(),context.reason(),null,null,metadata,"ELEVATED"));
    }
    private EventEnvelope event(String type,DomainIdentifier aggregateId,DomainIdentifier correlationId,Instant at,String payload){return new EventEnvelope(ids.next(),new EventType(type),EVENT_VERSION,at,SOURCE,correlationId,aggregateId,payload);}
    private static AuditScope scopeFor(DomainIdentifier orgId){return orgId==null?AuditScope.platform():AuditScope.organization(orgId.toString());}
    private static AuditScope auditScope(AuthorizationScope scope){return scope.organizationId()==null?AuditScope.platform():AuditScope.organization(scope.organizationId().toString());}
    private static Set<String> normalizedPermissions(Set<String> codes){TreeSet<String> out=new TreeSet<>(); for(String code:codes)out.add(Permission.normalizeCode(code)); return Set.copyOf(out);}
    private static void requirePermissionSet(Set<String> codes){if(codes==null||codes.isEmpty())throw new IdentityAccessException("IAM_ROLE_EMPTY","role requires at least one permission");}
    private static void page(int offset,int limit){if(offset<0||limit<1||limit>200)throw new IllegalArgumentException("pagination must use offset >= 0 and limit between 1 and 200");}
    private static IdentityAccessException notFound(String code,String message){return new IdentityAccessException(code,message);}
    private static String userPayload(IdentityUser u){return "{\"user_id\":\""+u.id()+"\",\"login\":\""+json(u.login())+"\",\"status\":\""+u.status().name().toLowerCase(Locale.ROOT)+"\"}";}
    private static String membershipPayload(UserMembership m){return "{\"user_id\":\""+m.userId()+"\",\"membership_id\":\""+m.id()+"\",\"organization_id\":\""+m.organizationId()+"\"}";}
    private static String groupPayload(IdentityGroup g){return "{\"group_id\":\""+g.id()+"\",\"organization_id\":\""+g.organizationId()+"\",\"code\":\""+json(g.code())+"\"}";}
    private static String memberPayload(DomainIdentifier g,String type,DomainIdentifier member){return "{\"group_id\":\""+g+"\",\"member_type\":\""+type+"\",\"member_id\":\""+member+"\"}";}
    private static String permissionPayload(Permission p){return "{\"permission_id\":\""+p.id()+"\",\"code\":\""+json(p.code())+"\"}";}
    private static String rolePayload(Role r){return "{\"role_id\":\""+r.id()+"\",\"code\":\""+json(r.code())+"\"}";}
    private static String assignmentPayload(RoleAssignment a){return "{\"assignment_id\":\""+a.id()+"\",\"role_id\":\""+a.roleId()+"\",\"actor_id\":\""+a.actorId()+"\"}";}
    private static String json(String value){return value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");}
    @FunctionalInterface private interface UserTransition{IdentityUser apply(IdentityUser user,Instant now);}
}
