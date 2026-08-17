package io.infranexum.server.identityaccess.cli;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.identity.access.application.AuthorizationDecision;
import io.infranexum.identity.access.application.IdentityAccessAdminService;
import io.infranexum.identity.access.application.IdentityAccessCommandContext;
import io.infranexum.identity.access.application.RbacAuthorizationService;
import io.infranexum.identity.access.application.PolicyDecisionService;
import io.infranexum.identity.access.domain.AssignmentActorType;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.IdentityGroup;
import io.infranexum.identity.access.domain.IdentityUser;
import io.infranexum.identity.access.domain.IdentityUserStatus;
import io.infranexum.identity.access.domain.Permission;
import io.infranexum.identity.access.domain.PermissionCodes;
import io.infranexum.identity.access.domain.PolicyEvaluationRequest;
import io.infranexum.identity.access.domain.PolicyObligation;
import io.infranexum.identity.access.domain.Role;
import io.infranexum.identity.access.domain.RoleAssignment;
import io.infranexum.identity.access.domain.ScopeKind;
import io.infranexum.identity.access.domain.UserMembership;
import io.infranexum.identity.access.ports.IdentityAccessFeaturePolicy;
import io.infranexum.server.platform.PlatformCapabilityService;
import io.infranexum.identity.local.application.AuthenticatedSession;
import io.infranexum.identity.local.application.LocalAuthenticationService;
import io.infranexum.identity.local.application.ValidatedSession;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * In-process IAM CLI adapter for RBAC and PGM-03-E04 advanced authorization.
 *
 * <p>The CLI never accepts passwords directly in argv. It authenticates from an explicitly supplied
 * UTF-8 password file, evaluates RBAC then ABAC through the same PEP services as HTTP, invokes the same IAM use
 * cases, and revokes the ephemeral session before returning.</p>
 */
public final class IdentityAccessCli {
    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_AUTHENTICATION = 3;
    public static final int EXIT_AUTHORIZATION = 4;
    public static final int EXIT_BUSINESS = 5;
    public static final int EXIT_INTERNAL = 70;

    private final LocalAuthenticationService authentication;
    private final IdentityAccessAdminService administration;
    private final RbacAuthorizationService authorization;
    private final PolicyDecisionService policyDecisions;
    private final IdentityAccessFeaturePolicy features;
    private final PlatformCapabilityService capabilities;
    private final UuidV7Generator ids;

    public IdentityAccessCli(
            LocalAuthenticationService authentication,
            IdentityAccessAdminService administration,
            RbacAuthorizationService authorization,
            PolicyDecisionService policyDecisions,
            IdentityAccessFeaturePolicy features,
            PlatformCapabilityService capabilities,
            UuidV7Generator identifiers) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.administration = Objects.requireNonNull(administration, "administration");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.policyDecisions = Objects.requireNonNull(policyDecisions, "policyDecisions");
        this.features = Objects.requireNonNull(features, "features");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.ids = Objects.requireNonNull(identifiers, "identifiers");
    }

    /** Executes one command and returns a stable process exit code. */
    public int run(String[] arguments, PrintWriter out, PrintWriter err) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        if (arguments.length == 0 || has(arguments, "--help") || has(arguments, "-h")) {
            out.print(help());
            out.flush();
            return EXIT_OK;
        }
        AuthenticatedSession authenticated = null;
        try {
            Arguments args = Arguments.parse(arguments);
            char[] password = readSecret(args.required("password-file"));
            try {
                authenticated = authentication.authenticate(args.required("username"), password);
            } catch (RuntimeException failure) {
                err.println("authentication failed");
                err.flush();
                return EXIT_AUTHENTICATION;
            }
            DomainIdentifier actor = authenticated.account().id();
            DomainIdentifier correlation = ids.next();
            IdentityAccessCommandContext context = new IdentityAccessCommandContext(
                    actor, correlation, args.optional("reason", "IAM CLI administration"), "CLI");
            String result = execute(args, actor, correlation, context);
            if (!result.isEmpty()) out.println(result);
            out.flush();
            return EXIT_OK;
        } catch (CliAuthorizationException failure) {
            err.println("authorization denied: " + failure.getMessage());
            err.flush();
            return EXIT_AUTHORIZATION;
        } catch (IllegalArgumentException failure) {
            err.println("usage error: " + safe(failure.getMessage()));
            err.flush();
            return EXIT_USAGE;
        } catch (io.infranexum.identity.access.domain.IdentityAccessException failure) {
            err.println(failure.code() + ": " + safe(failure.getMessage()));
            err.flush();
            return EXIT_BUSINESS;
        } catch (RuntimeException failure) {
            err.println("internal CLI failure: " + failure.getClass().getSimpleName());
            err.flush();
            return EXIT_INTERNAL;
        } finally {
            if (authenticated != null) {
                try {
                    authentication.logout(new ValidatedSession(authenticated.account(), authenticated.session()));
                } catch (RuntimeException ignored) {
                    // The command result must not be replaced by a best-effort ephemeral-session cleanup failure.
                }
            }
        }
    }

    private String execute(Arguments args, DomainIdentifier actor, DomainIdentifier correlation, IdentityAccessCommandContext context) {
        if (!"iam".equals(args.namespace())) throw new IllegalArgumentException("first argument must be 'iam'");
        return switch (args.resource()) {
            case "user" -> user(args, actor, correlation, context);
            case "group" -> group(args, actor, correlation, context);
            case "role" -> role(args, actor, correlation, context);
            case "permission" -> permission(args, actor, correlation, context);
            default -> throw new IllegalArgumentException("unknown IAM resource: " + args.resource());
        };
    }

    private String user(Arguments args, DomainIdentifier actor, DomainIdentifier correlation, IdentityAccessCommandContext context) {
        return switch (args.operation()) {
            case "list" -> {
                require(args, actor, PermissionCodes.USER_SEARCH, AuthorizationScope.platform(), correlation, "user", "collection");
                DomainIdentifier org = args.optionalId("org");
                String requestedStatus = args.optional("status", "").trim();
                IdentityUserStatus status = requestedStatus.isEmpty() ? null : IdentityUserStatus.valueOf(requestedStatus.toUpperCase(Locale.ROOT));
                String filter = args.optional("filter", "").toLowerCase(Locale.ROOT);
                List<IdentityUser> users = administration.listUsers(status, args.offset(), args.limit()).stream()
                        .filter(user -> filter.isEmpty() || user.login().contains(filter) || user.displayName().toLowerCase(Locale.ROOT).contains(filter))
                        .filter(user -> org == null || administration.memberships(user.id()).stream().anyMatch(m -> m.organizationId().equals(org)))
                        .toList();
                yield renderList(args, users.stream().map(IdentityAccessCli::userMap).toList());
            }
            case "create" -> {
                require(args, actor, PermissionCodes.USER_CREATE, AuthorizationScope.platform(), correlation, "user", "collection");
                DomainIdentifier org = args.requiredId("org");
                boolean invite = args.flag("invite");
                if (args.flag("dry-run")) yield dryRun(args, "create user " + args.required("login") + " in organization " + org);
                IdentityUser created = administration.createUser(
                        args.required("login"), args.required("email"), args.required("name"), !invite, context);
                try {
                    administration.addMembership(created.id(), org, args.optionalId("subdivision"), null, null, context);
                    for (String roleId : args.csv("roles")) {
                        Role role = administration.getRole(id(roleId));
                        AuthorizationScope scope = role.scopeKind() == ScopeKind.SUBDIVISION
                                ? AuthorizationScope.subdivision(org, args.requiredId("subdivision"))
                                : AuthorizationScope.organization(org);
                        administration.assignRole(role.id(), AssignmentActorType.USER, created.id(), scope, null, null, context);
                    }
                } catch (RuntimeException failure) {
                    administration.deleteUser(created.id(), context);
                    throw failure;
                }
                yield render(args, userMap(created));
            }
            case "update" -> {
                DomainIdentifier userId = args.requiredId("id");
                require(args, actor, PermissionCodes.USER_UPDATE, AuthorizationScope.platform(), correlation, "user", userId.toString());
                IdentityUser current = administration.getUser(userId);
                if (args.flag("dry-run")) yield dryRun(args, "update user " + userId);
                yield render(args, userMap(administration.updateUser(userId,
                        args.optional("email", current.email()), args.optional("name", current.displayName()), context)));
            }
            case "suspend" -> {
                DomainIdentifier userId = args.requiredId("id");
                require(args, actor, PermissionCodes.USER_SUSPEND, AuthorizationScope.platform(), correlation, "user", userId.toString());
                confirm(args, "suspend user " + userId);
                if (args.flag("dry-run")) yield dryRun(args, "suspend user " + userId);
                yield render(args, userMap(administration.suspendUser(userId, context)));
            }
            case "activate" -> {
                DomainIdentifier userId = args.requiredId("id");
                require(args, actor, PermissionCodes.USER_ACTIVATE, AuthorizationScope.platform(), correlation, "user", userId.toString());
                if (args.flag("dry-run")) yield dryRun(args, "activate user " + userId);
                yield render(args, userMap(administration.activateUser(userId, context)));
            }
            case "delete" -> {
                DomainIdentifier userId = args.requiredId("id");
                require(args, actor, PermissionCodes.USER_DELETE, AuthorizationScope.platform(), correlation, "user", userId.toString());
                confirm(args, "delete user " + userId);
                if (args.flag("dry-run")) yield dryRun(args, "soft-delete user " + userId);
                yield render(args, userMap(administration.deleteUser(userId, context)));
            }
            case "add-membership" -> {
                DomainIdentifier userId = args.requiredId("id");
                require(args, actor, PermissionCodes.USER_MANAGE_MEMBERSHIP, AuthorizationScope.platform(), correlation, "user", userId.toString());
                DomainIdentifier org = args.requiredId("org");
                if (args.flag("dry-run")) yield dryRun(args, "add membership for user " + userId + " in organization " + org);
                UserMembership membership = administration.addMembership(userId, org, args.optionalId("subdivision"),
                        args.optionalInstant("effective-from"), args.optionalInstant("effective-to"), context);
                yield render(args, membershipMap(membership));
            }
            default -> throw new IllegalArgumentException("unknown user operation: " + args.operation());
        };
    }

    private String group(Arguments args, DomainIdentifier actor, DomainIdentifier correlation, IdentityAccessCommandContext context) {
        if (args.has("type") && !"static".equalsIgnoreCase(args.required("type"))) {
            throw new IllegalArgumentException("dynamic groups are outside PGM-03-E03; only --type static is accepted");
        }
        return switch (args.operation()) {
            case "list" -> {
                DomainIdentifier org = args.requiredId("org");
                require(args, actor, PermissionCodes.GROUP_SEARCH, AuthorizationScope.organization(org), correlation, "group", "collection");
                String filter = args.optional("filter", "").toLowerCase(Locale.ROOT);
                var groups = administration.listGroups(org, args.offset(), args.limit()).stream()
                        .filter(group -> filter.isEmpty() || group.code().contains(filter) || group.displayName().toLowerCase(Locale.ROOT).contains(filter))
                        .map(IdentityAccessCli::groupMap).toList();
                yield renderList(args, groups);
            }
            case "create" -> {
                DomainIdentifier org = args.requiredId("org");
                require(args, actor, PermissionCodes.GROUP_CREATE, AuthorizationScope.organization(org), correlation, "group", "collection");
                if (args.flag("dry-run")) yield dryRun(args, "create group " + args.required("code") + " in organization " + org);
                yield render(args, groupMap(administration.createGroup(org, args.required("code"), args.required("name"), context)));
            }
            case "update" -> {
                IdentityGroup group = administration.getGroup(args.requiredId("id"));
                require(args, actor, PermissionCodes.GROUP_UPDATE, AuthorizationScope.organization(group.organizationId()), correlation, "group", group.id().toString());
                if (args.flag("dry-run")) yield dryRun(args, "update group " + group.id());
                yield render(args, groupMap(administration.updateGroup(group.organizationId(), group.id(), args.optional("name", group.displayName()), context)));
            }
            case "delete" -> {
                IdentityGroup group = administration.getGroup(args.requiredId("id"));
                require(args, actor, PermissionCodes.GROUP_DELETE, AuthorizationScope.organization(group.organizationId()), correlation, "group", group.id().toString());
                confirm(args, "delete group " + group.id());
                if (args.flag("dry-run")) yield dryRun(args, "soft-delete group " + group.id());
                yield render(args, groupMap(administration.deleteGroup(group.organizationId(), group.id(), context)));
            }
            case "add-member", "remove-member" -> {
                IdentityGroup group = administration.getGroup(args.requiredId("id"));
                boolean add = args.operation().equals("add-member");
                require(args, actor, add ? PermissionCodes.GROUP_ADD_MEMBER : PermissionCodes.GROUP_REMOVE_MEMBER,
                        AuthorizationScope.organization(group.organizationId()), correlation, "group", group.id().toString());
                DomainIdentifier userId = args.requiredId("user");
                if (args.flag("dry-run")) yield dryRun(args, (add ? "add" : "remove") + " user " + userId + " in group " + group.id());
                if (add) administration.addUserToGroup(group.organizationId(), group.id(), userId, context);
                else administration.removeUserFromGroup(group.organizationId(), group.id(), userId, context);
                yield success(args, args.operation(), group.id().toString());
            }
            case "add-group", "remove-group" -> {
                IdentityGroup group = administration.getGroup(args.requiredId("id"));
                boolean add = args.operation().equals("add-group");
                require(args, actor, add ? PermissionCodes.GROUP_ADD_GROUP : PermissionCodes.GROUP_REMOVE_GROUP,
                        AuthorizationScope.organization(group.organizationId()), correlation, "group", group.id().toString());
                DomainIdentifier child = args.requiredId("child");
                if (args.flag("dry-run")) yield dryRun(args, (add ? "add" : "remove") + " child group " + child + " in group " + group.id());
                if (add) administration.addGroupToGroup(group.organizationId(), group.id(), child, context);
                else administration.removeGroupFromGroup(group.organizationId(), group.id(), child, context);
                yield success(args, args.operation(), group.id().toString());
            }
            case "assign-role" -> {
                IdentityGroup group = administration.getGroup(args.requiredId("id"));
                require(args, actor, PermissionCodes.GROUP_ASSIGN_ROLE, AuthorizationScope.organization(group.organizationId()), correlation, "group", group.id().toString());
                Role role = administration.getRole(args.requiredId("role"));
                AuthorizationScope scope = role.scopeKind() == ScopeKind.SUBDIVISION
                        ? AuthorizationScope.subdivision(group.organizationId(), args.requiredId("subdivision"))
                        : AuthorizationScope.organization(group.organizationId());
                if (args.flag("dry-run")) yield dryRun(args, "assign role " + role.id() + " to group " + group.id());
                yield render(args, assignmentMap(administration.assignRole(role.id(), AssignmentActorType.GROUP, group.id(), scope,
                        args.optionalInstant("effective-from"), args.optionalInstant("effective-to"), context)));
            }
            default -> throw new IllegalArgumentException("unknown group operation: " + args.operation());
        };
    }

    private String role(Arguments args, DomainIdentifier actor, DomainIdentifier correlation, IdentityAccessCommandContext context) {
        return switch (args.operation()) {
            case "list" -> {
                DomainIdentifier org = args.requiredId("org");
                require(args, actor, PermissionCodes.ROLE_SEARCH, AuthorizationScope.organization(org), correlation, "role", "collection");
                String filter = args.optional("filter", "").toLowerCase(Locale.ROOT);
                yield renderList(args, administration.listRoles(org, args.offset(), args.limit()).stream()
                        .filter(role -> filter.isEmpty() || role.code().contains(filter) || role.displayName().toLowerCase(Locale.ROOT).contains(filter))
                        .map(IdentityAccessCli::roleMap).toList());
            }
            case "show" -> {
                Role role = administration.getRole(args.requiredId("id"));
                AuthorizationScope scope = role.organizationId() == null ? AuthorizationScope.platform() : AuthorizationScope.organization(role.organizationId());
                require(args, actor, PermissionCodes.ROLE_READ, scope, correlation, "role", role.id().toString());
                yield render(args, roleMap(role));
            }
            case "create" -> {
                DomainIdentifier org = args.requiredId("org");
                require(args, actor, PermissionCodes.ROLE_CREATE, AuthorizationScope.organization(org), correlation, "role", "collection");
                if (args.has("condition")) throw new IllegalArgumentException("--condition belongs to PGM-03-E04 and is not accepted by the RBAC foundation");
                ScopeKind scopeKind = args.optionalScope("scope", ScopeKind.ORGANIZATION);
                if (scopeKind == ScopeKind.PLATFORM) throw new IllegalArgumentException("organization-created roles cannot use platform scope");
                if (args.flag("dry-run")) yield dryRun(args, "create role " + args.required("code") + " in organization " + org);
                yield render(args, roleMap(administration.createRole(org, args.required("code"), args.required("name"), scopeKind,
                        Set.copyOf(args.requiredCsv("permissions")), context)));
            }
            case "update" -> {
                Role current = administration.getRole(args.requiredId("id"));
                AuthorizationScope scope = current.organizationId() == null ? AuthorizationScope.platform() : AuthorizationScope.organization(current.organizationId());
                require(args, actor, PermissionCodes.ROLE_UPDATE, scope, correlation, "role", current.id().toString());
                if (args.has("scope") && args.optionalScope("scope", current.scopeKind()) != current.scopeKind()) {
                    throw new IllegalArgumentException("role scope changes require replacement in PGM-03-E03 to preserve active assignment semantics");
                }
                Set<String> permissions = args.has("permissions") ? Set.copyOf(args.requiredCsv("permissions")) : administration.rolePermissionCodes(current.id());
                if (args.flag("dry-run")) yield dryRun(args, "update role " + current.id());
                yield render(args, roleMap(administration.updateRole(current.id(), args.optional("code", current.code()),
                        args.optional("name", current.displayName()), permissions, context)));
            }
            case "delete" -> {
                Role current = administration.getRole(args.requiredId("id"));
                AuthorizationScope scope = current.organizationId() == null ? AuthorizationScope.platform() : AuthorizationScope.organization(current.organizationId());
                require(args, actor, PermissionCodes.ROLE_DELETE, scope, correlation, "role", current.id().toString());
                confirm(args, "delete role " + current.id());
                if (args.flag("dry-run")) yield dryRun(args, "soft-delete role " + current.id());
                yield render(args, roleMap(administration.deleteRole(current.id(), args.flag("force"), context)));
            }
            case "assign" -> {
                Role role = administration.getRole(args.requiredId("id"));
                AssignmentActorType actorType = actorType(args.required("actor-type"));
                DomainIdentifier targetActor = args.requiredId("actor");
                AuthorizationScope scope = assignmentScope(args, role);
                require(args, actor, PermissionCodes.ROLE_ASSIGN, scope.kind() == ScopeKind.PLATFORM ? AuthorizationScope.platform() : AuthorizationScope.organization(scope.organizationId()), correlation, "role", role.id().toString());
                if (args.flag("dry-run")) yield dryRun(args, "assign role " + role.id() + " to " + actorType + " " + targetActor);
                yield render(args, assignmentMap(administration.assignRole(role.id(), actorType, targetActor, scope,
                        args.optionalInstant("effective-from"), args.optionalInstant("effective-to"), context)));
            }
            case "revoke" -> {
                DomainIdentifier assignmentId = args.requiredId("assignment");
                RoleAssignment assignment = administration.getAssignment(assignmentId);
                Role role = administration.getRole(assignment.roleId());
                AuthorizationScope scope = role.organizationId() == null ? AuthorizationScope.platform() : AuthorizationScope.organization(role.organizationId());
                require(args, actor, PermissionCodes.ROLE_UNASSIGN, scope, correlation, "role", role.id().toString());
                confirm(args, "revoke assignment " + assignmentId);
                if (args.flag("dry-run")) yield dryRun(args, "revoke role assignment " + assignmentId);
                administration.revokeAssignment(role.id(), assignmentId, context);
                yield success(args, "revoke", assignmentId.toString());
            }
            default -> throw new IllegalArgumentException("unknown role operation: " + args.operation());
        };
    }

    private String permission(Arguments args, DomainIdentifier actor, DomainIdentifier correlation, IdentityAccessCommandContext context) {
        return switch (args.operation()) {
            case "list" -> {
                DomainIdentifier org = args.requiredId("org");
                require(args, actor, PermissionCodes.PERMISSION_SEARCH, AuthorizationScope.organization(org), correlation, "permission", "collection");
                String filter = args.optional("filter", "").toLowerCase(Locale.ROOT);
                yield renderList(args, administration.listPermissions(org, args.offset(), args.limit()).stream()
                        .filter(permission -> filter.isEmpty() || permission.code().contains(filter)
                                || permission.resourceType().contains(filter) || permission.action().contains(filter))
                        .map(IdentityAccessCli::permissionMap).toList());
            }
            case "describe" -> {
                Permission permission = administration.getPermission(args.requiredId("id"));
                AuthorizationScope scope = permission.organizationId() == null ? AuthorizationScope.platform() : AuthorizationScope.organization(permission.organizationId());
                require(args, actor, PermissionCodes.PERMISSION_READ, scope, correlation, "permission", permission.id().toString());
                yield render(args, permissionMap(permission));
            }
            case "create" -> {
                DomainIdentifier org = args.requiredId("org");
                require(args, actor, PermissionCodes.PERMISSION_CREATE, AuthorizationScope.organization(org), correlation, "permission", "collection");
                ScopeKind scope = args.optionalScope("scope", ScopeKind.ORGANIZATION);
                if (scope == ScopeKind.PLATFORM) throw new IllegalArgumentException("organization-created permissions cannot use platform scope");
                if (args.flag("dry-run")) yield dryRun(args, "create permission " + args.required("code") + " in organization " + org);
                yield render(args, permissionMap(administration.createPermission(org, args.required("code"), args.required("resource"),
                        args.required("action"), args.optional("sensitivity", "normal"), scope, context)));
            }
            case "update" -> {
                Permission current = administration.getPermission(args.requiredId("id"));
                AuthorizationScope authScope = current.organizationId() == null ? AuthorizationScope.platform() : AuthorizationScope.organization(current.organizationId());
                require(args, actor, PermissionCodes.PERMISSION_UPDATE, authScope, correlation, "permission", current.id().toString());
                boolean active = !"inactive".equalsIgnoreCase(args.optional("status", current.active() ? "active" : "inactive"));
                if (args.flag("dry-run")) yield dryRun(args, "update permission " + current.id());
                yield render(args, permissionMap(administration.updatePermission(current.id(), args.optional("resource", current.resourceType()),
                        args.optional("action", current.action()), args.optional("sensitivity", current.sensitivity()),
                        args.optionalScope("scope", current.scopeKind()), active, context)));
            }
            case "delete" -> {
                Permission current = administration.getPermission(args.requiredId("id"));
                AuthorizationScope scope = current.organizationId() == null ? AuthorizationScope.platform() : AuthorizationScope.organization(current.organizationId());
                require(args, actor, PermissionCodes.PERMISSION_DELETE, scope, correlation, "permission", current.id().toString());
                confirm(args, "delete permission " + current.id());
                if (args.flag("dry-run")) yield dryRun(args, "soft-delete permission " + current.id());
                yield render(args, permissionMap(administration.deletePermission(current.id(), context)));
            }
            case "evaluate" -> {
                DomainIdentifier org = args.requiredId("org");
                AuthorizationScope scope = args.has("subdivision")
                        ? AuthorizationScope.subdivision(org, args.requiredId("subdivision"))
                        : AuthorizationScope.organization(org);
                require(args, actor, PermissionCodes.PERMISSION_EVALUATE, AuthorizationScope.organization(org), correlation, "permission", "evaluation");
                DomainIdentifier evaluated = args.requiredId("actor");
                if (args.has("permission")) {
                    AuthorizationDecision decision = authorization.evaluatePermission(evaluated, args.required("permission"), scope,
                            actor, correlation, "CLI");
                    yield render(args, Map.of("actorId", evaluated.toString(), "permissionCode", args.required("permission"),
                            "allowed", decision.allowed(), "decisionCode", decision.code(), "explanation", decision.explanation()));
                }
                Set<String> permissions = authorization.effectivePermissions(evaluated, scope, actor, correlation, "CLI");
                yield render(args, Map.of("actorId", evaluated.toString(), "permissionCodes", new TreeSet<>(permissions)));
            }
            default -> throw new IllegalArgumentException("unknown permission operation: " + args.operation());
        };
    }

    private AuthorizationScope assignmentScope(Arguments args, Role role) {
        if (role.scopeKind() == ScopeKind.PLATFORM) return AuthorizationScope.platform();
        DomainIdentifier org = args.requiredId("org");
        if (role.scopeKind() == ScopeKind.SUBDIVISION) return AuthorizationScope.subdivision(org, args.requiredId("subdivision"));
        return AuthorizationScope.organization(org);
    }

    private void require(Arguments args, DomainIdentifier actor, String permission, AuthorizationScope scope,
            DomainIdentifier correlation, String targetType, String targetId) {
        AuthorizationDecision decision = authorization.decide(actor, permission, scope, correlation, targetType, targetId, "CLI");
        if (!decision.allowed()) throw new CliAuthorizationException(decision.explanation());
        if (!features.supportsAdvancedAuthorization()) return;
        boolean justificationPresent = args.has("reason") && validJustification(args.required("reason"));
        String capabilityVersion = capabilities.snapshot().catalogVersion() + ":" + capabilities.snapshot().profileVersion();
        PolicyEvaluationRequest request = new PolicyEvaluationRequest(actor, permission, targetType, targetId, scope,
                Map.of("channel", "CLI", "justification_present", Boolean.toString(justificationPresent)),
                "LOCAL_SESSION", capabilityVersion, null, true);
        var advanced = policyDecisions.decide(request, correlation, "CLI");
        if (!advanced.permitted()) throw new CliAuthorizationException(advanced.reasonCode());
        for (PolicyObligation obligation : advanced.obligations()) {
            if (obligation == PolicyObligation.REQUIRE_JUSTIFICATION && justificationPresent) continue;
            throw new CliAuthorizationException("required authorization obligation is not satisfied: " + obligation.name());
        }
    }

    private static boolean validJustification(String value) {
        String normalized = value.strip();
        return normalized.length() >= 8 && normalized.length() <= 500
                && normalized.chars().noneMatch(Character::isISOControl);
    }

    private static void confirm(Arguments args, String description) {
        if (!args.flag("confirm") && !args.flag("dry-run")) {
            throw new IllegalArgumentException(description + " requires --confirm (or use --dry-run)");
        }
    }

    private static AssignmentActorType actorType(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "user" -> AssignmentActorType.USER;
            case "group" -> AssignmentActorType.GROUP;
            case "service_account", "service-account" -> throw new IllegalArgumentException("service accounts are outside PGM-03-E03");
            default -> throw new IllegalArgumentException("--actor-type must be user or group");
        };
    }

    private static DomainIdentifier id(String value) { return DomainIdentifier.parse(value); }

    private static char[] readSecret(String pathValue) {
        Path path = Path.of(pathValue);
        if (!path.isAbsolute()) throw new IllegalArgumentException("--password-file must be an absolute path");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new IllegalArgumentException("--password-file is unreadable", failure);
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            while (decoded.hasRemaining() && Character.isWhitespace(decoded.get(decoded.limit() - 1))) decoded.limit(decoded.limit() - 1);
            if (!decoded.hasRemaining()) throw new IllegalArgumentException("--password-file is empty");
            char[] secret = new char[decoded.remaining()];
            decoded.get(secret);
            return secret;
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("--password-file must contain valid UTF-8", failure);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String render(Arguments args, Map<String, ?> value) {
        return args.json() ? Json.write(value) : text(value);
    }

    private static String renderList(Arguments args, List<Map<String, ?>> values) {
        if (args.json()) return Json.write(values);
        if (values.isEmpty()) return "no results";
        StringBuilder output = new StringBuilder();
        for (Map<String, ?> value : values) output.append(text(value)).append(System.lineSeparator());
        return output.toString().stripTrailing();
    }

    private static String dryRun(Arguments args, String description) {
        return render(args, Map.of("dryRun", true, "operation", description));
    }

    private static String success(Arguments args, String operation, String id) {
        return render(args, Map.of("status", "success", "operation", operation, "id", id));
    }

    private static String text(Map<String, ?> value) {
        StringBuilder output = new StringBuilder();
        for (Map.Entry<String, ?> entry : value.entrySet()) {
            if (output.length() > 0) output.append(' ');
            output.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return output.toString();
    }

    private static Map<String, ?> userMap(IdentityUser user) {
        return ordered("id", user.id().toString(), "login", user.login(), "email", user.email(), "name", user.displayName(), "status", user.status().name());
    }
    private static Map<String, ?> membershipMap(UserMembership membership) {
        return ordered("id", membership.id().toString(), "userId", membership.userId().toString(), "organizationId", membership.organizationId().toString(),
                "subdivisionId", nullable(membership.subdivisionId()), "effectiveFrom", membership.effectiveFrom().toString(), "effectiveTo", nullable(membership.effectiveTo()));
    }
    private static Map<String, ?> groupMap(IdentityGroup group) {
        return ordered("id", group.id().toString(), "organizationId", group.organizationId().toString(), "code", group.code(), "name", group.displayName(), "deleted", group.deleted());
    }
    private static Map<String, ?> roleMap(Role role) {
        return ordered("id", role.id().toString(), "organizationId", nullable(role.organizationId()), "code", role.code(), "name", role.displayName(),
                "scope", role.scopeKind().name(), "systemRole", role.systemRole(), "active", role.active(), "deleted", role.deleted());
    }
    private static Map<String, ?> permissionMap(Permission permission) {
        return ordered("id", permission.id().toString(), "organizationId", nullable(permission.organizationId()), "code", permission.code(),
                "resource", permission.resourceType(), "action", permission.action(), "sensitivity", permission.sensitivity(), "scope", permission.scopeKind().name(),
                "systemDefined", permission.systemDefined(), "active", permission.active(), "deleted", permission.deleted());
    }
    private static Map<String, ?> assignmentMap(RoleAssignment assignment) {
        return ordered("id", assignment.id().toString(), "roleId", assignment.roleId().toString(), "actorType", assignment.actorType().name(),
                "actorId", assignment.actorId().toString(), "scope", assignment.scope().kind().name(), "organizationId", nullable(assignment.scope().organizationId()),
                "subdivisionId", nullable(assignment.scope().subdivisionId()), "effectiveFrom", assignment.effectiveFrom().toString(), "effectiveTo", nullable(assignment.effectiveTo()));
    }

    private static Object nullable(Object value) { return value == null ? null : value.toString(); }

    private static Map<String, ?> ordered(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private static boolean has(String[] args, String value) {
        for (String arg : args) if (value.equals(arg)) return true;
        return false;
    }

    private static String safe(String value) { return value == null || value.isBlank() ? "invalid command" : value; }

    public static String help() {
        return """
                InfraNexum IAM CLI — PGM-03-E03

                Usage:
                  infranexum iam <user|group|role|permission> <operation> [options]
                    --username <local-login> --password-file </absolute/secret/path>
                    [--format text|json] [--reason <audit-reason>] [--dry-run]

                User:       create update suspend activate delete add-membership list
                Group:      create update delete add-member remove-member add-group remove-group assign-role list
                Role:       create update delete list show assign revoke
                Permission: create update delete list describe evaluate

                Safety:
                  delete, suspend and revoke operations require --confirm unless --dry-run is used.
                  passwords are accepted only through --password-file and are never accepted in argv.
                """;
    }

    private static final class CliAuthorizationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        CliAuthorizationException(String message) { super(message); }
    }

    /** Strict option parser; unknown positional arguments and duplicate options are rejected. */
    static final class Arguments {
        private static final Set<String> FLAGS = Set.of("invite", "dry-run", "confirm", "force", "soft");
        private final String namespace;
        private final String resource;
        private final String operation;
        private final Map<String, String> options;

        private Arguments(String namespace, String resource, String operation, Map<String, String> options) {
            this.namespace = namespace;
            this.resource = resource;
            this.operation = operation;
            this.options = Map.copyOf(options);
        }

        static Arguments parse(String[] raw) {
            if (raw.length < 3) throw new IllegalArgumentException("expected: iam <resource> <operation>");
            if (raw[0].startsWith("-") || raw[1].startsWith("-") || raw[2].startsWith("-")) throw new IllegalArgumentException("command path is incomplete");
            Map<String, String> options = new LinkedHashMap<>();
            for (int i = 3; i < raw.length; i++) {
                String token = raw[i];
                if (!token.startsWith("--") || token.length() < 3) throw new IllegalArgumentException("unexpected positional argument: " + token);
                String name = token.substring(2);
                if (options.containsKey(name)) throw new IllegalArgumentException("duplicate option --" + name);
                if (FLAGS.contains(name)) {
                    options.put(name, "true");
                } else {
                    if (++i >= raw.length || raw[i].startsWith("--")) throw new IllegalArgumentException("option --" + name + " requires a value");
                    options.put(name, raw[i]);
                }
            }
            return new Arguments(raw[0].toLowerCase(Locale.ROOT), raw[1].toLowerCase(Locale.ROOT), raw[2].toLowerCase(Locale.ROOT), options);
        }

        String namespace() { return namespace; }
        String resource() { return resource; }
        String operation() { return operation; }
        boolean has(String name) { return options.containsKey(name); }
        boolean flag(String name) { return "true".equals(options.get(name)); }
        String required(String name) {
            String value = options.get(name);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("missing --" + name);
            return value;
        }
        String optional(String name, String fallback) {
            String value = options.get(name);
            return value == null || value.isBlank() ? fallback : value;
        }
        DomainIdentifier requiredId(String name) { return id(required(name)); }
        DomainIdentifier optionalId(String name) { return has(name) ? requiredId(name) : null; }
        Instant optionalInstant(String name) { return has(name) ? Instant.parse(required(name)) : null; }
        List<String> csv(String name) { return has(name) ? requiredCsv(name) : List.of(); }
        List<String> requiredCsv(String name) {
            List<String> result = Arrays.stream(required(name).split(",", -1)).map(String::strip).filter(value -> !value.isEmpty()).toList();
            if (result.isEmpty()) throw new IllegalArgumentException("--" + name + " must contain at least one value");
            return result;
        }
        ScopeKind optionalScope(String name, ScopeKind fallback) {
            if (!has(name)) return fallback;
            try { return ScopeKind.valueOf(required(name).toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("--" + name + " must be platform, organization or subdivision"); }
        }
        int offset() { return integer("offset", 0, 0, Integer.MAX_VALUE); }
        int limit() { return integer("limit", 50, 1, 200); }
        boolean json() {
            String format = optional("format", "text").toLowerCase(Locale.ROOT);
            if (!format.equals("text") && !format.equals("json")) throw new IllegalArgumentException("--format must be text or json");
            return format.equals("json");
        }
        private int integer(String name, int fallback, int minimum, int maximum) {
            if (!has(name)) return fallback;
            try {
                int value = Integer.parseInt(required(name));
                if (value < minimum || value > maximum) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("--" + name + " is outside the supported range");
            }
        }
    }

    /** Minimal deterministic JSON encoder for CLI output; it never deserializes untrusted input. */
    static final class Json {
        private Json() {}
        static String write(Object value) {
            if (value == null) return "null";
            if (value instanceof Boolean || value instanceof Number) return value.toString();
            if (value instanceof CharSequence) return '"' + escape(value.toString()) + '"';
            if (value instanceof Map<?, ?> map) {
                StringBuilder out = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    out.append('"').append(escape(String.valueOf(entry.getKey()))).append("\":").append(write(entry.getValue()));
                }
                return out.append('}').toString();
            }
            if (value instanceof Iterable<?> iterable) {
                StringBuilder out = new StringBuilder("[");
                boolean first = true;
                for (Object element : iterable) {
                    if (!first) out.append(',');
                    first = false;
                    out.append(write(element));
                }
                return out.append(']').toString();
            }
            return write(value.toString());
        }
        private static String escape(String value) {
            StringBuilder out = new StringBuilder(value.length() + 16);
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        else out.append(c);
                    }
                }
            }
            return out.toString();
        }
    }
}
