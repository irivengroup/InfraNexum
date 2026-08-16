package io.infranexum.server.platform;

import java.util.Objects;

/** Canonical runtime route-to-capability registry for the public v1 API contract. */
public final class ApiCapabilityRequirement {
    private ApiCapabilityRequirement() {}

    public static String resolve(String requestPath) {
        Objects.requireNonNull(requestPath, "requestPath");
        String path = normalize(requestPath);

        if (path.equals("/api/v1/system/build")) return "platform.bootstrap";
        if (route(path, "/api/v1/platform/capabilities") || path.equals("/api/v1/platform/quotas")) return "platform.bootstrap";
        if (route(path, "/api/v1/iam/local-auth")) return "iam.local-auth";
        if (route(path, "/api/v1/iam/organizations")
                || route(path, "/api/v1/iam/users")
                || route(path, "/api/v1/iam/groups")
                || route(path, "/api/v1/iam/policies")
                || route(path, "/api/v1/iam/authorization")
                || organizationAccessRoute(path)) return "iam.access";
        if (route(path, "/api/v1/rsot")) return "rsot.core";
        if (route(path, "/api/v1/itam/partners")) return "itam.partners";
        if (itamComplianceRoute(path)) return "itam.compliance";
        if (route(path, "/api/v1/itam/assets")) return "itam.assets";
        if (dcimFacilityRoute(path)) return "dcim.facilities";
        if (dcimPhysicalRoute(path)) return "dcim.physical";
        if (route(path, "/api/v1/ddi/ipam")) return "ddi.ipam";
        if (route(path, "/api/v1/integrations")) return "integrations.connectors";
        if (path.equals("/api/v1/platform/evaluation/status")) return "platform.entitlements";
        return null;
    }

    private static boolean organizationAccessRoute(String path) {
        if (!route(path, "/api/v1/organizations")) return false;
        String tail = path.substring("/api/v1/organizations".length());
        return tail.matches("/[^/]+/(groups|roles|permissions)(/.*)?");
    }

    private static boolean itamComplianceRoute(String path) {
        if (route(path, "/api/v1/itam/warranties")
                || route(path, "/api/v1/itam/licenses")
                || route(path, "/api/v1/itam/support-coverages")
                || route(path, "/api/v1/itam/support-authorizations")
                || path.equals("/api/v1/itam/warranty-types")) return true;
        if (path.matches("/api/v1/itam/(warranties|licenses|support-coverages)/[^/]+/history")) return true;
        return path.matches("/api/v1/itam/assets/[^/]+/(warranties|licenses|support-coverages|compliance-alerts)(/.*)?");
    }

    private static boolean dcimFacilityRoute(String path) {
        return route(path, "/api/v1/dcim/sites")
                || route(path, "/api/v1/dcim/buildings")
                || route(path, "/api/v1/dcim/floors")
                || route(path, "/api/v1/dcim/rooms")
                || route(path, "/api/v1/dcim/zones");
    }

    private static boolean dcimPhysicalRoute(String path) {
        return route(path, "/api/v1/dcim/equipment-models")
                || route(path, "/api/v1/dcim/racks")
                || route(path, "/api/v1/dcim/equipment")
                || route(path, "/api/v1/dcim/cables");
    }

    private static boolean route(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static String normalize(String path) {
        if (path.isBlank()) return path;
        int query = path.indexOf('?');
        String value = query < 0 ? path : path.substring(0, query);
        return value.length() > 1 && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
