package io.infranexum.adapters.jiraassets;

import io.infranexum.integrations.ConnectorSecretProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Governed Jira Assets Cloud connector supporting federated reads and explicitly admitted object upserts. */
public final class JiraAssetsConnector implements JiraAssetsMutationPort {
    private static final int MAX_AQL_LENGTH = 4_096;
    private static final int MAX_TEXT_LENGTH = 1_024;
    private static final int MAX_OFFSET = 1_000_000;
    private static final int MAX_LIMIT = 200;
    private static final byte[] EMPTY_BODY = new byte[0];

    private final JiraAssetsSettings settings;
    private final JiraAssetsTransport transport;
    private final ConnectorSecretProvider secrets;
    private final ObjectMapper json;
    private final String rootUrl;

    public JiraAssetsConnector(
            JiraAssetsSettings settings,
            JiraAssetsTransport transport,
            ConnectorSecretProvider secrets,
            ObjectMapper json) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.json = Objects.requireNonNull(json, "json");
        this.rootUrl = "https://api.atlassian.com/ex/jira/" + settings.cloudId()
                + "/jsm/assets/workspace/" + settings.workspaceId() + "/v1";
    }

    public JiraAssetsSettings settings() { return settings; }

    /** Performs a read-only schema-list request; credentials require only Assets read scopes. */
    public Health health() {
        requireEnabled();
        URI uri = URI.create(rootUrl + "/objectschema/list?startAt=0&maxResults=1&includeCounts=false");
        JiraAssetsTransport.Response response = execute(uri, "GET", EMPTY_BODY, false);
        requireSuccessful(response);
        return new Health(settings.connectorKey().value(), "UP", JiraAssetsSettings.PROVIDER,
                JiraAssetsSettings.DIRECTION, JiraAssetsSettings.AUTHORITY);
    }

    /** Executes a bounded AQL query and returns only identity/type metadata, never provider attributes. */
    public ObjectPage search(String aql, int offset, int limit) {
        requireEnabled();
        String query = normalizeAql(aql);
        if (offset < 0 || offset > MAX_OFFSET) throw new IllegalArgumentException("offset must be between 0 and 1000000");
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be between 1 and 200");
        byte[] body;
        try {
            body = json.writeValueAsBytes(Map.of("qlQuery", query));
        } catch (JacksonException failure) {
            throw new JiraAssetsProtocolException("Jira Assets request encoding failed", failure);
        }
        URI uri = URI.create(rootUrl + "/object/aql?startAt=" + offset + "&maxResults=" + limit + "&includeAttributes=false");
        JiraAssetsTransport.Response response = execute(uri, "POST", body, true);
        requireSuccessful(response);
        return parsePage(response.body(), offset, limit);
    }


    /** Finds at most one remote object for a deterministic identity AQL. */
    @Override
    public java.util.Optional<RemoteObject> findUnique(String aql) {
        ObjectPage page = search(aql, 0, 2);
        if (page.total() > 1 || page.items().size() > 1) throw new JiraAssetsIdentityConflictException();
        return page.items().isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(page.items().getFirst());
    }

    /** Creates one Jira Assets object using explicit provider attribute identifiers. */
    @Override
    public RemoteMutationObject create(String objectTypeId, Map<String, String> attributes) {
        return writeObject(null, objectTypeId, attributes, "POST", 201);
    }

    /** Updates one Jira Assets object using explicit provider attribute identifiers. */
    @Override
    public RemoteMutationObject update(String objectId, String objectTypeId, Map<String, String> attributes) {
        if (objectId == null || !objectId.equals(objectId.strip()) || !objectId.matches("^[A-Za-z0-9-]{1,128}$")) {
            throw new IllegalArgumentException("Jira Assets objectId is invalid");
        }
        return writeObject(objectId, objectTypeId, attributes, "PUT", 200);
    }

    private RemoteMutationObject writeObject(
            String objectId, String objectTypeId, Map<String, String> attributes, String method, int expectedStatus) {
        requireEnabled();
        String normalizedType = providerId(objectTypeId, "objectTypeId");
        Map<String, String> normalizedAttributes = normalizeMutationAttributes(attributes);
        List<Map<String, Object>> payloadAttributes = new ArrayList<>();
        normalizedAttributes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                payloadAttributes.add(Map.of(
                        "objectTypeAttributeId", entry.getKey(),
                        "objectAttributeValues", List.of(Map.of("value", entry.getValue())))));
        byte[] body;
        try {
            body = json.writeValueAsBytes(Map.of("objectTypeId", normalizedType, "attributes", payloadAttributes));
        } catch (JacksonException failure) {
            throw new JiraAssetsProtocolException("Jira Assets mutation request encoding failed", failure);
        }
        URI uri = URI.create(rootUrl + (objectId == null ? "/object/create" : "/object/" + objectId));
        JiraAssetsTransport.Response response = execute(uri, method, body, true);
        requireWriteSuccessful(response, expectedStatus);
        return parseMutationObject(response.body());
    }

    private static Map<String, String> normalizeMutationAttributes(Map<String, String> attributes) {
        Objects.requireNonNull(attributes, "attributes");
        if (attributes.isEmpty() || attributes.size() > 64) {
            throw new IllegalArgumentException("Jira Assets mutation requires 1..64 attributes");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String id = providerId(entry.getKey(), "attribute id");
            String value = entry.getValue();
            if (value == null || !value.equals(value.strip()) || value.isEmpty() || value.length() > 4096
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Jira Assets mutation attribute value is invalid");
            }
            if (normalized.putIfAbsent(id, value) != null) {
                throw new IllegalArgumentException("duplicate Jira Assets mutation attribute id");
            }
        }
        return Map.copyOf(normalized);
    }

    private static String providerId(String value, String field) {
        if (value == null || !value.equals(value.strip()) || !value.matches("^[A-Za-z0-9-]{1,128}$")) {
            throw new IllegalArgumentException("Jira Assets " + field + " is invalid");
        }
        return value;
    }

    private static void requireWriteSuccessful(JiraAssetsTransport.Response response, int expectedStatus) {
        int status = response.statusCode();
        if (status == expectedStatus) return;
        if (status == 401 || status == 403) throw new JiraAssetsAuthenticationException();
        if (status == 429) throw new JiraAssetsRateLimitedException();
        if (status >= 500) throw new JiraAssetsUnavailableException("Jira Assets provider is unavailable");
        throw new JiraAssetsProtocolException("Jira Assets rejected the mutation request with HTTP " + status);
    }

    private RemoteMutationObject parseMutationObject(byte[] payload) {
        final JsonNode root;
        try {
            root = json.readTree(payload);
        } catch (JacksonException failure) {
            throw new JiraAssetsProtocolException("Jira Assets returned invalid mutation JSON", failure);
        }
        if (root == null || !root.isObject()) throw new JiraAssetsProtocolException("Jira Assets returned an invalid mutation object");
        return new RemoteMutationObject(requiredText(root, "id"));
    }

    private JiraAssetsTransport.Response execute(URI uri, String method, byte[] body, boolean jsonBody) {
        byte[] credential = secrets.resolve(settings.bearerTokenReference());
        try {
            String token = bearerToken(credential);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/json");
            headers.put("Authorization", "Bearer " + token);
            headers.put("User-Agent", "InfraNexum-JiraAssets/2");
            if (jsonBody) headers.put("Content-Type", "application/json");
            return transport.execute(new JiraAssetsTransport.Request(uri, method, headers, body, settings.requestTimeout()));
        } finally {
            Arrays.fill(credential, (byte) 0);
        }
    }

    private static String bearerToken(byte[] credential) {
        if (credential.length == 0) throw new JiraAssetsAuthenticationException();
        String token = new String(credential, StandardCharsets.UTF_8);
        if (!token.equals(token.strip()) || token.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw new JiraAssetsAuthenticationException();
        }
        return token;
    }

    private static void requireSuccessful(JiraAssetsTransport.Response response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) return;
        if (status == 401 || status == 403) throw new JiraAssetsAuthenticationException();
        if (status == 429) throw new JiraAssetsRateLimitedException();
        if (status >= 500) throw new JiraAssetsUnavailableException("Jira Assets provider is unavailable");
        throw new JiraAssetsProtocolException("Jira Assets rejected the read request with HTTP " + status);
    }

    private ObjectPage parsePage(byte[] payload, int requestedOffset, int requestedLimit) {
        final JsonNode root;
        try {
            root = json.readTree(payload);
        } catch (JacksonException failure) {
            throw new JiraAssetsProtocolException("Jira Assets returned invalid JSON", failure);
        }
        if (root == null || !root.isObject()) throw new JiraAssetsProtocolException("Jira Assets returned an invalid object page");
        int startAt = requiredBoundedInteger(root, "startAt", 0, MAX_OFFSET);
        int maxResults = requiredBoundedInteger(root, "maxResults", 1, MAX_LIMIT);
        int total = requiredBoundedInteger(root, "total", 0, Integer.MAX_VALUE);
        JsonNode values = root.get("values");
        if (values == null || !values.isArray() || values.size() > requestedLimit) {
            throw new JiraAssetsProtocolException("Jira Assets returned an invalid values page");
        }
        if (startAt != requestedOffset || maxResults > requestedLimit) {
            throw new JiraAssetsProtocolException("Jira Assets pagination response does not match the request bounds");
        }
        List<RemoteObject> items = new ArrayList<>(values.size());
        for (JsonNode value : values) items.add(parseObject(value));
        Integer nextOffset = startAt + items.size() < total ? startAt + items.size() : null;
        return new ObjectPage(List.copyOf(items), startAt, maxResults, total, nextOffset);
    }

    private static RemoteObject parseObject(JsonNode node) {
        if (node == null || !node.isObject()) throw new JiraAssetsProtocolException("Jira Assets returned an invalid object entry");
        String id = requiredText(node, "id");
        String globalId = requiredText(node, "globalId");
        String objectKey = requiredText(node, "objectKey");
        String label = requiredText(node, "label");
        JsonNode type = node.get("objectType");
        if (type == null || !type.isObject()) throw new JiraAssetsProtocolException("Jira Assets object type is missing");
        return new RemoteObject(id, globalId, objectKey, label, requiredText(type, "id"), requiredText(type, "name"));
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new JiraAssetsProtocolException("Jira Assets response field is missing: " + field);
        String text = value.textValue();
        if (text == null || text.isBlank() || text.length() > MAX_TEXT_LENGTH || text.chars().anyMatch(Character::isISOControl)) {
            throw new JiraAssetsProtocolException("Jira Assets response field is invalid: " + field);
        }
        return text;
    }

    private static int requiredBoundedInteger(JsonNode root, String field, int minimum, int maximum) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber()) throw new JiraAssetsProtocolException("Jira Assets response field is missing: " + field);
        long candidate = value.longValue();
        if (candidate < minimum || candidate > maximum) throw new JiraAssetsProtocolException("Jira Assets response field is out of bounds: " + field);
        return (int) candidate;
    }

    private static String normalizeAql(String aql) {
        if (aql == null) throw new IllegalArgumentException("aql is required");
        if (aql.length() > MAX_AQL_LENGTH || aql.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("aql must contain 1..4096 printable characters");
        }
        String normalized = aql.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("aql must contain 1..4096 printable characters");
        }
        return normalized;
    }

    private void requireEnabled() {
        if (!settings.enabled()) throw new JiraAssetsUnavailableException("Jira Assets connector is disabled");
    }

    /** Public health representation with governance metadata but no tenant identifiers or secret references. */
    public record Health(String connectorKey, String status, String provider, String direction, String authority) {}

    /** Minimized remote object projection; attributes remain at the provider until explicitly modeled later. */
    public record RemoteObject(String id, String globalId, String objectKey, String label, String objectTypeId, String objectTypeName) {}

    /** Minimized response from a successful create/update operation. */
    public record RemoteMutationObject(String id) {}

    /** Provider page preserving remote pagination while exposing a stable next-offset contract. */
    public record ObjectPage(List<RemoteObject> items, int startAt, int maxResults, int total, Integer nextOffset) {
        public ObjectPage { items = List.copyOf(Objects.requireNonNull(items, "items")); }
    }
}
