package io.infranexum.adapters.jiraassets;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.integrations.ConnectorKey;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JiraAssetsSettingsAndTransportTest {
    private static final ConnectorKey KEY = new ConnectorKey("jira-assets.test");

    @Test
    void settingsExposeFixedGovernanceAndRejectUnsafeConfiguration() {
        JiraAssetsSettings settings = new JiraAssetsSettings(
                KEY, "cloud-123", "workspace-456", "file:/run/secrets/jira", Duration.ofSeconds(15), true);
        assertEquals("jira-assets", JiraAssetsSettings.PROVIDER);
        assertEquals("FEDERATED_READ", JiraAssetsSettings.DIRECTION);
        assertEquals("EXTERNAL", JiraAssetsSettings.AUTHORITY);
        assertEquals("cloud-123", settings.cloudId());
        assertTrue(settings.enabled());

        assertThrows(NullPointerException.class,
                () -> new JiraAssetsSettings(null, "cloud", "workspace", "env:JIRA_TOKEN", Duration.ofSeconds(1), true));
        assertThrows(ConfigurationException.class,
                () -> new JiraAssetsSettings(KEY, null, "workspace", "env:JIRA_TOKEN", Duration.ofSeconds(1), true));
        assertThrows(ConfigurationException.class,
                () -> new JiraAssetsSettings(KEY, " cloud", "workspace", "env:JIRA_TOKEN", Duration.ofSeconds(1), true));
        assertThrows(ConfigurationException.class,
                () -> new JiraAssetsSettings(KEY, "cloud/unsafe", "workspace", "env:JIRA_TOKEN", Duration.ofSeconds(1), true));
        assertThrows(ConfigurationException.class,
                () -> new JiraAssetsSettings(KEY, "cloud", "", "env:JIRA_TOKEN", Duration.ofSeconds(1), true));
        assertThrows(ConfigurationException.class,
                () -> new JiraAssetsSettings(KEY, "cloud", "workspace", "inline:secret", Duration.ofSeconds(1), true));
        assertThrows(ConfigurationException.class,
                () -> new JiraAssetsSettings(KEY, "cloud", "workspace", null, Duration.ofSeconds(1), true));
        assertThrows(ConfigurationException.class,
                () -> new JiraAssetsSettings(KEY, "cloud", "workspace", "env:JIRA_TOKEN", null, true));
        assertThrows(ConfigurationException.class,
                () -> new JiraAssetsSettings(KEY, "cloud", "workspace", "env:JIRA_TOKEN", Duration.ZERO, true));
        assertThrows(ConfigurationException.class,
                () -> new JiraAssetsSettings(KEY, "cloud", "workspace", "env:JIRA_TOKEN", Duration.ofSeconds(-1), true));
        assertThrows(ConfigurationException.class,
                () -> new JiraAssetsSettings(KEY, "cloud", "workspace", "env:JIRA_TOKEN", Duration.ofSeconds(61), true));
        assertDoesNotThrow(() -> new JiraAssetsSettings(
                KEY, "A".repeat(128), "9".repeat(128), "env:JIRA_TOKEN", Duration.ofSeconds(60), false));
    }

    @Test
    void requestAndResponseAreDefensiveAndTransportIsHostPinned() {
        byte[] body = {1, 2};
        JiraAssetsTransport.Request request = new JiraAssetsTransport.Request(
                URI.create("https://api.atlassian.com/path"), "POST", Map.of("Accept", "application/json"), body,
                Duration.ofSeconds(2));
        body[0] = 9;
        assertArrayEquals(new byte[] {1, 2}, request.body());
        byte[] copy = request.body();
        copy[1] = 9;
        assertArrayEquals(new byte[] {1, 2}, request.body());
        assertDoesNotThrow(() -> new JiraAssetsTransport.Request(
                URI.create("https://api.atlassian.com/path"), "PUT", Map.of(), new byte[] {5}, Duration.ofSeconds(1)));

        byte[] responseBody = {3, 4};
        JiraAssetsTransport.Response response = new JiraAssetsTransport.Response(200, Map.of("x", List.of("y")), responseBody);
        responseBody[0] = 9;
        assertArrayEquals(new byte[] {3, 4}, response.body());
        assertThrows(IllegalArgumentException.class,
                () -> new JiraAssetsTransport.Request(URI.create("http://api.atlassian.com/path"), "GET", Map.of(), new byte[0], Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new JiraAssetsTransport.Request(URI.create("https://example.invalid/path"), "GET", Map.of(), new byte[0], Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new JiraAssetsTransport.Request(URI.create("/relative"), "GET", Map.of(), new byte[0], Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new JiraAssetsTransport.Request(URI.create("https://api.atlassian.com/path"), "DELETE", Map.of(), new byte[0], Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new JiraAssetsTransport.Response(99, Map.of(), new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new JiraAssetsTransport.Response(600, Map.of(), new byte[0]));
        assertThrows(NullPointerException.class,
                () -> new JiraAssetsTransport.Request(null, "GET", Map.of(), new byte[0], Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class,
                () -> new JiraAssetsTransport.Response(200, null, new byte[0]));
    }
}
