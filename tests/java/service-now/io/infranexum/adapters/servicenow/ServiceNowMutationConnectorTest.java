package io.infranexum.adapters.servicenow;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.integrations.ConnectorKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ServiceNowMutationConnectorTest {
    private static final ConnectorKey KEY = new ConnectorKey("service-now.test");
    private static final ServiceNowSettings SETTINGS = new ServiceNowSettings(
            KEY, "tenant.service-now.com", "cmdb_ci_server", "env:SN_TOKEN", Duration.ofSeconds(15), true);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IDENTITY = "018f0d34-2c00-7000-8000-000000000002";
    private static final String SYS_ID = "0123456789abcdef0123456789abcdef";

    @Test
    void identityLookupCreateAndPatchAreBoundedAndUseOnlyConfiguredTable() throws Exception {
        QueueTransport transport = new QueueTransport();
        transport.responses.add(response(200, "{\"result\":[]}"));
        transport.responses.add(response(201, "{\"result\":{\"sys_id\":\"" + SYS_ID + "\"}}"));
        transport.responses.add(response(200, "{\"result\":[{\"sys_id\":\"" + SYS_ID
                + "\",\"u_infranexum_id\":\"" + IDENTITY + "\"}]}"));
        transport.responses.add(response(200, "{\"result\":{\"sys_id\":\"" + SYS_ID + "\"}}"));
        TrackingSecrets secrets = new TrackingSecrets();
        ServiceNowConnector connector = new ServiceNowConnector(SETTINGS, transport, secrets, JSON);

        assertTrue(connector.findUnique("u_infranexum_id", IDENTITY).isEmpty());
        assertEquals(SYS_ID, connector.create(Map.of(
                "u_infranexum_id", IDENTITY, "u_asset_type", "HARDWARE")).sysId());
        assertEquals(SYS_ID, connector.findUnique("u_infranexum_id", IDENTITY).orElseThrow().sysId());
        assertEquals(SYS_ID, connector.update(SYS_ID, Map.of(
                "u_infranexum_id", IDENTITY, "u_asset_type", "SOFTWARE")).sysId());

        assertEquals(List.of("GET", "POST", "GET", "PATCH"),
                transport.requests.stream().map(ServiceNowTransport.Request::method).toList());
        ServiceNowTransport.Request lookup = transport.requests.getFirst();
        assertEquals("/api/now/table/cmdb_ci_server", lookup.uri().getPath());
        assertTrue(lookup.uri().getRawQuery().contains(
                "sysparm_query=u_infranexum_id%3D" + IDENTITY + "%5EORDERBYsys_id"));
        assertTrue(lookup.uri().getRawQuery().contains("sysparm_limit=2"));
        assertTrue(lookup.uri().getRawQuery().contains("sysparm_fields=sys_id%2Cu_infranexum_id"));

        ServiceNowTransport.Request create = transport.requests.get(1);
        assertEquals("application/json", create.headers().get("Content-Type"));
        assertEquals("/api/now/table/cmdb_ci_server", create.uri().getPath());
        assertEquals(IDENTITY, JSON.readTree(create.body()).get("u_infranexum_id").textValue());
        assertEquals("HARDWARE", JSON.readTree(create.body()).get("u_asset_type").textValue());

        ServiceNowTransport.Request patch = transport.requests.get(3);
        assertEquals("/api/now/table/cmdb_ci_server/" + SYS_ID, patch.uri().getPath());
        assertEquals("SOFTWARE", JSON.readTree(patch.body()).get("u_asset_type").textValue());
        assertArrayEquals(new byte[secrets.lastResolved.length], secrets.lastResolved);
    }

    @Test
    void duplicateOrMismatchedIdentityFailsClosedBeforeMutation() {
        QueueTransport duplicate = new QueueTransport();
        duplicate.responses.add(response(200, "{\"result\":["
                + "{\"sys_id\":\"" + SYS_ID + "\",\"u_infranexum_id\":\"" + IDENTITY + "\"},"
                + "{\"sys_id\":\"fedcba9876543210fedcba9876543210\",\"u_infranexum_id\":\"" + IDENTITY + "\"}]}"));
        assertThrows(ServiceNowIdentityConflictException.class, () -> connector(duplicate).findUnique("u_infranexum_id", IDENTITY));

        QueueTransport mismatch = new QueueTransport();
        mismatch.responses.add(response(200, "{\"result\":[{\"sys_id\":\"" + SYS_ID
                + "\",\"u_infranexum_id\":\"018f0d34-2c00-7000-8000-000000000003\"}]}"));
        assertThrows(ServiceNowProtocolException.class, () -> connector(mismatch).findUnique("u_infranexum_id", IDENTITY));
    }

    @Test
    void invalidIdentityColumnsValuesAndSysIdsAreRejectedBeforeEgress() {
        QueueTransport transport = new QueueTransport();
        ServiceNowConnector connector = connector(transport);
        for (String field : List.of("sys_id", "bad-field", " U_ID")) {
            assertThrows(RuntimeException.class, () -> connector.findUnique(field, IDENTITY));
        }
        assertThrows(IllegalArgumentException.class, () -> connector.findUnique("u_infranexum_id", "not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> connector.update("bad", Map.of("u_name", "server")));
        assertThrows(IllegalArgumentException.class, () -> connector.create(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> connector.create(Map.of("sys_id", SYS_ID)));
        assertThrows(IllegalArgumentException.class, () -> connector.create(Map.of("u_name", " bad ")));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void mutationProviderStatusesAndMalformedEnvelopesAreTranslatedWithoutRemoteBodies() {
        assertThrows(ServiceNowAuthenticationException.class, () -> createWithStatus(401));
        assertThrows(ServiceNowAuthenticationException.class, () -> createWithStatus(403));
        assertThrows(ServiceNowRateLimitedException.class, () -> createWithStatus(429));
        assertThrows(ServiceNowUnavailableException.class, () -> createWithStatus(500));
        assertThrows(ServiceNowProtocolException.class, () -> createWithStatus(400));

        for (String payload : List.of("not-json", "[]", "{}", "{\"result\":[]}",
                "{\"result\":{}}", "{\"result\":{\"sys_id\":\"bad\"}}")) {
            QueueTransport transport = new QueueTransport();
            transport.responses.add(response(201, payload));
            assertThrows(ServiceNowProtocolException.class, () -> connector(transport).create(Map.of(
                    "u_infranexum_id", IDENTITY)), payload);
        }
    }

    private static void createWithStatus(int status) {
        QueueTransport transport = new QueueTransport();
        transport.responses.add(response(status, "{}"));
        connector(transport).create(Map.of("u_infranexum_id", IDENTITY));
    }

    private static ServiceNowConnector connector(QueueTransport transport) {
        return new ServiceNowConnector(SETTINGS, transport,
                reference -> "abcdefghijklmnopqrstuvwxyz0123456789".getBytes(StandardCharsets.UTF_8), JSON);
    }

    private static ServiceNowTransport.Response response(int status, String body) {
        return new ServiceNowTransport.Response(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class QueueTransport implements ServiceNowTransport {
        private final Deque<ServiceNowTransport.Response> responses = new ArrayDeque<>();
        private final List<ServiceNowTransport.Request> requests = new ArrayList<>();
        @Override public ServiceNowTransport.Response execute(ServiceNowTransport.Request request) {
            requests.add(request);
            return responses.removeFirst();
        }
    }

    private static final class TrackingSecrets implements io.infranexum.integrations.ConnectorSecretProvider {
        private byte[] lastResolved;
        @Override public byte[] resolve(String reference) {
            lastResolved = "abcdefghijklmnopqrstuvwxyz0123456789".getBytes(StandardCharsets.UTF_8);
            return lastResolved;
        }
    }
}
