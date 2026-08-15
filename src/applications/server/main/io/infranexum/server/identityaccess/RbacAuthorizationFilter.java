package io.infranexum.server.identityaccess;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.application.AuthorizationDecision;
import io.infranexum.identity.access.application.RbacAuthorizationService;
import io.infranexum.server.identity.LocalAuthenticationFilter;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Server PEP enforcing the registered RBAC policy after successful authentication. */
public final class RbacAuthorizationFilter extends OncePerRequestFilter implements Ordered {
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 30;
    public static final String REQUIREMENT_ATTRIBUTE = "io.infranexum.authorization.rbac.requirement";
    private static final String API_PREFIX="/api/v1/";
    private static final String AUTH_PREFIX="/api/v1/iam/local-auth";
    private static final String PUBLIC_BUILD_PATH="/api/v1/system/build";
    private final RbacAuthorizationService authorization;

    public RbacAuthorizationFilter(RbacAuthorizationService authorization){this.authorization=Objects.requireNonNull(authorization,"authorization");}
    @Override public int getOrder(){return ORDER;}

    @Override protected boolean shouldNotFilter(HttpServletRequest request){String path=request.getRequestURI();return !path.startsWith(API_PREFIX)||path.equals(AUTH_PREFIX)||path.startsWith(AUTH_PREFIX+"/")||PUBLIC_BUILD_PATH.equals(path);}

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        Object actorValue=request.getAttribute(LocalAuthenticationFilter.ACCOUNT_ATTRIBUTE);
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

    private static void reject(HttpServletRequest request,HttpServletResponse response,int status,String code,String detail)throws IOException{
        if(response.isCommitted())throw new IOException("authorization rejection response was committed before the RBAC boundary");
        response.resetBuffer();response.setStatus(status);response.setCharacterEncoding(StandardCharsets.UTF_8.name());response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);response.setHeader("Cache-Control","no-store");
        String correlation=CorrelationContext.traceId(request);if(correlation==null)correlation="unavailable";else response.setHeader(CorrelationContext.HEADER_NAME,correlation);
        String body="{\"status\":"+status+",\"title\":\"Authorization denied\",\"code\":\""+code+"\",\"detail\":\""+json(detail)+"\",\"correlation_id\":\""+correlation+"\",\"trace_id\":\""+correlation+"\"}\n";
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);response.setContentLength(bytes.length);response.getOutputStream().write(bytes);response.flushBuffer();
    }
    private static String json(String value){return value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");}
}
