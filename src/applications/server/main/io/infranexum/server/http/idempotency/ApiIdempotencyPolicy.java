package io.infranexum.server.http.idempotency;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Route-level idempotency policy for the remaining PGM-05-E01 mutating operations. */
public final class ApiIdempotencyPolicy {
    private record Rule(String operation, String method, Pattern path) {
        boolean matches(HttpServletRequest request) {
            return method.equals(request.getMethod().toUpperCase(Locale.ROOT)) && path.matcher(request.getRequestURI()).matches();
        }
    }

    private static final String UUID = "[^/]+";
    private static final List<Rule> RULES = List.of(
        rule("createIamUser","POST","/api/v1/iam/users"),
        rule("updateIamUser","PATCH","/api/v1/iam/users/"+UUID),
        rule("deleteIamUser","DELETE","/api/v1/iam/users/"+UUID),
        rule("activateIamUser","POST","/api/v1/iam/users/"+UUID+"/activate"),
        rule("suspendIamUser","POST","/api/v1/iam/users/"+UUID+"/suspend"),
        rule("createIamUserMembership","POST","/api/v1/iam/users/"+UUID+"/memberships"),
        rule("assignIamUserRole","POST","/api/v1/iam/users/"+UUID+"/roles"),
        rule("createIamGroup","POST","/api/v1/organizations/"+UUID+"/groups"),
        rule("updateIamGroup","PATCH","/api/v1/organizations/"+UUID+"/groups/"+UUID),
        rule("deleteIamGroup","DELETE","/api/v1/organizations/"+UUID+"/groups/"+UUID),
        rule("addIamGroupMember","POST","/api/v1/organizations/"+UUID+"/groups/"+UUID+"/members"),
        rule("removeIamGroupMember","DELETE","/api/v1/organizations/"+UUID+"/groups/"+UUID+"/members/"+UUID),
        rule("assignIamGroupRole","POST","/api/v1/organizations/"+UUID+"/groups/"+UUID+"/roles"),
        rule("createIamRole","POST","/api/v1/organizations/"+UUID+"/roles"),
        rule("updateIamRole","PATCH","/api/v1/organizations/"+UUID+"/roles/"+UUID),
        rule("deleteIamRole","DELETE","/api/v1/organizations/"+UUID+"/roles/"+UUID),
        rule("createIamRoleAssignment","POST","/api/v1/organizations/"+UUID+"/roles/"+UUID+"/assignments"),
        rule("revokeIamRoleAssignment","DELETE","/api/v1/organizations/"+UUID+"/roles/"+UUID+"/assignments/"+UUID),
        rule("createIamPermission","POST","/api/v1/organizations/"+UUID+"/permissions"),
        rule("updateIamPermission","PATCH","/api/v1/organizations/"+UUID+"/permissions/"+UUID),
        rule("deleteIamPermission","DELETE","/api/v1/organizations/"+UUID+"/permissions/"+UUID),
        rule("createIamAccessPolicy","POST","/api/v1/iam/policies"),
        rule("validateIamAccessPolicy","POST","/api/v1/iam/policies/"+UUID+"/validate"),
        rule("approveIamAccessPolicy","POST","/api/v1/iam/policies/"+UUID+"/approve"),
        rule("activateIamAccessPolicy","POST","/api/v1/iam/policies/"+UUID+"/activate"),
        rule("createRsotSchema","POST","/api/v1/rsot/schemas"),
        rule("updateRsotSchemaDraft","PATCH","/api/v1/rsot/schemas/"+UUID),
        rule("publishRsotSchema","POST","/api/v1/rsot/schemas/"+UUID+"/publish"),
        rule("deprecateRsotSchema","POST","/api/v1/rsot/schemas/"+UUID+"/deprecate"),
        rule("createRsotSchemaProfile","POST","/api/v1/rsot/schema-profiles"),
        rule("publishRsotSchemaProfile","POST","/api/v1/rsot/schema-profiles/"+UUID+"/publish"),
        rule("deprecateRsotSchemaProfile","POST","/api/v1/rsot/schema-profiles/"+UUID+"/deprecate"),
        rule("replayIntegrationDeadLetter","POST","/api/v1/integrations/dlq/"+UUID+"/replay"),
        rule("resumeIntegrationConnector","POST","/api/v1/integrations/connectors/"+UUID+"/resume"),
        rule("executeConnectorSynchronization","POST","/api/v1/integrations/sync/"+UUID+"/execute"),
        rule("resumeConnectorSynchronization","POST","/api/v1/integrations/sync/runs/"+UUID+"/resume"),
        rule("compensateConnectorSynchronization","POST","/api/v1/integrations/sync/runs/"+UUID+"/compensate")
    );

    public Optional<String> operation(HttpServletRequest request) {
        return RULES.stream().filter(rule -> rule.matches(request)).map(Rule::operation).findFirst();
    }

    private static Rule rule(String operation, String method, String regex) {
        return new Rule(operation, method, Pattern.compile("^" + regex + "$"));
    }
}
