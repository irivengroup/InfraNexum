package io.infranexum.server.identityaccess;

import static io.infranexum.server.identityaccess.IdentityAccessApiModels.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.identity.access.application.AuthorizationDecision;
import io.infranexum.identity.access.application.IdentityAccessAdminService;
import io.infranexum.identity.access.application.IdentityAccessCommandContext;
import io.infranexum.identity.access.application.RbacAuthorizationService;
import io.infranexum.identity.access.domain.AssignmentActorType;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.PermissionCodes;
import io.infranexum.identity.access.domain.ScopeKind;
import io.infranexum.server.configuration.ServerTemporalInputParser;
import io.infranexum.server.http.ApiPagination;
import io.infranexum.server.identity.LocalAuthenticationFilter;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** HTTP adapter for users, groups, roles, permissions, memberships and temporal assignments. */
@RestController
public final class IdentityAccessController {
    private final IdentityAccessAdminService service;
    private final RbacAuthorizationService authorization;
    private final UuidV7Generator ids;
    private final ServerTemporalInputParser temporal;

    public IdentityAccessController(IdentityAccessAdminService service,RbacAuthorizationService authorization,@Qualifier("platformClock") Clock clock,ServerTemporalInputParser temporal){
        this.service=Objects.requireNonNull(service,"service");this.authorization=Objects.requireNonNull(authorization,"authorization");this.ids=new UuidV7Generator(Objects.requireNonNull(clock,"clock"),new SecureRandom());this.temporal=Objects.requireNonNull(temporal,"temporal");
    }

    @GetMapping("/api/v1/iam/users")
    List<UserResponse> users(@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="50")int limit){return service.listUsers(offset,limit).stream().map(UserResponse::from).toList();}
    @PostMapping("/api/v1/iam/users")
    ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest body,HttpServletRequest request){var result=service.createUser(body.login(),body.email(),body.displayName(),body.activate(),context(request,body.reason()));return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(result));}
    @GetMapping("/api/v1/iam/users/{userId}")
    UserResponse user(@PathVariable String userId){return UserResponse.from(service.getUser(id(userId)));}
    @PatchMapping("/api/v1/iam/users/{userId}")
    UserResponse updateUser(@PathVariable String userId,@Valid @RequestBody UpdateUserRequest body,HttpServletRequest request){return UserResponse.from(service.updateUser(id(userId),body.email(),body.displayName(),context(request,body.reason())));}
    @DeleteMapping("/api/v1/iam/users/{userId}")
    UserResponse deleteUser(@PathVariable String userId,@RequestParam(required=false)String reason,HttpServletRequest request){return UserResponse.from(service.deleteUser(id(userId),context(request,reason)));}
    @PostMapping("/api/v1/iam/users/{userId}/activate")
    UserResponse activateUser(@PathVariable String userId,@Valid @RequestBody(required=false) ReasonRequest body,HttpServletRequest request){return UserResponse.from(service.activateUser(id(userId),context(request,body==null?null:body.reason())));}
    @PostMapping("/api/v1/iam/users/{userId}/suspend")
    UserResponse suspendUser(@PathVariable String userId,@Valid @RequestBody(required=false) ReasonRequest body,HttpServletRequest request){return UserResponse.from(service.suspendUser(id(userId),context(request,body==null?null:body.reason())));}
    @GetMapping("/api/v1/iam/users/{userId}/memberships")
    ResponseEntity<List<MembershipResponse>> memberships(@PathVariable String userId,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="50")int limit){var page=service.memberships(id(userId),offset,limit);return ApiPagination.offset(page.items().stream().map(MembershipResponse::from).toList(),page.nextOffset(),limit);}
    @PostMapping("/api/v1/iam/users/{userId}/memberships")
    ResponseEntity<MembershipResponse> addMembership(@PathVariable String userId,@Valid @RequestBody MembershipRequest body,HttpServletRequest request){var result=service.addMembership(id(userId),id(body.organizationId()),nullableId(body.subdivisionId()),instant(body.effectiveFrom(),"effectiveFrom"),instant(body.effectiveTo(),"effectiveTo"),context(request,body.reason()));return ResponseEntity.status(HttpStatus.CREATED).body(MembershipResponse.from(result));}
    @PostMapping("/api/v1/iam/users/{userId}/roles")
    ResponseEntity<RoleAssignmentResponse> assignUserRole(@PathVariable String userId,@Valid @RequestBody UserRoleRequest body,HttpServletRequest request){var scope=scope(body.scopeKind(),body.organizationId(),body.subdivisionId());var result=service.assignRole(id(body.roleId()),AssignmentActorType.USER,id(userId),scope,instant(body.effectiveFrom(),"effectiveFrom"),instant(body.effectiveTo(),"effectiveTo"),context(request,body.reason()));return ResponseEntity.status(HttpStatus.CREATED).body(RoleAssignmentResponse.from(result));}

    @GetMapping("/api/v1/iam/groups/{groupId}/effective-members")
    EffectiveMembersResponse effectiveGroupMembers(@PathVariable String groupId){
        Set<String> users=service.effectiveGroupMembers(id(groupId)).stream().map(DomainIdentifier::toString).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        return new EffectiveMembersResponse(groupId,Set.copyOf(users));
    }

    @GetMapping("/api/v1/organizations/{orgId}/groups")
    List<GroupResponse> groups(@PathVariable String orgId,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="50")int limit){return service.listGroups(id(orgId),offset,limit).stream().map(GroupResponse::from).toList();}
    @PostMapping("/api/v1/organizations/{orgId}/groups")
    ResponseEntity<GroupResponse> createGroup(@PathVariable String orgId,@Valid @RequestBody CreateGroupRequest body,HttpServletRequest request){var result=service.createGroup(id(orgId),body.code(),body.displayName(),context(request,body.reason()));return ResponseEntity.status(HttpStatus.CREATED).body(GroupResponse.from(result));}
    @GetMapping("/api/v1/organizations/{orgId}/groups/{groupId}")
    GroupResponse group(@PathVariable String orgId,@PathVariable String groupId){return GroupResponse.from(service.getGroup(id(orgId),id(groupId)));}
    @PatchMapping("/api/v1/organizations/{orgId}/groups/{groupId}")
    GroupResponse updateGroup(@PathVariable String orgId,@PathVariable String groupId,@Valid @RequestBody UpdateGroupRequest body,HttpServletRequest request){return GroupResponse.from(service.updateGroup(id(orgId),id(groupId),body.displayName(),context(request,body.reason())));}
    @DeleteMapping("/api/v1/organizations/{orgId}/groups/{groupId}")
    GroupResponse deleteGroup(@PathVariable String orgId,@PathVariable String groupId,@RequestParam(required=false)String reason,HttpServletRequest request){return GroupResponse.from(service.deleteGroup(id(orgId),id(groupId),context(request,reason)));}
    @PostMapping("/api/v1/organizations/{orgId}/groups/{groupId}/members")
    ResponseEntity<Void> addGroupMember(@PathVariable String orgId,@PathVariable String groupId,@Valid @RequestBody GroupMemberRequest body,HttpServletRequest request){if(body.memberType()==AssignmentActorType.USER)service.addUserToGroup(id(orgId),id(groupId),id(body.memberId()),context(request,body.reason()));else{requirePermission(request,PermissionCodes.GROUP_ADD_GROUP,AuthorizationScope.organization(id(orgId)),"group",groupId);service.addGroupToGroup(id(orgId),id(groupId),id(body.memberId()),context(request,body.reason()));}return ResponseEntity.noContent().build();}
    @DeleteMapping("/api/v1/organizations/{orgId}/groups/{groupId}/members/{memberId}")
    ResponseEntity<Void> removeGroupMember(@PathVariable String orgId,@PathVariable String groupId,@PathVariable String memberId,@RequestParam(defaultValue="USER") AssignmentActorType memberType,@RequestParam(required=false)String reason,HttpServletRequest request){if(memberType==AssignmentActorType.USER)service.removeUserFromGroup(id(orgId),id(groupId),id(memberId),context(request,reason));else{requirePermission(request,PermissionCodes.GROUP_REMOVE_GROUP,AuthorizationScope.organization(id(orgId)),"group",groupId);service.removeGroupFromGroup(id(orgId),id(groupId),id(memberId),context(request,reason));}return ResponseEntity.noContent().build();}
    @PostMapping("/api/v1/organizations/{orgId}/groups/{groupId}/roles")
    ResponseEntity<RoleAssignmentResponse> assignGroupRole(@PathVariable String orgId,@PathVariable String groupId,@Valid @RequestBody GroupRoleRequest body,HttpServletRequest request){AuthorizationScope scope=scope(body.scopeKind(),orgId,body.subdivisionId());var result=service.assignRole(id(body.roleId()),AssignmentActorType.GROUP,id(groupId),scope,instant(body.effectiveFrom(),"effectiveFrom"),instant(body.effectiveTo(),"effectiveTo"),context(request,body.reason()));return ResponseEntity.status(HttpStatus.CREATED).body(RoleAssignmentResponse.from(result));}

    @GetMapping("/api/v1/organizations/{orgId}/roles")
    List<RoleResponse> roles(@PathVariable String orgId,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="50")int limit){return service.listRoles(id(orgId),offset,limit).stream().map(RoleResponse::from).toList();}
    @PostMapping("/api/v1/organizations/{orgId}/roles")
    ResponseEntity<RoleResponse> createRole(@PathVariable String orgId,@Valid @RequestBody CreateRoleRequest body,HttpServletRequest request){var result=service.createRole(id(orgId),body.code(),body.displayName(),body.scopeKind(),body.permissionCodes(),context(request,body.reason()));return ResponseEntity.status(HttpStatus.CREATED).body(RoleResponse.from(result));}
    @GetMapping("/api/v1/organizations/{orgId}/roles/{roleId}")
    RoleResponse role(@PathVariable String orgId,@PathVariable String roleId){RoleResponse result=RoleResponse.from(service.getRole(id(roleId)));requireOwned(result.organizationId(),orgId);return result;}
    @PatchMapping("/api/v1/organizations/{orgId}/roles/{roleId}")
    RoleResponse updateRole(@PathVariable String orgId,@PathVariable String roleId,@Valid @RequestBody UpdateRoleRequest body,HttpServletRequest request){RoleResponse current=RoleResponse.from(service.getRole(id(roleId)));requireOwned(current.organizationId(),orgId);Set<String> permissions=body.permissionCodes()==null?service.rolePermissionCodes(id(roleId)):body.permissionCodes();return RoleResponse.from(service.updateRole(id(roleId),body.code(),body.displayName(),permissions,context(request,body.reason())));}
    @DeleteMapping("/api/v1/organizations/{orgId}/roles/{roleId}")
    RoleResponse deleteRole(@PathVariable String orgId,@PathVariable String roleId,@RequestParam(defaultValue="false")boolean force,@RequestParam(required=false)String reason,HttpServletRequest request){RoleResponse current=RoleResponse.from(service.getRole(id(roleId)));requireOwned(current.organizationId(),orgId);return RoleResponse.from(service.deleteRole(id(roleId),force,context(request,reason)));}
    @GetMapping("/api/v1/organizations/{orgId}/roles/{roleId}/assignments")
    ResponseEntity<List<RoleAssignmentResponse>> roleAssignments(@PathVariable String orgId,@PathVariable String roleId,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="50")int limit){RoleResponse current=RoleResponse.from(service.getRole(id(roleId)));requireOwned(current.organizationId(),orgId);var page=service.assignments(id(roleId),offset,limit);return ApiPagination.offset(page.items().stream().map(RoleAssignmentResponse::from).toList(),page.nextOffset(),limit);}
    @PostMapping("/api/v1/organizations/{orgId}/roles/{roleId}/assignments")
    ResponseEntity<RoleAssignmentResponse> assignRole(@PathVariable String orgId,@PathVariable String roleId,@Valid @RequestBody RoleAssignmentRequest body,HttpServletRequest request){var result=service.assignRole(id(roleId),body.actorType(),id(body.actorId()),scope(body.scopeKind(),orgId,body.subdivisionId()),instant(body.effectiveFrom(),"effectiveFrom"),instant(body.effectiveTo(),"effectiveTo"),context(request,body.reason()));return ResponseEntity.status(HttpStatus.CREATED).body(RoleAssignmentResponse.from(result));}
    @DeleteMapping("/api/v1/organizations/{orgId}/roles/{roleId}/assignments/{assignmentId}")
    ResponseEntity<Void> revokeRole(@PathVariable String orgId,@PathVariable String roleId,@PathVariable String assignmentId,@RequestParam(required=false)String reason,HttpServletRequest request){RoleResponse current=RoleResponse.from(service.getRole(id(roleId)));requireOwned(current.organizationId(),orgId);service.revokeAssignment(id(roleId),id(assignmentId),context(request,reason));return ResponseEntity.noContent().build();}

    @GetMapping("/api/v1/organizations/{orgId}/permissions")
    List<PermissionResponse> permissions(@PathVariable String orgId,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="100")int limit){return service.listPermissions(id(orgId),offset,limit).stream().map(PermissionResponse::from).toList();}
    @PostMapping("/api/v1/organizations/{orgId}/permissions")
    ResponseEntity<PermissionResponse> createPermission(@PathVariable String orgId,@Valid @RequestBody CreatePermissionRequest body,HttpServletRequest request){var result=service.createPermission(id(orgId),body.code(),body.resourceType(),body.action(),body.sensitivity(),body.scopeKind(),context(request,body.reason()));return ResponseEntity.status(HttpStatus.CREATED).body(PermissionResponse.from(result));}
    @GetMapping("/api/v1/organizations/{orgId}/permissions/effective")
    EffectivePermissionsResponse effectivePermissions(@PathVariable String orgId,@RequestParam String actorId,@RequestParam(required=false)String subdivisionId,HttpServletRequest request){
        AuthorizationScope target=subdivisionId==null||subdivisionId.isBlank()?AuthorizationScope.organization(id(orgId)):AuthorizationScope.subdivision(id(orgId),id(subdivisionId));
        DomainIdentifier evaluator=authenticatedActor(request);DomainIdentifier correlation=correlation(request);
        Set<String> codes=authorization.effectivePermissions(id(actorId),target,evaluator,correlation,"HTTP");
        return new EffectivePermissionsResponse(actorId,target.kind().name(),orgId,target.subdivisionId()==null?null:target.subdivisionId().toString(),codes);
    }
    @PostMapping("/api/v1/organizations/{orgId}/permissions/validate")
    PermissionValidationResponse validatePermission(@PathVariable String orgId,@Valid @RequestBody PermissionValidationRequest body,HttpServletRequest request){
        if(body.scopeKind()==ScopeKind.PLATFORM)throw new IllegalArgumentException("platform scope is invalid on an organization permission evaluation endpoint");
        AuthorizationScope target=scope(body.scopeKind(),orgId,body.subdivisionId());DomainIdentifier evaluator=authenticatedActor(request);DomainIdentifier correlation=correlation(request);
        AuthorizationDecision decision=authorization.evaluatePermission(id(body.actorId()),body.permissionCode(),target,evaluator,correlation,"HTTP");
        return new PermissionValidationResponse(body.actorId(),body.permissionCode(),target.kind().name(),orgId,target.subdivisionId()==null?null:target.subdivisionId().toString(),decision.allowed(),decision.code(),decision.explanation());
    }

    @GetMapping("/api/v1/organizations/{orgId}/permissions/{permissionId}")
    PermissionResponse permission(@PathVariable String orgId,@PathVariable String permissionId){PermissionResponse result=PermissionResponse.from(service.getPermission(id(permissionId)));if(result.organizationId()!=null)requireOwned(result.organizationId(),orgId);return result;}
    @PatchMapping("/api/v1/organizations/{orgId}/permissions/{permissionId}")
    PermissionResponse updatePermission(@PathVariable String orgId,@PathVariable String permissionId,@Valid @RequestBody UpdatePermissionRequest body,HttpServletRequest request){permission(orgId,permissionId);return PermissionResponse.from(service.updatePermission(id(permissionId),body.resourceType(),body.action(),body.sensitivity(),body.scopeKind(),body.active(),context(request,body.reason())));}
    @DeleteMapping("/api/v1/organizations/{orgId}/permissions/{permissionId}")
    PermissionResponse deletePermission(@PathVariable String orgId,@PathVariable String permissionId,@RequestParam(required=false)String reason,HttpServletRequest request){permission(orgId,permissionId);return PermissionResponse.from(service.deletePermission(id(permissionId),context(request,reason)));}

    private void requirePermission(HttpServletRequest request,String permission,AuthorizationScope scope,String targetType,String targetId){DomainIdentifier actor=authenticatedActor(request);AuthorizationDecision decision=authorization.decide(actor,permission,scope,correlation(request),targetType,targetId,"HTTP");if(!decision.allowed())throw new io.infranexum.identity.access.domain.IdentityAccessException("IAM_AUTHORIZATION_DENIED",decision.explanation());}
    private static DomainIdentifier authenticatedActor(HttpServletRequest request){Object actorValue=request.getAttribute(LocalAuthenticationFilter.ACCOUNT_ATTRIBUTE);if(!(actorValue instanceof DomainIdentifier actor))throw new IllegalStateException("authenticated actor missing after RBAC boundary");return actor;}
    private DomainIdentifier correlation(HttpServletRequest request){return CorrelationContext.identifier(request).orElseGet(ids::next);}
    private IdentityAccessCommandContext context(HttpServletRequest request,String reason){return new IdentityAccessCommandContext(authenticatedActor(request),correlation(request),reason==null||reason.isBlank()?"IAM administration":reason,"HTTP");}
    private java.time.Instant instant(String value,String field){return temporal.optionalInstant(value,field);}
    private static DomainIdentifier id(String value){return DomainIdentifier.parse(value);}
    private static DomainIdentifier nullableId(String value){return value==null||value.isBlank()?null:id(value);}
    private static AuthorizationScope scope(ScopeKind kind,String organizationId,String subdivisionId){return switch(kind){case PLATFORM->AuthorizationScope.platform();case ORGANIZATION->AuthorizationScope.organization(id(organizationId));case SUBDIVISION->AuthorizationScope.subdivision(id(organizationId),id(subdivisionId));};}
    private static void requireOwned(String actual,String requested){if(actual!=null&&!actual.equals(requested))throw new IllegalArgumentException("resource does not belong to requested organization");}
}
