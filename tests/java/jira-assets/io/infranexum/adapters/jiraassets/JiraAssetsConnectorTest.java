package io.infranexum.adapters.jiraassets;

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

class JiraAssetsConnectorTest {
    private static final ConnectorKey KEY = new ConnectorKey("jira-assets.test");
    private static final JiraAssetsSettings SETTINGS = new JiraAssetsSettings(
            KEY, "cloud-123", "workspace-456", "env:JIRA_TOKEN", Duration.ofSeconds(15), true);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void healthUsesSchemaListAndNeverLeaksCredential() {
        RecordingTransport transport = new RecordingTransport(response(200, "{}"));
        TrackingSecrets secrets = new TrackingSecrets("abcdefghijklmnopqrstuvwxyz0123456789");
        JiraAssetsConnector connector = new JiraAssetsConnector(SETTINGS, transport, secrets, JSON);

        JiraAssetsConnector.Health health = connector.health();

        assertEquals("UP", health.status());
        assertEquals("jira-assets", health.provider());
        assertEquals("FEDERATED_READ", health.direction());
        assertEquals("EXTERNAL", health.authority());
        JiraAssetsTransport.Request request = transport.requests.getFirst();
        assertEquals("GET", request.method());
        assertEquals("api.atlassian.com", request.uri().getHost());
        assertTrue(request.uri().getPath().endsWith("/v1/objectschema/list"));
        assertEquals("startAt=0&maxResults=1&includeCounts=false", request.uri().getQuery());
        assertEquals("Bearer abcdefghijklmnopqrstuvwxyz0123456789", request.headers().get("Authorization"));
        assertArrayEquals(new byte[secrets.lastResolved.length], secrets.lastResolved, "resolved secret buffer must be zeroed");
    }

    @Test
    void searchPostsModernAqlEndpointWithBoundedProjectionAndPagination() {
        String payload = """
                {"startAt":0,"maxResults":2,"total":3,"values":[
                  {"id":"1","globalId":"g1","objectKey":"SRV-1","label":"Server 1","objectType":{"id":"10","name":"Server"},"attributes":[{"secret":"ignored"}]},
                  {"id":"2","globalId":"g2","objectKey":"SRV-2","label":"Server 2","objectType":{"id":"10","name":"Server"}}
                ]}
                """;
        RecordingTransport transport = new RecordingTransport(response(200, payload));
        JiraAssetsConnector connector = connector(transport);

        JiraAssetsConnector.ObjectPage page = connector.search(" objectType = Server ", 0, 2);

        assertEquals(2, page.items().size());
        assertEquals(3, page.total());
        assertEquals(2, page.nextOffset());
        assertEquals("SRV-1", page.items().getFirst().objectKey());
        assertEquals("Server", page.items().getFirst().objectTypeName());
        JiraAssetsTransport.Request request = transport.requests.getFirst();
        assertEquals("POST", request.method());
        assertTrue(request.uri().getPath().endsWith("/v1/object/aql"));
        assertEquals("startAt=0&maxResults=2&includeAttributes=false", request.uri().getQuery());
        assertEquals("application/json", request.headers().get("Content-Type"));
        assertEquals("objectType = Server", JSON.readTree(request.body()).get("qlQuery").textValue());
        assertFalse(new String(request.body(), StandardCharsets.UTF_8).contains("attributes"));

        RecordingTransport last = new RecordingTransport(response(200,
                "{\"startAt\":2,\"maxResults\":2,\"total\":3,\"values\":[{\"id\":\"3\",\"globalId\":\"g3\",\"objectKey\":\"SRV-3\",\"label\":\"Server 3\",\"objectType\":{\"id\":\"10\",\"name\":\"Server\"}}]}"));
        assertNull(connector(last).search("objectType = Server", 2, 2).nextOffset());
    }

    @Test
    void searchRejectsInvalidInputsBeforeTransport() {
        RecordingTransport transport = new RecordingTransport(response(200, "{}"));
        JiraAssetsConnector connector = connector(transport);
        assertThrows(IllegalArgumentException.class, () -> connector.search(null, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> connector.search("   ", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> connector.search("x\n", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> connector.search("x".repeat(4097), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> connector.search("x", -1, 1));
        assertThrows(IllegalArgumentException.class, () -> connector.search("x", 1_000_001, 1));
        assertThrows(IllegalArgumentException.class, () -> connector.search("x", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> connector.search("x", 0, 201));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void providerStatusesMapToStableFailureTypes() {
        assertThrows(JiraAssetsAuthenticationException.class, () -> connectorWithStatus(401).health());
        assertThrows(JiraAssetsAuthenticationException.class, () -> connectorWithStatus(403).health());
        assertThrows(JiraAssetsRateLimitedException.class, () -> connectorWithStatus(429).health());
        assertThrows(JiraAssetsUnavailableException.class, () -> connectorWithStatus(500).health());
        assertThrows(JiraAssetsUnavailableException.class, () -> connectorWithStatus(599).health());
        assertThrows(JiraAssetsProtocolException.class, () -> connectorWithStatus(400).health());
    }

    @Test
    void disabledOrMalformedCredentialFailsClosed() {
        JiraAssetsSettings disabled = new JiraAssetsSettings(KEY, "cloud", "workspace", "env:JIRA_TOKEN", Duration.ofSeconds(1), false);
        assertThrows(JiraAssetsUnavailableException.class,
                () -> new JiraAssetsConnector(disabled, request -> response(200, "{}"), ref -> "x".repeat(32).getBytes(StandardCharsets.UTF_8), JSON).health());
        assertThrows(JiraAssetsAuthenticationException.class,
                () -> new JiraAssetsConnector(SETTINGS, request -> response(200, "{}"), ref -> new byte[0], JSON).health());
        assertThrows(JiraAssetsAuthenticationException.class,
                () -> new JiraAssetsConnector(SETTINGS, request -> response(200, "{}"), ref -> " token-with-space ".getBytes(StandardCharsets.UTF_8), JSON).health());
        assertThrows(JiraAssetsAuthenticationException.class,
                () -> new JiraAssetsConnector(SETTINGS, request -> response(200, "{}"), ref -> "token\ncontrol".getBytes(StandardCharsets.UTF_8), JSON).health());
    }

    @Test
    void malformedProviderPagesAreRejectedWithoutReturningPartialData() {
        List<String> invalidPayloads = List.of(
                "not-json",
                "[]",
                "{}",
                "{\"startAt\":0,\"maxResults\":1,\"total\":1,\"values\":{}}",
                "{\"startAt\":1,\"maxResults\":1,\"total\":1,\"values\":[]}",
                "{\"startAt\":0,\"maxResults\":2,\"total\":1,\"values\":[]}",
                "{\"startAt\":0,\"maxResults\":1,\"total\":-1,\"values\":[]}",
                "{\"startAt\":0,\"maxResults\":1,\"total\":1,\"values\":[{}]}",
                "{\"startAt\":0,\"maxResults\":1,\"total\":1,\"values\":[{\"id\":\"1\",\"globalId\":\"g\",\"objectKey\":\"K\",\"label\":\"L\"}]}",
                "{\"startAt\":0,\"maxResults\":1,\"total\":1,\"values\":[{\"id\":\"1\",\"globalId\":\"g\",\"objectKey\":\"K\",\"label\":\"L\",\"objectType\":{\"id\":\"\",\"name\":\"Type\"}}]}"
        );
        for (String payload : invalidPayloads) {
            RecordingTransport transport = new RecordingTransport(response(200, payload));
            assertThrows(JiraAssetsProtocolException.class,
                    () -> connector(transport).search("objectType = Server", 0, 1), payload);
        }
        RecordingTransport tooManyValues = new RecordingTransport(response(200,
                "{\"startAt\":0,\"maxResults\":1,\"total\":2,\"values\":[{\"id\":\"1\",\"globalId\":\"g1\",\"objectKey\":\"K1\",\"label\":\"L1\",\"objectType\":{\"id\":\"t\",\"name\":\"T\"}},{\"id\":\"2\",\"globalId\":\"g2\",\"objectKey\":\"K2\",\"label\":\"L2\",\"objectType\":{\"id\":\"t\",\"name\":\"T\"}}]}"));
        assertThrows(JiraAssetsProtocolException.class, () -> connector(tooManyValues).search("x", 0, 1));
    }

    private static JiraAssetsConnector connector(RecordingTransport transport) {
        return new JiraAssetsConnector(SETTINGS, transport,
                reference -> "abcdefghijklmnopqrstuvwxyz0123456789".getBytes(StandardCharsets.UTF_8), JSON);
    }

    private static JiraAssetsConnector connectorWithStatus(int status) {
        return connector(new RecordingTransport(response(status, "{}")));
    }

    private static JiraAssetsTransport.Response response(int status, String body) {
        return new JiraAssetsTransport.Response(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class RecordingTransport implements JiraAssetsTransport {
        private final JiraAssetsTransport.Response response;
        private final List<JiraAssetsTransport.Request> requests = new ArrayList<>();
        private RecordingTransport(JiraAssetsTransport.Response response) { this.response = response; }
        @Override public JiraAssetsTransport.Response execute(JiraAssetsTransport.Request request) {
            requests.add(request);
            return response;
        }
    }

    private static final class TrackingSecrets implements ConnectorSecretProvider {
        private final String value;
        private byte[] lastResolved;
        private TrackingSecrets(String value) { this.value = value; }
        @Override public byte[] resolve(String reference) {
            lastResolved = value.getBytes(StandardCharsets.UTF_8);
            return lastResolved;
        }
    }
}
