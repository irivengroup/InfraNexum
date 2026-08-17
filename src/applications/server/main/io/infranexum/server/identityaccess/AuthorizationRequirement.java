package io.infranexum.server.identityaccess;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.PermissionCodes;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic registry mapping every currently exposed v1 API route to one RBAC policy. */
record AuthorizationRequirement(
        Type type,
        String permissionCode,
        AuthorizationScope scope,
        String targetType,
        String targetId) {
    enum Type { PERMISSION, GROUP_PERMISSION, ORGANIZATION_VISIBILITY, PLATFORM_ADMINISTRATOR, CONTROLLER_SCOPED, UNREGISTERED }

    private static final Pattern IAM_ORG = Pattern.compile("^/api/v1/iam/organizations/([^/]+)(.*)$");
    private static final Pattern POLICY_LIFECYCLE = Pattern.compile("^/api/v1/iam/policies/([^/]+)/(validate|approve|activate)$");
    private static final Pattern USER = Pattern.compile("^/api/v1/iam/users(?:/([^/]+))?(.*)$");
    private static final Pattern EFFECTIVE_GROUP = Pattern.compile("^/api/v1/iam/groups/([^/]+)/effective-members$");
    private static final Pattern ORG_RESOURCE = Pattern.compile("^/api/v1/organizations/([^/]+)/(groups|roles|permissions)(.*)$");
    private static final Pattern RESOURCE_DETAIL = Pattern.compile("^/([^/]+)$");
    private static final Pattern GROUP_MEMBERS = Pattern.compile("^/([^/]+)/members$");
    private static final Pattern GROUP_MEMBER = Pattern.compile("^/([^/]+)/members/([^/]+)$");
    private static final Pattern GROUP_ROLES = Pattern.compile("^/([^/]+)/roles$");
    private static final Pattern ROLE_ASSIGNMENTS = Pattern.compile("^/([^/]+)/assignments$");
    private static final Pattern ROLE_ASSIGNMENT = Pattern.compile("^/([^/]+)/assignments/([^/]+)$");

    AuthorizationRequirement {
        Objects.requireNonNull(type, "type"); Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(targetType, "targetType"); Objects.requireNonNull(targetId, "targetId");
        if (type == Type.PERMISSION && (permissionCode == null || permissionCode.isBlank())) {
            throw new IllegalArgumentException("permission requirement needs a permission code");
        }
    }

    static AuthorizationRequirement resolve(String method, String path) {
        Objects.requireNonNull(method, "method"); Objects.requireNonNull(path, "path");
        String verb=method.toUpperCase(java.util.Locale.ROOT);
        String normalized=path.length()>1 && path.endsWith("/") ? path.substring(0,path.length()-1) : path;

        if (normalized.equals("/api/v1/platform/capabilities") || normalized.startsWith("/api/v1/platform/capabilities/")) {
            return permission(PermissionCodes.PLATFORM_CAPABILITY_READ, AuthorizationScope.platform(), "platform", "capabilities");
        }
        if (normalized.equals("/api/v1/platform/quotas")) {
            return permission(PermissionCodes.PLATFORM_CAPABILITY_READ, AuthorizationScope.platform(), "platform", "quotas");
        }

        if (normalized.equals("/api/v1/integrations/providers/jira-assets") && verb.equals("GET")) {
            return permission(PermissionCodes.INTEGRATIONS_CONNECTOR_READ, AuthorizationScope.platform(), "integration-provider", "jira-assets");
        }
        if (normalized.matches("^/api/v1/integrations/providers/jira-assets/[^/]+/(health|objects/search)$")) {
            boolean supported = (normalized.endsWith("/health") && verb.equals("GET"))
                    || (normalized.endsWith("/objects/search") && verb.equals("POST"));
            if (!supported) return unregistered(normalized);
            String connectorKey = new io.infranexum.integrations.ConnectorKey(normalized.split("/")[6]).value();
            return permission(PermissionCodes.INTEGRATIONS_CONNECTOR_READ, AuthorizationScope.platform(), "integration-connector", connectorKey);
        }

        if (normalized.equals("/api/v1/integrations/providers/service-now") && verb.equals("GET")) {
            return permission(PermissionCodes.INTEGRATIONS_CONNECTOR_READ, AuthorizationScope.platform(), "integration-provider", "service-now");
        }
        if (normalized.matches("^/api/v1/integrations/providers/service-now/[^/]+/(health|configuration-items/search)$")) {
            boolean supported = (normalized.endsWith("/health") && verb.equals("GET"))
                    || (normalized.endsWith("/configuration-items/search") && verb.equals("POST"));
            if (!supported) return unregistered(normalized);
            String connectorKey = new io.infranexum.integrations.ConnectorKey(normalized.split("/")[6]).value();
            return permission(PermissionCodes.INTEGRATIONS_CONNECTOR_READ, AuthorizationScope.platform(), "integration-connector", connectorKey);
        }

        if (normalized.equals("/api/v1/integrations/dlq") && verb.equals("GET")) {
            return permission(PermissionCodes.INTEGRATIONS_DLQ_READ, AuthorizationScope.platform(), "integration-dlq", "collection");
        }
        if (normalized.matches("^/api/v1/integrations/dlq/[^/]+/replay$") && verb.equals("POST")) {
            String deliveryId = DomainIdentifier.parse(normalized.split("/")[5]).toString();
            return permission(PermissionCodes.INTEGRATIONS_DLQ_REPLAY, AuthorizationScope.platform(), "integration-delivery", deliveryId);
        }
        if (normalized.matches("^/api/v1/integrations/connectors/[^/]+/runtime$") && verb.equals("GET")) {
            String connectorKey = new io.infranexum.integrations.ConnectorKey(normalized.split("/")[5]).value();
            return permission(PermissionCodes.INTEGRATIONS_CONNECTOR_READ, AuthorizationScope.platform(), "integration-connector", connectorKey);
        }
        if (normalized.matches("^/api/v1/integrations/connectors/[^/]+/resume$") && verb.equals("POST")) {
            String connectorKey = new io.infranexum.integrations.ConnectorKey(normalized.split("/")[5]).value();
            return permission(PermissionCodes.INTEGRATIONS_CONNECTOR_RESUME, AuthorizationScope.platform(), "integration-connector", connectorKey);
        }
        if (normalized.equals("/api/v1/platform/evaluation/status")) {
            return permission(PermissionCodes.PLATFORM_PROFILE_READ, AuthorizationScope.platform(), "platform", "evaluation-status");
        }

        if (normalized.equals("/api/v1/rsot/canonical-objects") || normalized.startsWith("/api/v1/rsot/canonical-objects/")) {
            if (verb.equals("GET")) return controllerScoped("rsot-object", normalized);
            return unregistered(normalized);
        }

        if (normalized.equals("/api/v1/rsot/schemas")) {
            if (verb.equals("GET")) return permission(PermissionCodes.RSOT_SCHEMA_READ, AuthorizationScope.platform(), "rsot-schema", "collection");
            if (verb.equals("POST")) return permission(PermissionCodes.RSOT_SCHEMA_CREATE, AuthorizationScope.platform(), "rsot-schema", "collection");
            return unregistered(normalized);
        }
        if (normalized.startsWith("/api/v1/rsot/schemas/")) {
            return rsotSchemaRequirement(verb, normalized.substring("/api/v1/rsot/schemas/".length()), false, normalized);
        }
        if (normalized.equals("/api/v1/rsot/schema-profiles")) {
            if (verb.equals("GET")) return permission(PermissionCodes.RSOT_SCHEMA_READ, AuthorizationScope.platform(), "rsot-schema-profile", "collection");
            if (verb.equals("POST")) return permission(PermissionCodes.RSOT_SCHEMA_CREATE, AuthorizationScope.platform(), "rsot-schema-profile", "collection");
            return unregistered(normalized);
        }
        if (normalized.startsWith("/api/v1/rsot/schema-profiles/")) {
            return rsotSchemaRequirement(verb, normalized.substring("/api/v1/rsot/schema-profiles/".length()), true, normalized);
        }

        if (normalized.equals("/api/v1/ddi/ipam/vrfs") || normalized.startsWith("/api/v1/ddi/ipam/vrfs/")
                || normalized.equals("/api/v1/ddi/ipam/vlans") || normalized.startsWith("/api/v1/ddi/ipam/vlans/")
                || normalized.equals("/api/v1/ddi/ipam/networks") || normalized.startsWith("/api/v1/ddi/ipam/networks/")
                || normalized.equals("/api/v1/ddi/ipam/pools") || normalized.startsWith("/api/v1/ddi/ipam/pools/")
                || normalized.equals("/api/v1/ddi/ipam/addresses") || normalized.startsWith("/api/v1/ddi/ipam/addresses/")) {
            return controllerScoped("ddi-ipam", normalized);
        }

        if (normalized.equals("/api/v1/dcim/sites") || normalized.startsWith("/api/v1/dcim/sites/")
                || normalized.equals("/api/v1/dcim/buildings") || normalized.startsWith("/api/v1/dcim/buildings/")
                || normalized.equals("/api/v1/dcim/floors") || normalized.startsWith("/api/v1/dcim/floors/")
                || normalized.equals("/api/v1/dcim/rooms") || normalized.startsWith("/api/v1/dcim/rooms/")
                || normalized.equals("/api/v1/dcim/zones") || normalized.startsWith("/api/v1/dcim/zones/")) {
            return controllerScoped("dcim-facility", normalized);
        }

        if (normalized.equals("/api/v1/itam/partners") || normalized.startsWith("/api/v1/itam/partners/")) {
            return controllerScoped("itam-partner", normalized);
        }
        if (normalized.equals("/api/v1/itam/assets") || normalized.startsWith("/api/v1/itam/assets/")) {
            return controllerScoped("itam-asset", normalized);
        }
        if (normalized.equals("/api/v1/itam/warranty-types")
                || normalized.startsWith("/api/v1/itam/warranties/")
                || normalized.startsWith("/api/v1/itam/licenses/")
                || normalized.startsWith("/api/v1/itam/support-coverages/")
                || normalized.equals("/api/v1/itam/support-authorizations")
                || normalized.startsWith("/api/v1/itam/support-authorizations/")) {
            return controllerScoped("itam-compliance", normalized);
        }

        if (normalized.equals("/api/v1/iam/policies") && (verb.equals("GET") || verb.equals("POST"))) {
            return platformAdmin("policy", "collection");
        }
        Matcher policyLifecycle = POLICY_LIFECYCLE.matcher(normalized);
        if (policyLifecycle.matches() && verb.equals("POST")) {
            String policyId = DomainIdentifier.parse(policyLifecycle.group(1)).toString();
            return platformAdmin("policy", policyId);
        }
        if ((normalized.equals("/api/v1/iam/authorization/decisions")
                || normalized.equals("/api/v1/iam/authorization/explain")) && verb.equals("POST")) {
            return platformAdmin("authorization", normalized.endsWith("/explain") ? "explain" : "decisions");
        }

        if (normalized.equals("/api/v1/iam/organizations")) {
            if (verb.equals("POST")) return permission(PermissionCodes.ORGANIZATION_CREATE, AuthorizationScope.platform(), "organization", "collection");
            if (verb.equals("GET")) return platformAdmin("organization", "collection");
            return unregistered(normalized);
        }
        Matcher legacyOrg=IAM_ORG.matcher(normalized);
        if (legacyOrg.matches()) {
            DomainIdentifier org=DomainIdentifier.parse(legacyOrg.group(1)); String tail=legacyOrg.group(2);
            AuthorizationScope orgScope=AuthorizationScope.organization(org);
            if (tail.isEmpty() && verb.equals("GET")) return visibility(org);
            if ((tail.equals("/suspend") || tail.equals("/resume")) && verb.equals("POST")) return permission(PermissionCodes.ORGANIZATION_SUSPEND,orgScope,"organization",org.toString());
            if (tail.equals("/subdivisions") && verb.equals("POST")) return permission(PermissionCodes.SUBDIVISION_CREATE,orgScope,"organization",org.toString());
            if (tail.equals("/subdivisions") && verb.equals("GET")) return permission(PermissionCodes.SUBDIVISION_SEARCH,orgScope,"organization",org.toString());
            // Temporal scopes are an E01 compatibility surface without a normative atomic permission in draft.21.
            if (tail.equals("/scopes") && verb.equals("POST")) return platformAdmin("organization",org.toString());
            if (tail.equals("/scopes/effective") && verb.equals("GET")) return visibility(org);
            return unregistered(normalized);
        }

        Matcher effectiveGroup = EFFECTIVE_GROUP.matcher(normalized);
        if (effectiveGroup.matches() && verb.equals("GET")) {
            DomainIdentifier groupId = DomainIdentifier.parse(effectiveGroup.group(1));
            return groupPermission(PermissionCodes.GROUP_READ, groupId);
        }

        Matcher user=USER.matcher(normalized);
        if (user.matches()) {
            String userId=user.group(1); String tail=user.group(2); String target=userId==null?"collection":DomainIdentifier.parse(userId).toString();
            if (userId==null && tail.isEmpty()) {
                if (verb.equals("GET")) return permission(PermissionCodes.USER_SEARCH,AuthorizationScope.platform(),"user",target);
                if (verb.equals("POST")) return permission(PermissionCodes.USER_CREATE,AuthorizationScope.platform(),"user",target);
            } else if (tail.isEmpty()) {
                if (verb.equals("GET")) return permission(PermissionCodes.USER_READ,AuthorizationScope.platform(),"user",target);
                if (verb.equals("PATCH")) return permission(PermissionCodes.USER_UPDATE,AuthorizationScope.platform(),"user",target);
                if (verb.equals("DELETE")) return permission(PermissionCodes.USER_DELETE,AuthorizationScope.platform(),"user",target);
            } else if (tail.equals("/activate") && verb.equals("POST")) return permission(PermissionCodes.USER_ACTIVATE,AuthorizationScope.platform(),"user",target);
            else if (tail.equals("/suspend") && verb.equals("POST")) return permission(PermissionCodes.USER_SUSPEND,AuthorizationScope.platform(),"user",target);
            else if (tail.equals("/memberships") && verb.equals("GET")) return permission(PermissionCodes.USER_READ,AuthorizationScope.platform(),"user",target);
            else if (tail.equals("/memberships") && verb.equals("POST")) return permission(PermissionCodes.USER_MANAGE_MEMBERSHIP,AuthorizationScope.platform(),"user",target);
            else if (tail.equals("/roles") && verb.equals("POST")) return permission(PermissionCodes.USER_ASSIGN_ROLE,AuthorizationScope.platform(),"user",target);
            return unregistered(normalized);
        }

        Matcher orgResource=ORG_RESOURCE.matcher(normalized);
        if (orgResource.matches()) {
            DomainIdentifier org=DomainIdentifier.parse(orgResource.group(1)); String resource=orgResource.group(2); String tail=orgResource.group(3);
            AuthorizationScope scope=AuthorizationScope.organization(org);
            return switch(resource) {
                case "groups" -> groupRequirement(verb,tail,scope,normalized);
                case "roles" -> roleRequirement(verb,tail,scope,normalized);
                case "permissions" -> permissionRequirement(verb,tail,scope,normalized);
                default -> unregistered(normalized);
            };
        }
        return unregistered(normalized);
    }

    private static AuthorizationRequirement rsotSchemaRequirement(String verb, String tail, boolean profile, String target) {
        String[] parts = tail.split("/", -1);
        if (parts.length < 1 || parts[0].isBlank()) return unregistered(target);
        String id = DomainIdentifier.parse(parts[0]).toString();
        String type = profile ? "rsot-schema-profile" : "rsot-schema";
        if (parts.length == 1 && verb.equals("GET")) return permission(PermissionCodes.RSOT_SCHEMA_READ, AuthorizationScope.platform(), type, id);
        if (!profile && parts.length == 1 && verb.equals("PATCH")) return permission(PermissionCodes.RSOT_SCHEMA_UPDATE, AuthorizationScope.platform(), type, id);
        if (!profile && parts.length == 2 && parts[1].equals("compatibility") && verb.equals("GET")) return permission(PermissionCodes.RSOT_SCHEMA_READ, AuthorizationScope.platform(), type, id);
        if (parts.length == 2 && parts[1].equals("publish") && verb.equals("POST")) return permission(PermissionCodes.RSOT_SCHEMA_PUBLISH, AuthorizationScope.platform(), type, id);
        if (parts.length == 2 && parts[1].equals("deprecate") && verb.equals("POST")) return permission(PermissionCodes.RSOT_SCHEMA_DEPRECATE, AuthorizationScope.platform(), type, id);
        return unregistered(target);
    }

    private static AuthorizationRequirement groupRequirement(String verb,String tail,AuthorizationScope scope,String target){
        String organizationId=scope.organizationId().toString();
        if(tail.isEmpty()) return permission(verb.equals("POST")?PermissionCodes.GROUP_CREATE:verb.equals("GET")?PermissionCodes.GROUP_SEARCH:"",scope,"group",organizationId);
        Matcher detail=RESOURCE_DETAIL.matcher(tail);
        if(detail.matches()) {
            String groupId=DomainIdentifier.parse(detail.group(1)).toString();
            return permission(switch(verb){case "GET"->PermissionCodes.GROUP_READ;case "PATCH"->PermissionCodes.GROUP_UPDATE;case "DELETE"->PermissionCodes.GROUP_DELETE;default->"";},scope,"group",groupId);
        }
        Matcher members=GROUP_MEMBERS.matcher(tail);
        if(members.matches() && verb.equals("POST")) {
            String groupId=DomainIdentifier.parse(members.group(1)).toString();
            return permission(PermissionCodes.GROUP_ADD_MEMBER,scope,"group",groupId);
        }
        Matcher member=GROUP_MEMBER.matcher(tail);
        if(member.matches() && verb.equals("DELETE")) {
            String groupId=DomainIdentifier.parse(member.group(1)).toString();
            DomainIdentifier.parse(member.group(2));
            return permission(PermissionCodes.GROUP_REMOVE_MEMBER,scope,"group",groupId);
        }
        Matcher roles=GROUP_ROLES.matcher(tail);
        if(roles.matches() && verb.equals("POST")) {
            String groupId=DomainIdentifier.parse(roles.group(1)).toString();
            return permission(PermissionCodes.GROUP_ASSIGN_ROLE,scope,"group",groupId);
        }
        return unregistered(target);
    }

    private static AuthorizationRequirement roleRequirement(String verb,String tail,AuthorizationScope scope,String target){
        String organizationId=scope.organizationId().toString();
        if(tail.isEmpty()) return permission(verb.equals("POST")?PermissionCodes.ROLE_CREATE:verb.equals("GET")?PermissionCodes.ROLE_SEARCH:"",scope,"role",organizationId);
        Matcher detail=RESOURCE_DETAIL.matcher(tail);
        if(detail.matches()) {
            String roleId=DomainIdentifier.parse(detail.group(1)).toString();
            return permission(switch(verb){case "GET"->PermissionCodes.ROLE_READ;case "PATCH"->PermissionCodes.ROLE_UPDATE;case "DELETE"->PermissionCodes.ROLE_DELETE;default->"";},scope,"role",roleId);
        }
        Matcher assignments=ROLE_ASSIGNMENTS.matcher(tail);
        if(assignments.matches()) {
            String roleId=DomainIdentifier.parse(assignments.group(1)).toString();
            if(verb.equals("GET")) return permission(PermissionCodes.ROLE_READ,scope,"role",roleId);
            if(verb.equals("POST")) return permission(PermissionCodes.ROLE_ASSIGN,scope,"role",roleId);
        }
        Matcher assignment=ROLE_ASSIGNMENT.matcher(tail);
        if(assignment.matches() && verb.equals("DELETE")) {
            String roleId=DomainIdentifier.parse(assignment.group(1)).toString();
            DomainIdentifier.parse(assignment.group(2));
            return permission(PermissionCodes.ROLE_UNASSIGN,scope,"role",roleId);
        }
        return unregistered(target);
    }

    private static AuthorizationRequirement permissionRequirement(String verb,String tail,AuthorizationScope scope,String target){
        String organizationId=scope.organizationId().toString();
        if(tail.isEmpty()) return permission(verb.equals("POST")?PermissionCodes.PERMISSION_CREATE:verb.equals("GET")?PermissionCodes.PERMISSION_SEARCH:"",scope,"permission",organizationId);
        if(tail.equals("/effective") && verb.equals("GET")) return permission(PermissionCodes.PERMISSION_EVALUATE,scope,"organization",organizationId);
        if(tail.equals("/validate") && verb.equals("POST")) return permission(PermissionCodes.PERMISSION_EVALUATE,scope,"organization",organizationId);
        Matcher detail=RESOURCE_DETAIL.matcher(tail);
        if(detail.matches()) {
            String permissionId=DomainIdentifier.parse(detail.group(1)).toString();
            return permission(switch(verb){case "GET"->PermissionCodes.PERMISSION_READ;case "PATCH"->PermissionCodes.PERMISSION_UPDATE;case "DELETE"->PermissionCodes.PERMISSION_DELETE;default->"";},scope,"permission",permissionId);
        }
        return unregistered(target);
    }

    private static AuthorizationRequirement permission(String code,AuthorizationScope scope,String targetType,String targetId){
        if(code==null||code.isBlank()) return unregistered(targetId);
        return new AuthorizationRequirement(Type.PERMISSION,code,scope,targetType,targetId);
    }
    private static AuthorizationRequirement groupPermission(String code,DomainIdentifier groupId){return new AuthorizationRequirement(Type.GROUP_PERMISSION,code,AuthorizationScope.platform(),"group",groupId.toString());}
    private static AuthorizationRequirement visibility(DomainIdentifier org){return new AuthorizationRequirement(Type.ORGANIZATION_VISIBILITY,null,AuthorizationScope.organization(org),"organization",org.toString());}
    private static AuthorizationRequirement platformAdmin(String type,String id){return new AuthorizationRequirement(Type.PLATFORM_ADMINISTRATOR,null,AuthorizationScope.platform(),type,id);}
    private static AuthorizationRequirement controllerScoped(String type,String id){return new AuthorizationRequirement(Type.CONTROLLER_SCOPED,null,AuthorizationScope.platform(),type,id);}
    private static AuthorizationRequirement unregistered(String target){return new AuthorizationRequirement(Type.UNREGISTERED,null,AuthorizationScope.platform(),"api-route",target);}
}
