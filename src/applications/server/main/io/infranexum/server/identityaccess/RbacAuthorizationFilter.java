package io.infranexum.server.identityaccess;

import io.infranexum.server.http.AuthenticatedActorContext;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.application.AuthorizationDecision;
import io.infranexum.identity.access.application.RbacAuthorizationService;
import io.infranexum.server.http.ApiProblemSupport;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/** Server PEP enforcing the registered RBAC policy after successful authentication. */
public final class RbacAuthorizationFilter extends OncePerRequestFilter implements Ordered {
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 30;
    public static final String REQUIREMENT_ATTRIBUTE = "io.infranexum.authorization.rbac.requirement";
    private static final String API_PREFIX="/api/v1/";
    private static final String AUTH_PREFIX="/api/v1/iam/local-auth";
    private static final String PUBLIC_BUILD_PATH="/api/v1/system/build";
    private static final String WEBHOOK_PREFIX = "/api/v1/integrations/webhooks/";
    private final RbacAuthorizationService authorization;
    private final ApiProblemSupport problems;

    public RbacAuthorizationFilter(RbacAuthorizationService authorization, ApiProblemSupport problems){this.authorization=Objects.requireNonNull(authorization,"authorization");this.problems=Objects.requireNonNull(problems,"problems");}
    @Override public int getOrder(){return ORDER;}

    @Override protected boolean shouldNotFilter(HttpServletRequest request){String path=request.getRequestURI();return !path.startsWith(API_PREFIX)||path.equals(AUTH_PREFIX)||path.startsWith(AUTH_PREFIX+"/")||PUBLIC_BUILD_PATH.equals(path)||path.startsWith(WEBHOOK_PREFIX);}

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        Object actorValue=request.getAttribute(AuthenticatedActorContext.ACCOUNT_ATTRIBUTE);
        if(!(actorValue instanceof DomainIdentifier actor)){reject(request,response,HttpServletResponse.SC_UNAUTHORIZED,"INFRANEXUM_AUTHENTICATION_CONTEXT_MISSING","Authentication context missing");return;}
        DomainIdentifier correlation=CorrelationContext.identifier(request).orElse(null);
        if(correlation==null){reject(request,response,HttpServletResponse.SC_INTERNAL_SERVER_ERROR,"INFRANEXUM_CORRELATION_CONTEXT_MISSING","Correlation context missing");return;}
        AuthorizationRequirement requirement;
        try{requirement=AuthorizationRequirement.resolve(request.getMethod(),request.getRequestURI());}
        catch(IllegalArgumentException malformed){reject(request,response,HttpServletResponse.SC_BAD_REQUEST,"INFRANEXUM_INVALID_RESOURCE_IDENTIFIER","Invalid resource identifier");return;}
        AuthorizationDecision decision=switch(requirement.type()){
            case PERMISSION -> authorization.decide(actor,requirement.permissionCode(),requirement.scope(),correlation,requirement.targetType(),requirement.targetId(),"HTTP");
            case GROUP_PERMISSION -> authorization.decideGroupPermission(actor,requirement.permissionCode(),DomainIdentifier.parse(requirement.targetId()),correlation,"HTTP");
            case ORGANIZATION_VISIBILITY -> authorization.decideOrganizationVisibility(actor,requirement.scope().organizationId(),correlation,"HTTP");
            case PLATFORM_ADMINISTRATOR -> authorization.decidePlatformAdministrator(actor,correlation,requirement.targetType(),requirement.targetId(),"HTTP");
            case CONTROLLER_SCOPED -> new AuthorizationDecision(true, "DEFERRED_TO_CONTROLLER", "resource scope resolved at controller boundary");
            case UNREGISTERED -> authorization.denyUnregisteredRoute(actor,correlation,request.getMethod()+" "+request.getRequestURI(),"HTTP");
        };
        if(!decision.allowed()){reject(request,response,HttpServletResponse.SC_FORBIDDEN,"INFRANEXUM_AUTHORIZATION_DENIED",decision.explanation());return;}
        request.setAttribute(REQUIREMENT_ATTRIBUTE, requirement);
        chain.doFilter(request,response);
    }

    private void reject(HttpServletRequest request,HttpServletResponse response,int status,String code,String detail)throws IOException{
        HttpStatus httpStatus=HttpStatus.valueOf(status);
        problems.write(response,problems.problem(httpStatus,code,"Authorization denied",detail,java.util.Map.of(),java.util.Map.of(),request));
    }

}
