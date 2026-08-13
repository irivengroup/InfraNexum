package io.infranexum.identity.access.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;

/** Runtime authorization scope; a subdivision always belongs to an organization. */
public record AuthorizationScope(ScopeKind kind, DomainIdentifier organizationId, DomainIdentifier subdivisionId) {
    public AuthorizationScope {
        Objects.requireNonNull(kind, "kind");
        switch (kind) {
            case PLATFORM -> { if (organizationId != null || subdivisionId != null) throw new IllegalArgumentException("platform scope cannot contain organization identifiers"); }
            case ORGANIZATION -> { Objects.requireNonNull(organizationId, "organizationId"); if (subdivisionId != null) throw new IllegalArgumentException("organization scope cannot contain subdivisionId"); }
            case SUBDIVISION -> { Objects.requireNonNull(organizationId, "organizationId"); Objects.requireNonNull(subdivisionId, "subdivisionId"); }
        }
    }

    public static AuthorizationScope platform() { return new AuthorizationScope(ScopeKind.PLATFORM, null, null); }
    public static AuthorizationScope organization(DomainIdentifier organizationId) { return new AuthorizationScope(ScopeKind.ORGANIZATION, organizationId, null); }
    public static AuthorizationScope subdivision(DomainIdentifier organizationId, DomainIdentifier subdivisionId) { return new AuthorizationScope(ScopeKind.SUBDIVISION, organizationId, subdivisionId); }

    /** Returns true when this assignment scope covers the requested resource scope. */
    public boolean covers(AuthorizationScope requested) {
        Objects.requireNonNull(requested, "requested");
        if (kind == ScopeKind.PLATFORM) return true;
        if (requested.kind == ScopeKind.PLATFORM || !organizationId.equals(requested.organizationId)) return false;
        return kind == ScopeKind.ORGANIZATION || (requested.kind == ScopeKind.SUBDIVISION && subdivisionId.equals(requested.subdivisionId));
    }
}
