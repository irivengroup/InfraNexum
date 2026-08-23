package io.infranexum.adapters.servicenow;

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

class JdkServiceNowTransportTest {
    private static ServiceNowTransport.Request request() {
        return new ServiceNowTransport.Request(URI.create("https://tenant.service-now.com/api/now/table/cmdb_ci"),
                "GET", Map.of("Accept", "application/json"), new byte[0], Duration.ofSeconds(2));
    }

    @Test
    void sendsGetAndBuffersWithinConfiguredLimit() {
        HttpRequest[] sent = new HttpRequest[1];
        JdkServiceNowTransport transport = new JdkServiceNowTransport(req -> { sent[0] = req; return response(207, "abc".getBytes()); }, 3);
        ServiceNowTransport.Response result = transport.execute(request());
        assertEquals(207, result.statusCode());
        assertArrayEquals("abc".getBytes(), result.body());
        assertEquals("GET", sent[0].method());
        assertEquals(Duration.ofSeconds(2), sent[0].timeout().orElseThrow());
    }


    @Test
    void sendsGovernedMutationMethodsWithExactBodies() {
        java.util.List<HttpRequest> sent = new java.util.ArrayList<>();
        JdkServiceNowTransport transport = new JdkServiceNowTransport(req -> {
            sent.add(req);
            return response(200, "{}".getBytes());
        }, 32);
        byte[] body = "{\"u_name\":\"server\"}".getBytes();
        for (String method : List.of("POST", "PATCH")) {
            ServiceNowTransport.Request request = new ServiceNowTransport.Request(
                    URI.create("https://tenant.service-now.com/api/now/table/cmdb_ci"),
                    method, Map.of("Content-Type", "application/json"), body, Duration.ofSeconds(2));
            transport.execute(request);
        }
        assertEquals(List.of("POST", "PATCH"), sent.stream().map(HttpRequest::method).toList());
    }

    @Test
    void rejectsOversizedResponsesAndMapsTransportFailures() {
        assertThrows(ServiceNowProtocolException.class, () -> new JdkServiceNowTransport(req -> response(200, new byte[] {1,2}), 1).execute(request()));
        assertThrows(ServiceNowUnavailableException.class, () -> new JdkServiceNowTransport(req -> { throw new IOException("network"); }, 10).execute(request()));
        JdkServiceNowTransport interrupted = new JdkServiceNowTransport(req -> { throw new InterruptedException("stop"); }, 10);
        assertThrows(ServiceNowUnavailableException.class, () -> interrupted.execute(request()));
        assertTrue(Thread.interrupted());
        assertThrows(NullPointerException.class, () -> interrupted.execute(null));
        assertThrows(IllegalArgumentException.class, () -> new JdkServiceNowTransport(req -> response(200, new byte[0]), 0));
        assertThrows(IllegalArgumentException.class, () -> new JdkServiceNowTransport(req -> response(200, new byte[0]), 8_388_609));
    }

    @Test
    void publicConstructorRequiresRedirectRefusal() {
        assertDoesNotThrow(() -> new JdkServiceNowTransport(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), JdkServiceNowTransport.DEFAULT_MAX_RESPONSE_BYTES));
        assertThrows(IllegalArgumentException.class, () -> new JdkServiceNowTransport(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(), 1));
        assertThrows(NullPointerException.class, () -> new JdkServiceNowTransport((HttpClient) null, 1));
    }

    private static HttpResponse<InputStream> response(int status, byte[] body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return HttpRequest.newBuilder(URI.create("https://tenant.service-now.com/test")).build(); }
            @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (a,b) -> true); }
            @Override public InputStream body() { return new ByteArrayInputStream(body); }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create("https://tenant.service-now.com/test"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        };
    }
}
