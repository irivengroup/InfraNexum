package io.infranexum.adapters.jiraassets;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class JdkJiraAssetsTransportTest {
    private static JiraAssetsTransport.Request request(String method, byte[] body) {
        return new JiraAssetsTransport.Request(
                URI.create("https://api.atlassian.com/test"), method, Map.of("Accept", "application/json"), body,
                Duration.ofSeconds(2));
    }

    @Test
    void sendsGetPostAndPutAndBuffersWithinConfiguredLimit() {
        HttpRequest[] sent = new HttpRequest[1];
        JdkJiraAssetsTransport transport = new JdkJiraAssetsTransport(req -> {
            sent[0] = req;
            return response(207, "abc".getBytes());
        }, 3);
        JiraAssetsTransport.Response result = transport.execute(request("GET", new byte[0]));
        assertEquals(207, result.statusCode());
        assertArrayEquals("abc".getBytes(), result.body());
        assertEquals("GET", sent[0].method());
        assertEquals(Duration.ofSeconds(2), sent[0].timeout().orElseThrow());

        JdkJiraAssetsTransport post = new JdkJiraAssetsTransport(req -> {
            sent[0] = req;
            return response(200, new byte[0]);
        }, 10);
        post.execute(request("POST", new byte[] {1, 2, 3}));
        assertEquals("POST", sent[0].method());
        assertTrue(sent[0].bodyPublisher().isPresent());
        post.execute(request("PUT", new byte[] {4, 5}));
        assertEquals("PUT", sent[0].method());
        assertTrue(sent[0].bodyPublisher().isPresent());
    }

    @Test
    void rejectsOversizedResponsesAndMapsTransportFailures() {
        JdkJiraAssetsTransport oversized = new JdkJiraAssetsTransport(req -> response(200, new byte[] {1, 2}), 1);
        assertThrows(JiraAssetsProtocolException.class, () -> oversized.execute(request("GET", new byte[0])));

        JdkJiraAssetsTransport io = new JdkJiraAssetsTransport(req -> { throw new IOException("network"); }, 10);
        assertThrows(JiraAssetsUnavailableException.class, () -> io.execute(request("GET", new byte[0])));

        JdkJiraAssetsTransport interrupted = new JdkJiraAssetsTransport(req -> { throw new InterruptedException("stop"); }, 10);
        assertThrows(JiraAssetsUnavailableException.class, () -> interrupted.execute(request("GET", new byte[0])));
        assertTrue(Thread.interrupted(), "execute must restore interrupted status");
        assertThrows(NullPointerException.class, () -> interrupted.execute(null));
        assertThrows(IllegalArgumentException.class, () -> new JdkJiraAssetsTransport(req -> response(200, new byte[0]), 0));
        assertThrows(IllegalArgumentException.class, () -> new JdkJiraAssetsTransport(req -> response(200, new byte[0]), 8_388_609));
    }

    @Test
    void publicConstructorRequiresRedirectRefusal() {
        HttpClient safe = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        assertDoesNotThrow(() -> new JdkJiraAssetsTransport(safe, JdkJiraAssetsTransport.DEFAULT_MAX_RESPONSE_BYTES));
        HttpClient unsafe = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        assertThrows(IllegalArgumentException.class, () -> new JdkJiraAssetsTransport(unsafe, 1));
        assertThrows(NullPointerException.class, () -> new JdkJiraAssetsTransport((HttpClient) null, 1));
    }

    private static HttpResponse<InputStream> response(int status, byte[] body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return HttpRequest.newBuilder(URI.create("https://api.atlassian.com/test")).build(); }
            @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (a,b) -> true); }
            @Override public InputStream body() { return new ByteArrayInputStream(body); }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create("https://api.atlassian.com/test"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        };
    }
}
