package io.infranexum.organization.ports;
/** Runtime profile/quota decision port; the domain never parses edition/profile manifests itself. */
public interface OrganizationFeaturePolicy { boolean supportsOrganizationHierarchy(); boolean supportsSubdivisions(); long organizationLimit(); long subdivisionLimit(); long hierarchyDepthLimit(); }
