package io.infranexum.adapters.servicenow;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorSecretProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ServiceNowConnectorTest {
    private static final ConnectorKey KEY = new ConnectorKey("service-now.test");
    private static final ServiceNowSettings SETTINGS = new ServiceNowSettings(
            KEY, "tenant.service-now.com", "cmdb_ci_server", "env:SN_TOKEN", Duration.ofSeconds(15), true);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void healthReadsConfiguredCmdbTableAndZeroesCredential() {
        RecordingTransport transport = new RecordingTransport(response(200,
                "{\"result\":[{\"sys_id\":\"0123456789abcdef0123456789abcdef\"}]}"));
        TrackingSecrets secrets = new TrackingSecrets("abcdefghijklmnopqrstuvwxyz0123456789");
        ServiceNowConnector connector = new ServiceNowConnector(SETTINGS, transport, secrets, JSON);
        ServiceNowConnector.Health health = connector.health();
        assertEquals("UP", health.status());
        assertEquals("service-now", health.provider());
        ServiceNowTransport.Request request = transport.requests.getFirst();
        assertEquals("GET", request.method());
        assertEquals("tenant.service-now.com", request.uri().getHost());
        assertEquals("/api/now/table/cmdb_ci_server", request.uri().getPath());
        assertTrue(request.uri().getRawQuery().contains("sysparm_fields=sys_id"));
        assertTrue(request.uri().getRawQuery().contains("sysparm_no_count=true"));
        assertEquals("Bearer abcdefghijklmnopqrstuvwxyz0123456789", request.headers().get("Authorization"));
        assertArrayEquals(new byte[secrets.lastResolved.length], secrets.lastResolved);
    }

    @Test
    void searchBuildsConstrainedNameQueryAndReturnsMinimalProjection() {
        RecordingTransport transport = new RecordingTransport(response(200, """
                {"result":[
                  {"sys_id":"0123456789abcdef0123456789abcdef","name":"server-01","sys_class_name":"cmdb_ci_server","sys_updated_on":"2026-08-17 12:00:00","serial_number":"must-not-escape"},
                  {"sys_id":"fedcba9876543210fedcba9876543210","name":"server-02","sys_class_name":"cmdb_ci_server","sys_updated_on":"2026-08-17 12:01:00"}
                ]}
                """));
        ServiceNowConnector.ConfigurationItemPage page = connector(transport).search(" server ", 0, 2);
        assertEquals(2, page.items().size());
        assertEquals(2, page.nextOffset());
        assertEquals("server-01", page.items().getFirst().name());
        assertEquals("cmdb_ci_server", page.items().getFirst().className());
        ServiceNowTransport.Request request = transport.requests.getFirst();
        String query = request.uri().getRawQuery();
        assertTrue(query.contains("sysparm_query=nameLIKEserver%5EORDERBYsys_id"));
        assertTrue(query.contains("sysparm_fields=sys_id%2Cname%2Csys_class_name%2Csys_updated_on"));
        assertTrue(query.contains("sysparm_limit=2"));
        assertTrue(query.contains("sysparm_offset=0"));

        RecordingTransport last = new RecordingTransport(response(200, "{\"result\":[]}"));
        assertNull(connector(last).search("server", 2, 2).nextOffset());
    }

    @Test
    void searchRejectsEncodedQueryInjectionAndInvalidBoundsBeforeTransport() {
        RecordingTransport transport = new RecordingTransport(response(200, "{}"));
        ServiceNowConnector connector = connector(transport);
        for (String value : new String[] {null, "", "   ", "name^ORactive=true", "x=y", "javascript:gs.getUserID()", "x".repeat(257)}) {
            assertThrows(IllegalArgumentException.class, () -> connector.search(value, 0, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> connector.search("server", -1, 1));
        assertThrows(IllegalArgumentException.class, () -> connector.search("server", 1_000_001, 1));
        assertThrows(IllegalArgumentException.class, () -> connector.search("server", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> connector.search("server", 0, 201));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void providerStatusesAndMalformedResponsesFailClosed() {
        assertThrows(ServiceNowAuthenticationException.class, () -> connectorWithStatus(401).health());
        assertThrows(ServiceNowAuthenticationException.class, () -> connectorWithStatus(403).health());
        assertThrows(ServiceNowRateLimitedException.class, () -> connectorWithStatus(429).health());
        assertThrows(ServiceNowUnavailableException.class, () -> connectorWithStatus(500).health());
        assertThrows(ServiceNowProtocolException.class, () -> connectorWithStatus(400).health());
        for (String payload : List.of("not-json", "[]", "{}", "{\"result\":{}}",
                "{\"result\":[{\"sys_id\":\"bad\",\"name\":\"n\",\"sys_class_name\":\"cmdb_ci\",\"sys_updated_on\":\"x\"}]}",
                "{\"result\":[{\"sys_id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"n\",\"sys_class_name\":\"incident\",\"sys_updated_on\":\"x\"}]}")) {
            assertThrows(ServiceNowProtocolException.class, () -> connector(new RecordingTransport(response(200, payload))).search("server", 0, 1), payload);
        }
    }

    @Test
    void disabledOrMalformedCredentialFailsClosed() {
        ServiceNowSettings disabled = new ServiceNowSettings(KEY, "tenant.service-now.com", "cmdb_ci", "env:SN_TOKEN", Duration.ofSeconds(1), false);
        assertThrows(ServiceNowUnavailableException.class, () -> new ServiceNowConnector(disabled,
                request -> response(200, "{\"result\":[]}"), ref -> "x".repeat(32).getBytes(StandardCharsets.UTF_8), JSON).health());
        assertThrows(ServiceNowAuthenticationException.class, () -> new ServiceNowConnector(SETTINGS,
                request -> response(200, "{\"result\":[]}"), ref -> new byte[0], JSON).health());
        assertThrows(ServiceNowAuthenticationException.class, () -> new ServiceNowConnector(SETTINGS,
                request -> response(200, "{\"result\":[]}"), ref -> " token ".getBytes(StandardCharsets.UTF_8), JSON).health());
    }

    private static ServiceNowConnector connector(RecordingTransport transport) {
        return new ServiceNowConnector(SETTINGS, transport,
                reference -> "abcdefghijklmnopqrstuvwxyz0123456789".getBytes(StandardCharsets.UTF_8), JSON);
    }
    private static ServiceNowConnector connectorWithStatus(int status) { return connector(new RecordingTransport(response(status, "{}"))); }
    private static ServiceNowTransport.Response response(int status, String body) { return new ServiceNowTransport.Response(status, Map.of(), body.getBytes(StandardCharsets.UTF_8)); }

    private static final class RecordingTransport implements ServiceNowTransport {
        private final ServiceNowTransport.Response response;
        private final List<ServiceNowTransport.Request> requests = new ArrayList<>();
        private RecordingTransport(ServiceNowTransport.Response response) { this.response = response; }
        @Override public ServiceNowTransport.Response execute(ServiceNowTransport.Request request) { requests.add(request); return response; }
    }
    private static final class TrackingSecrets implements ConnectorSecretProvider {
        private final String value; private byte[] lastResolved;
        private TrackingSecrets(String value) { this.value = value; }
        @Override public byte[] resolve(String reference) { lastResolved = value.getBytes(StandardCharsets.UTF_8); return lastResolved; }
    }
}
