package io.infranexum.adapters.servicenow;

import io.infranexum.integrations.ConnectorSecretProvider;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Governed ServiceNow CMDB federated-read connector for PGM-10-E06 phase 2. */
public final class ServiceNowConnector {
    private static final int MAX_SEARCH_LENGTH = 256;
    private static final int MAX_TEXT_LENGTH = 1_024;
    private static final int MAX_OFFSET = 1_000_000;
    private static final int MAX_LIMIT = 200;
    private static final Pattern SAFE_SEARCH_TERM = Pattern.compile("[A-Za-z0-9 _./:-]{1,256}");
    private static final Pattern SYS_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern CLASS_NAME = Pattern.compile("cmdb_ci(?:_[a-z0-9_]{1,56})?");
    private static final byte[] EMPTY_BODY = new byte[0];
    private static final String FIELDS = "sys_id,name,sys_class_name,sys_updated_on";

    private final ServiceNowSettings settings;
    private final ServiceNowTransport transport;
    private final ConnectorSecretProvider secrets;
    private final ObjectMapper json;
    private final String rootUrl;

    public ServiceNowConnector(
            ServiceNowSettings settings,
            ServiceNowTransport transport,
            ConnectorSecretProvider secrets,
            ObjectMapper json) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.json = Objects.requireNonNull(json, "json");
        this.rootUrl = "https://" + settings.instanceHost() + "/api/now/table/" + settings.tableName();
    }

    public ServiceNowSettings settings() { return settings; }

    /** Verifies read access against the configured CMDB table without requiring broader platform privileges. */
    public Health health() {
        requireEnabled();
        URI uri = tableUri("sys_idISNOTEMPTY^ORDERBYsys_id", 0, 1, "sys_id");
        ServiceNowTransport.Response response = execute(uri);
        requireSuccessful(response);
        parseResultArray(response.body(), 1);
        return new Health(settings.connectorKey().value(), "UP", ServiceNowSettings.PROVIDER,
                ServiceNowSettings.DIRECTION, ServiceNowSettings.AUTHORITY);
    }

    /** Searches CMDB CI names using a constrained term; arbitrary encoded queries never cross this boundary. */
    public ConfigurationItemPage search(String term, int offset, int limit) {
        requireEnabled();
        String normalized = normalizeSearchTerm(term);
        if (offset < 0 || offset > MAX_OFFSET) throw new IllegalArgumentException("offset must be between 0 and 1000000");
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be between 1 and 200");
        String encodedQuery = "nameLIKE" + normalized + "^ORDERBYsys_id";
        ServiceNowTransport.Response response = execute(tableUri(encodedQuery, offset, limit, FIELDS));
        requireSuccessful(response);
        List<RemoteConfigurationItem> items = parseItems(response.body(), limit);
        Integer nextOffset = items.size() == limit && offset <= MAX_OFFSET - items.size()
                ? offset + items.size() : null;
        return new ConfigurationItemPage(items, offset, limit, nextOffset);
    }

    private URI tableUri(String query, int offset, int limit, String fields) {
        String parameters = "sysparm_query=" + encode(query)
                + "&sysparm_fields=" + encode(fields)
                + "&sysparm_limit=" + limit
                + "&sysparm_offset=" + offset
                + "&sysparm_exclude_reference_link=true"
                + "&sysparm_display_value=false"
                + "&sysparm_no_count=true";
        return URI.create(rootUrl + "?" + parameters);
    }

    private ServiceNowTransport.Response execute(URI uri) {
        byte[] credential = secrets.resolve(settings.bearerTokenReference());
        try {
            String token = bearerToken(credential);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/json");
            headers.put("Authorization", "Bearer " + token);
            headers.put("User-Agent", "InfraNexum-ServiceNow/2");
            return transport.execute(new ServiceNowTransport.Request(uri, "GET", headers, EMPTY_BODY, settings.requestTimeout()));
        } finally {
            Arrays.fill(credential, (byte) 0);
        }
    }

    private static String bearerToken(byte[] credential) {
        if (credential.length == 0) throw new ServiceNowAuthenticationException();
        String token = new String(credential, StandardCharsets.UTF_8);
        if (!token.equals(token.strip()) || token.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw new ServiceNowAuthenticationException();
        }
        return token;
    }

    private static void requireSuccessful(ServiceNowTransport.Response response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) return;
        if (status == 401 || status == 403) throw new ServiceNowAuthenticationException();
        if (status == 429) throw new ServiceNowRateLimitedException();
        if (status >= 500) throw new ServiceNowUnavailableException("ServiceNow provider is unavailable");
        throw new ServiceNowProtocolException("ServiceNow rejected the read request with HTTP " + status);
    }

    private List<RemoteConfigurationItem> parseItems(byte[] payload, int requestedLimit) {
        JsonNode values = parseResultArray(payload, requestedLimit);
        List<RemoteConfigurationItem> items = new ArrayList<>(values.size());
        for (JsonNode value : values) items.add(parseItem(value));
        return List.copyOf(items);
    }

    private JsonNode parseResultArray(byte[] payload, int requestedLimit) {
        final JsonNode root;
        try {
            root = json.readTree(payload);
        } catch (JacksonException failure) {
            throw new ServiceNowProtocolException("ServiceNow returned invalid JSON", failure);
        }
        if (root == null || !root.isObject()) throw new ServiceNowProtocolException("ServiceNow returned an invalid response envelope");
        JsonNode result = root.get("result");
        if (result == null || !result.isArray() || result.size() > requestedLimit) {
            throw new ServiceNowProtocolException("ServiceNow returned an invalid result page");
        }
        return result;
    }

    private static RemoteConfigurationItem parseItem(JsonNode node) {
        if (node == null || !node.isObject()) throw new ServiceNowProtocolException("ServiceNow returned an invalid configuration item");
        String sysId = requiredText(node, "sys_id");
        if (!SYS_ID.matcher(sysId).matches()) throw new ServiceNowProtocolException("ServiceNow sys_id is invalid");
        String className = requiredText(node, "sys_class_name");
        if (!CLASS_NAME.matcher(className).matches()) throw new ServiceNowProtocolException("ServiceNow sys_class_name is outside CMDB CI scope");
        return new RemoteConfigurationItem(sysId, optionalText(node, "name"), className, optionalText(node, "sys_updated_on"));
    }

    private static String requiredText(JsonNode node, String field) {
        String text = optionalText(node, field);
        if (text.isBlank()) throw new ServiceNowProtocolException("ServiceNow response field is missing: " + field);
        return text;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual()) throw new ServiceNowProtocolException("ServiceNow response field is invalid: " + field);
        String text = value.textValue();
        if (text == null || text.length() > MAX_TEXT_LENGTH || text.chars().anyMatch(Character::isISOControl)) {
            throw new ServiceNowProtocolException("ServiceNow response field is invalid: " + field);
        }
        return text;
    }

    private static String normalizeSearchTerm(String term) {
        if (term == null) throw new IllegalArgumentException("query is required");
        String normalized = term.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("query must contain 1..256 characters");
        }
        if (!SAFE_SEARCH_TERM.matcher(normalized).matches()) {
            throw new IllegalArgumentException("query contains a character not allowed by the governed ServiceNow search boundary");
        }
        return normalized;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void requireEnabled() {
        if (!settings.enabled()) throw new ServiceNowUnavailableException("ServiceNow connector is disabled");
    }

    /** Public health representation with governance metadata but no tenant or credential details. */
    public record Health(String connectorKey, String status, String provider, String direction, String authority) {}

    /** Minimized CMDB projection; provider-specific attributes remain remote until explicitly modeled. */
    public record RemoteConfigurationItem(String sysId, String name, String className, String updatedOn) {}

    /** Stable offset page independent of ServiceNow response-link formatting. */
    public record ConfigurationItemPage(List<RemoteConfigurationItem> items, int offset, int limit, Integer nextOffset) {
        public ConfigurationItemPage { items = List.copyOf(Objects.requireNonNull(items, "items")); }
    }
}
