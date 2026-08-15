package io.infranexum.server.identityaccess;

import io.infranexum.identity.access.domain.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;

/** Stable JSON request/response records for the PGM-03-E03 IAM HTTP surface. */
final class IdentityAccessApiModels {
    private IdentityAccessApiModels() {}

    record CreateUserRequest(
            @NotBlank @Size(max=128) String login,
            @Email @Size(max=320) String email,
            @NotBlank @Size(max=200) String displayName,
            boolean activate,
            @Size(max=1024) String reason) {}
    record UpdateUserRequest(@Email @Size(max=320) String email,@NotBlank @Size(max=200) String displayName,@Size(max=1024) String reason) {}
    record ReasonRequest(@Size(max=1024) String reason) {}
    record MembershipRequest(@NotBlank String organizationId,String subdivisionId,@Size(max=80) String effectiveFrom,@Size(max=80) String effectiveTo,@Size(max=1024) String reason) {}
    record UserRoleRequest(@NotBlank String roleId,@NotNull ScopeKind scopeKind,String organizationId,String subdivisionId,@Size(max=80) String effectiveFrom,@Size(max=80) String effectiveTo,@Size(max=1024) String reason) {}

    record CreateGroupRequest(@NotBlank @Size(max=96) String code,@NotBlank @Size(max=200) String displayName,@Size(max=1024) String reason) {}
    record UpdateGroupRequest(@NotBlank @Size(max=200) String displayName,@Size(max=1024) String reason) {}
    record GroupMemberRequest(@NotNull AssignmentActorType memberType,@NotBlank String memberId,@Size(max=1024) String reason) {}
    record GroupRoleRequest(@NotBlank String roleId,@NotNull ScopeKind scopeKind,String subdivisionId,@Size(max=80) String effectiveFrom,@Size(max=80) String effectiveTo,@Size(max=1024) String reason) {}

    record CreateRoleRequest(@NotBlank @Size(max=160) String code,@NotBlank @Size(max=200) String displayName,@NotNull ScopeKind scopeKind,@NotEmpty Set<@NotBlank String> permissionCodes,@Size(max=1024) String reason) {}
    record UpdateRoleRequest(@NotBlank @Size(max=160) String code,@NotBlank @Size(max=200) String displayName,Set<@NotBlank String> permissionCodes,@Size(max=1024) String reason) {}
    record RoleAssignmentRequest(@NotNull AssignmentActorType actorType,@NotBlank String actorId,@NotNull ScopeKind scopeKind,String subdivisionId,@Size(max=80) String effectiveFrom,@Size(max=80) String effectiveTo,@Size(max=1024) String reason) {}

    record CreatePermissionRequest(@NotBlank @Size(max=160) String code,@NotBlank @Size(max=64) String resourceType,@NotBlank @Size(max=64) String action,@NotBlank @Size(max=16) String sensitivity,@NotNull ScopeKind scopeKind,@Size(max=1024) String reason) {}
    record UpdatePermissionRequest(@NotBlank @Size(max=64) String resourceType,@NotBlank @Size(max=64) String action,@NotBlank @Size(max=16) String sensitivity,@NotNull ScopeKind scopeKind,boolean active,@Size(max=1024) String reason) {}
    record PermissionValidationRequest(@NotBlank String actorId,@NotBlank @Size(max=160) String permissionCode,@NotNull ScopeKind scopeKind,String subdivisionId) {}
    record PermissionValidationResponse(String actorId,String permissionCode,String scopeKind,String organizationId,String subdivisionId,boolean allowed,String decisionCode,String explanation) {}
    record EffectivePermissionsResponse(String actorId,String scopeKind,String organizationId,String subdivisionId,Set<String> permissionCodes) {}
    record EffectiveMembersResponse(String groupId,Set<String> userIds) {}

    record UserResponse(String id,String login,String email,String displayName,String status,Instant createdAt,Instant updatedAt,Instant deletedAt) {
        static UserResponse from(IdentityUser u){return new UserResponse(u.id().toString(),u.login(),u.email(),u.displayName(),u.status().name().toLowerCase(java.util.Locale.ROOT),u.createdAt(),u.updatedAt(),u.deletedAt());}
    }
    record MembershipResponse(String id,String userId,String organizationId,String subdivisionId,Instant effectiveFrom,Instant effectiveTo,Instant revokedAt) {
        static MembershipResponse from(UserMembership m){return new MembershipResponse(m.id().toString(),m.userId().toString(),m.organizationId().toString(),m.subdivisionId()==null?null:m.subdivisionId().toString(),m.effectiveFrom(),m.effectiveTo(),m.revokedAt());}
    }
    record GroupResponse(String id,String organizationId,String code,String displayName,boolean systemGroup,Instant createdAt,Instant updatedAt,Instant deletedAt) {
        static GroupResponse from(IdentityGroup g){return new GroupResponse(g.id().toString(),g.organizationId().toString(),g.code(),g.displayName(),g.systemGroup(),g.createdAt(),g.updatedAt(),g.deletedAt());}
    }
    record RoleResponse(String id,String organizationId,String code,String displayName,String scopeKind,boolean systemRole,boolean active,Instant createdAt,Instant updatedAt,Instant deletedAt) {
        static RoleResponse from(Role r){return new RoleResponse(r.id().toString(),r.organizationId()==null?null:r.organizationId().toString(),r.code(),r.displayName(),r.scopeKind().name(),r.systemRole(),r.active(),r.createdAt(),r.updatedAt(),r.deletedAt());}
    }
    record PermissionResponse(String id,String organizationId,String code,String resourceType,String action,String sensitivity,String scopeKind,boolean systemDefined,boolean active,Instant createdAt,Instant updatedAt,Instant deletedAt) {
        static PermissionResponse from(Permission p){return new PermissionResponse(p.id().toString(),p.organizationId()==null?null:p.organizationId().toString(),p.code(),p.resourceType(),p.action(),p.sensitivity(),p.scopeKind().name(),p.systemDefined(),p.active(),p.createdAt(),p.updatedAt(),p.deletedAt());}
    }
    record RoleAssignmentResponse(String id,String roleId,String actorType,String actorId,String scopeKind,String organizationId,String subdivisionId,Instant effectiveFrom,Instant effectiveTo,Instant revokedAt,String revokedBy) {
        static RoleAssignmentResponse from(RoleAssignment a){return new RoleAssignmentResponse(a.id().toString(),a.roleId().toString(),a.actorType().name(),a.actorId().toString(),a.scope().kind().name(),a.scope().organizationId()==null?null:a.scope().organizationId().toString(),a.scope().subdivisionId()==null?null:a.scope().subdivisionId().toString(),a.effectiveFrom(),a.effectiveTo(),a.revokedAt(),a.revokedBy()==null?null:a.revokedBy().toString());}
    }
}
