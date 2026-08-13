package io.infranexum.identity.access.ports;

/** Profile-gated IAM RBAC features defined by the capability/entitlement layer. */
public interface IdentityAccessFeaturePolicy {
    boolean supportsNestedGroups();
    boolean supportsMultiMembership();
}
