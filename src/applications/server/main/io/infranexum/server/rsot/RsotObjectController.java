package io.infranexum.server.rsot;

import static io.infranexum.server.rsot.RsotObjectApiModels.CanonicalObjectResponse;

import io.infranexum.core.capabilities.CapabilityGuard;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.rsot.application.RsotQueryService;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.PermissionCodes;
import io.infranexum.server.identityaccess.ScopedAuthorizationGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.infranexum.server.platform.PlatformCapabilityService;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only HTTP boundary exposing canonical RSOT objects to governance and ITAM selectors. */
@RestController
@ConditionalOnExpression("\'${infranexum.persistence.mode:MEMORY}\' == \'POSTGRESQL\' || \'${infranexum.persistence.mode:MEMORY}\' == \'ORACLE\'")
@RequestMapping("/api/v1/rsot/canonical-objects")
public final class RsotObjectController {
    private final RsotQueryService queries;
    private final PlatformCapabilityService capabilities;
    private final ScopedAuthorizationGuard authorization;

    public RsotObjectController(RsotQueryService queries, PlatformCapabilityService capabilities, ScopedAuthorizationGuard authorization) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    @GetMapping
    List<CanonicalObjectResponse> list(
            @RequestParam(name = "organization_id") String organizationId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request, HttpServletResponse response) {
        requireCapability();
        DomainIdentifier organization = id(organizationId);
        authorization.require(request, response, PermissionCodes.RSOT_READ, AuthorizationScope.organization(organization), "rsot-object", "collection");
        return queries.list(organization, offset, limit, true).stream().map(CanonicalObjectResponse::from).toList();
    }

    @GetMapping("/{canonicalId}")
    CanonicalObjectResponse get(@PathVariable String canonicalId, HttpServletRequest request, HttpServletResponse response) {
        requireCapability();
        var object = queries.get(id(canonicalId), true);
        authorization.require(request, response, PermissionCodes.RSOT_READ, AuthorizationScope.organization(object.organizationId()), "rsot-object", object.id().toString());
        return CanonicalObjectResponse.from(object);
    }

    private void requireCapability() {
        CapabilityGuard.requireAvailable(capabilities.explain("rsot.core"));
    }

    private static DomainIdentifier id(String value) {
        return DomainIdentifier.parse(value);
    }
}
