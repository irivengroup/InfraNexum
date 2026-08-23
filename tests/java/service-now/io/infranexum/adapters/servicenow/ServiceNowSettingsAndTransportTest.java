package io.infranexum.adapters.servicenow;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.integrations.ConnectorKey;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServiceNowSettingsAndTransportTest {
    private static final ConnectorKey KEY = new ConnectorKey("service-now.test");

    @Test
    void settingsExposeFixedGovernanceAndRejectUnsafeConfiguration() {
        ServiceNowSettings settings = new ServiceNowSettings(KEY, "tenant.service-now.com", "cmdb_ci_server",
                "file:/run/secrets/servicenow", Duration.ofSeconds(15), true);
        assertEquals("service-now", ServiceNowSettings.PROVIDER);
        assertEquals("FEDERATED_READ", ServiceNowSettings.DIRECTION);
        assertEquals("EXTERNAL", ServiceNowSettings.AUTHORITY);
        assertEquals("tenant.service-now.com", settings.instanceHost());
        assertEquals("cmdb_ci_server", settings.tableName());

        assertThrows(NullPointerException.class, () -> new ServiceNowSettings(null, "tenant.service-now.com", "cmdb_ci", "env:SN_TOKEN", Duration.ofSeconds(1), true));
        for (String host : new String[] {null, " tenant.service-now.com", "tenant.example.com", "https://tenant.service-now.com", "TENANT.service-now.com"}) {
            assertThrows(ConfigurationException.class, () -> new ServiceNowSettings(KEY, host, "cmdb_ci", "env:SN_TOKEN", Duration.ofSeconds(1), true));
        }
        for (String table : new String[] {null, "incident", "sys_user", "cmdb-ci", " CMDB_CI"}) {
            assertThrows(ConfigurationException.class, () -> new ServiceNowSettings(KEY, "tenant.service-now.com", table, "env:SN_TOKEN", Duration.ofSeconds(1), true));
        }
        assertThrows(ConfigurationException.class, () -> new ServiceNowSettings(KEY, "tenant.service-now.com", "cmdb_ci", "inline:secret", Duration.ofSeconds(1), true));
        assertThrows(ConfigurationException.class, () -> new ServiceNowSettings(KEY, "tenant.service-now.com", "cmdb_ci", "env:SN_TOKEN", Duration.ZERO, true));
        assertThrows(ConfigurationException.class, () -> new ServiceNowSettings(KEY, "tenant.service-now.com", "cmdb_ci", "env:SN_TOKEN", Duration.ofSeconds(61), true));
    }


    @Test
    void mutationSettingsRequireImmutableIdAndSafeProviderColumns() {
        ServiceNowMutationSettings settings = new ServiceNowMutationSettings(
                KEY, "id", Map.of("id", "u_infranexum_id", "asset_type", "u_asset_type"), 50);
        assertEquals("u_infranexum_id", settings.identityField());
        assertEquals(2, settings.fieldNames().size());
        assertThrows(ConfigurationException.class, () -> new ServiceNowMutationSettings(
                KEY, "id", Map.of("id", "sys_id"), 50));
        assertThrows(ConfigurationException.class, () -> new ServiceNowMutationSettings(
                KEY, "id", Map.of("id", "name"), 50));
        assertThrows(ConfigurationException.class, () -> new ServiceNowMutationSettings(
                KEY, "id", Map.of("id", "u_infranexum_id", "asset_type", "u-bad"), 50));
        assertThrows(ConfigurationException.class, () -> new ServiceNowMutationSettings(
                KEY, "id", Map.of("id", "u_infranexum_id", "asset_type", "u_infranexum_id"), 50));
    }

    @Test
    void requestAndResponseAreDefensiveAndTransportIsSaasHostPinned() {
        byte[] body = new byte[0];
        ServiceNowTransport.Request request = new ServiceNowTransport.Request(
                URI.create("https://tenant.service-now.com/api/now/table/cmdb_ci"), "GET", Map.of("Accept", "application/json"), body,
                Duration.ofSeconds(2));
        assertArrayEquals(new byte[0], request.body());
        ServiceNowTransport.Response response = new ServiceNowTransport.Response(200, Map.of("x", List.of("y")), new byte[] {3, 4});
        byte[] copy = response.body(); copy[0] = 9; assertArrayEquals(new byte[] {3, 4}, response.body());

        for (URI uri : List.of(
                URI.create("http://tenant.service-now.com/path"),
                URI.create("https://tenant.example.com/path"),
                URI.create("https://service-now.com/path"),
                URI.create("https://user@tenant.service-now.com/path"),
                URI.create("https://tenant.service-now.com:8443/path"),
                URI.create("/relative"))) {
            assertThrows(IllegalArgumentException.class, () -> new ServiceNowTransport.Request(uri, "GET", Map.of(), new byte[0], Duration.ofSeconds(1)));
        }
        assertDoesNotThrow(() -> new ServiceNowTransport.Request(
                URI.create("https://tenant.service-now.com/api/now/table/cmdb_ci"), "POST", Map.of(), new byte[] {1}, Duration.ofSeconds(1)));
        assertDoesNotThrow(() -> new ServiceNowTransport.Request(
                URI.create("https://tenant.service-now.com/api/now/table/cmdb_ci/0123456789abcdef0123456789abcdef"), "PATCH", Map.of(), new byte[] {1}, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ServiceNowTransport.Request(
                URI.create("https://tenant.service-now.com/api/now/import/x"), "POST", Map.of(), new byte[] {1}, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ServiceNowTransport.Request(
                URI.create("https://tenant.service-now.com/api/now/table/cmdb_ci#fragment"), "GET", Map.of(), new byte[0], Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ServiceNowTransport.Request(
                URI.create("https://tenant.service-now.com/api/now/table/cmdb_ci"), "POST", Map.of(), new byte[0], Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ServiceNowTransport.Request(
                URI.create("https://tenant.service-now.com/api/now/table/cmdb_ci"), "PUT", Map.of(), new byte[] {1}, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ServiceNowTransport.Request(
                URI.create("https://tenant.service-now.com/api/now/table/cmdb_ci"), "GET", Map.of(), new byte[] {1}, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ServiceNowTransport.Response(99, Map.of(), new byte[0]));
        assertThrows(NullPointerException.class, () -> new ServiceNowTransport.Request(null, "GET", Map.of(), new byte[0], Duration.ofSeconds(1)));
    }
}
