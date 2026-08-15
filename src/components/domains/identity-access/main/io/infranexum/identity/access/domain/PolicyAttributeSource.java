package io.infranexum.identity.access.domain;

/** Trusted PIP namespaces addressable from a declarative policy condition. */
public enum PolicyAttributeSource {
    SUBJECT,
    RESOURCE,
    ORGANIZATION,
    SUBDIVISION,
    ENVIRONMENT,
    AUTHENTICATION,
    CAPABILITY,
    RBAC
}
