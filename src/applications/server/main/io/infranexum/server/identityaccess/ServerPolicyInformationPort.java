package io.infranexum.server.identityaccess;

import io.infranexum.adapters.persistence.jdbc.JdbcIdentityAccessRepository;
import io.infranexum.identity.access.domain.PolicyAttributeBag;
import io.infranexum.identity.access.domain.PolicyAttributeSource;
import io.infranexum.identity.access.domain.PolicyEvaluationRequest;
import io.infranexum.identity.access.domain.Role;
import io.infranexum.identity.access.ports.PolicyInformationPort;
import io.infranexum.server.platform.PlatformCapabilityService;
import java.time.Instant;
import java.util.Objects;

/** Trusted PIP assembling authorization attributes from server-owned state only. */
final class ServerPolicyInformationPort implements PolicyInformationPort {
    private final JdbcIdentityAccessRepository identities;
    private final PlatformCapabilityService capabilities;

    ServerPolicyInformationPort(JdbcIdentityAccessRepository identities, PlatformCapabilityService capabilities) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    @Override
    public PolicyAttributeBag resolve(PolicyEvaluationRequest request, Instant at) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(at, "at");
        var snapshot = capabilities.snapshot();
        var quotaPlan = capabilities.quotaPlan();
        PolicyAttributeBag.Builder attributes = PolicyAttributeBag.builder()
                .put(PolicyAttributeSource.SUBJECT, "id", request.subjectId().toString())
                .putAll(PolicyAttributeSource.SUBJECT, "permissions",
                        identities.effectivePermissionCodes(request.subjectId(), request.scope(), at))
                .put(PolicyAttributeSource.SUBJECT, "platform_admin",
                        Boolean.toString(identities.hasEffectiveSystemRole(request.subjectId(), Role.PLATFORM_ADMIN_CODE, at)))
                .put(PolicyAttributeSource.RESOURCE, "type", request.resourceType())
                .put(PolicyAttributeSource.RESOURCE, "id", request.resourceId())
                .put(PolicyAttributeSource.AUTHENTICATION, "context", request.authenticationContext())
                .put(PolicyAttributeSource.CAPABILITY, "catalog_version", snapshot.catalogVersion())
                .put(PolicyAttributeSource.CAPABILITY, "profile_version", Long.toString(snapshot.profileVersion()))
                .put(PolicyAttributeSource.CAPABILITY, "profile", quotaPlan.profile().name())
                .put(PolicyAttributeSource.CAPABILITY, "tier", quotaPlan.tier().name())
                .put(PolicyAttributeSource.CAPABILITY, "hash", snapshot.capabilityHash())
                .put(PolicyAttributeSource.RBAC, "permitted", Boolean.toString(request.rbacPermitted()));
        if (request.scope().organizationId() != null) {
            attributes.put(PolicyAttributeSource.ORGANIZATION, "id", request.scope().organizationId().toString());
        }
        if (request.scope().subdivisionId() != null) {
            attributes.put(PolicyAttributeSource.SUBDIVISION, "id", request.scope().subdivisionId().toString());
        }
        request.environment().forEach((key, value) -> attributes.put(PolicyAttributeSource.ENVIRONMENT, key, value));
        return attributes.build();
    }
}
