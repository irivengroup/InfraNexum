package io.infranexum.identity.access.application;

import io.infranexum.core.audit.*;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.IdentityGroup;
import io.infranexum.identity.access.domain.Role;
import io.infranexum.identity.access.ports.IdentityAccessRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deny-by-default RBAC evaluator used by every Server enforcement point. */
public final class RbacAuthorizationService {
    private final IdentityAccessRepository repository;
    private final AuditJournal audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public RbacAuthorizationService(IdentityAccessRepository repository, AuditJournal audit, UuidV7Generator ids, Clock clock) {
        this.repository=Objects.requireNonNull(repository,"repository"); this.audit=Objects.requireNonNull(audit,"audit");
        this.ids=Objects.requireNonNull(ids,"ids"); this.clock=Objects.requireNonNull(clock,"clock");
    }

    public AuthorizationDecision decide(DomainIdentifier userId, String permissionCode, AuthorizationScope scope, DomainIdentifier correlationId, String targetType, String targetId, String origin) {
        Objects.requireNonNull(userId,"userId"); Objects.requireNonNull(permissionCode,"permissionCode"); Objects.requireNonNull(scope,"scope");
        Instant now=clock.instant();
        boolean allowed=repository.hasEffectivePermission(userId, permissionCode, scope, now);
        AuthorizationDecision decision=allowed
                ? AuthorizationDecision.allow("RBAC_PERMISSION_GRANTED", "effective role assignment grants "+permissionCode)
                : AuthorizationDecision.deny("RBAC_PERMISSION_DENIED", "no effective role assignment grants "+permissionCode);
        auditDecision(userId, permissionCode, scope, correlationId, targetType, targetId, origin, decision, now);
        return decision;
    }

    /** Organization-root reads use the normative membership invariant because draft.21 defines no organization.read permission. */
    public AuthorizationDecision decideOrganizationVisibility(DomainIdentifier userId, DomainIdentifier organizationId, DomainIdentifier correlationId, String origin) {
        Objects.requireNonNull(organizationId,"organizationId"); Instant now=clock.instant();
        AuthorizationScope scope=AuthorizationScope.organization(organizationId);
        boolean allowed=repository.hasEffectiveSystemRole(userId, Role.PLATFORM_ADMIN_CODE, now)
                || repository.hasEffectiveMembership(userId, scope, now);
        AuthorizationDecision decision=allowed
                ? AuthorizationDecision.allow("RBAC_ORGANIZATION_VISIBLE", "platform administrator or effective organization membership")
                : AuthorizationDecision.deny("RBAC_ORGANIZATION_NOT_VISIBLE", "no effective organization membership");
        auditDecision(userId, "organization.visibility", scope, correlationId, "organization", organizationId.toString(), origin, decision, now);
        return decision;
    }


    /** Resolves a group-owned scope before evaluating a group permission for org-less IAM routes. */
    public AuthorizationDecision decideGroupPermission(DomainIdentifier userId, String permissionCode, DomainIdentifier groupId,
            DomainIdentifier correlationId, String origin) {
        Objects.requireNonNull(groupId, "groupId");
        IdentityGroup group = repository.findGroup(groupId).orElse(null);
        if (group == null || group.deleted()) {
            AuthorizationDecision decision = AuthorizationDecision.deny("RBAC_GROUP_NOT_VISIBLE", "group is absent or deleted");
            auditDecision(userId, permissionCode, AuthorizationScope.platform(), correlationId, "group", groupId.toString(), origin, decision, clock.instant());
            return decision;
        }
        return decide(userId, permissionCode, AuthorizationScope.organization(group.organizationId()), correlationId, "group", groupId.toString(), origin);
    }

    /** Evaluates one permission for another actor while preserving the evaluator as the audit actor. */
    public AuthorizationDecision evaluatePermission(DomainIdentifier evaluatedUserId, String permissionCode, AuthorizationScope scope,
            DomainIdentifier evaluatorId, DomainIdentifier correlationId, String origin) {
        Objects.requireNonNull(evaluatedUserId, "evaluatedUserId"); Objects.requireNonNull(permissionCode, "permissionCode");
        Objects.requireNonNull(scope, "scope"); Objects.requireNonNull(evaluatorId, "evaluatorId");
        Instant now = clock.instant();
        boolean allowed = repository.hasEffectivePermission(evaluatedUserId, permissionCode, scope, now);
        AuthorizationDecision decision = allowed
                ? AuthorizationDecision.allow("RBAC_PERMISSION_GRANTED", "effective role assignment grants " + permissionCode)
                : AuthorizationDecision.deny("RBAC_PERMISSION_DENIED", "no effective role assignment grants " + permissionCode);
        AuditScope auditScope = scope.organizationId()==null ? AuditScope.platform() : AuditScope.organization(scope.organizationId().toString());
        audit.append(new AuditEntry(ids.next(), auditScope, evaluatorId.toString(), "USER", "iam.permission.evaluate", "user",
                evaluatedUserId.toString(), decision.allowed()?"ALLOW":"DENY", now, correlationId,
                decision.allowed()?"SUCCESS":"DENIED", origin, decision.explanation(), null, null,
                Map.of("decision_code", decision.code(), "permission_code", permissionCode), "NORMAL"));
        return decision;
    }

    /** Evaluates the full effective RBAC permission set for an actor at one requested scope. */
    public Set<String> effectivePermissions(DomainIdentifier evaluatedUserId, AuthorizationScope scope, DomainIdentifier evaluatorId,
            DomainIdentifier correlationId, String origin) {
        Objects.requireNonNull(evaluatedUserId, "evaluatedUserId"); Objects.requireNonNull(scope, "scope");
        Instant now = clock.instant();
        Set<String> result = repository.effectivePermissionCodes(evaluatedUserId, scope, now);
        AuditScope auditScope = scope.organizationId()==null ? AuditScope.platform() : AuditScope.organization(scope.organizationId().toString());
        audit.append(new AuditEntry(ids.next(), auditScope, evaluatorId.toString(), "USER", "iam.permission.evaluate", "user",
                evaluatedUserId.toString(), "ALLOW", now, correlationId, "SUCCESS", origin,
                "effective RBAC permissions evaluated", null, null, Map.of("permission_count", Integer.toString(result.size())), "NORMAL"));
        return result;
    }

    public AuthorizationDecision decidePlatformAdministrator(DomainIdentifier userId, DomainIdentifier correlationId, String targetType, String targetId, String origin) {
        Instant now=clock.instant(); boolean allowed=repository.hasEffectiveSystemRole(userId, Role.PLATFORM_ADMIN_CODE, now);
        AuthorizationDecision decision=allowed
                ? AuthorizationDecision.allow("RBAC_PLATFORM_ADMIN_GRANTED", "effective system.platform_admin assignment")
                : AuthorizationDecision.deny("RBAC_PLATFORM_ADMIN_REQUIRED", "system.platform_admin is required for this operation");
        auditDecision(userId, "system.platform_admin", AuthorizationScope.platform(), correlationId, targetType, targetId, origin, decision, now);
        return decision;
    }

    public AuthorizationDecision denyUnregisteredRoute(DomainIdentifier userId, DomainIdentifier correlationId, String route, String origin) {
        Objects.requireNonNull(route, "route");
        Instant now=clock.instant();
        AuthorizationDecision decision=AuthorizationDecision.deny("RBAC_ROUTE_UNREGISTERED", "API route has no registered RBAC policy");
        AuditScope auditScope=AuditScope.platform();
        String auditedRoute=route.strip();
        if(auditedRoute.length()>1024) auditedRoute=auditedRoute.substring(0,1024);
        audit.append(new AuditEntry(ids.next(),auditScope,userId.toString(),"USER","rbac.route.unregistered","api-route","unregistered-route",
                "DENY",now,correlationId,"DENIED",origin,decision.explanation(),null,null,
                Map.of("decision_code",decision.code(),"route",auditedRoute),"NORMAL"));
        return decision;
    }

    public boolean isPlatformAdministrator(DomainIdentifier userId) {
        return repository.hasEffectiveSystemRole(userId, Role.PLATFORM_ADMIN_CODE, clock.instant());
    }

    private void auditDecision(DomainIdentifier userId, String action, AuthorizationScope scope, DomainIdentifier correlationId, String targetType, String targetId, String origin, AuthorizationDecision decision, Instant now) {
        AuditScope auditScope=scope.organizationId()==null ? AuditScope.platform() : AuditScope.organization(scope.organizationId().toString());
        audit.append(new AuditEntry(ids.next(), auditScope, userId.toString(), "USER", action, targetType, targetId,
                decision.allowed()?"ALLOW":"DENY", now, correlationId, decision.allowed()?"SUCCESS":"DENIED", origin,
                decision.explanation(), null, null, Map.of("decision_code",decision.code()), "NORMAL"));
    }
}
