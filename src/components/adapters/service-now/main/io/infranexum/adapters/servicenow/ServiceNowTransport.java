package io.infranexum.adapters.servicenow;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Narrow transport seam used to test ServiceNow protocol behavior without network access. */
@FunctionalInterface
public interface ServiceNowTransport {
    Response execute(Request request);

    /** HTTPS-only request restricted to one official ServiceNow SaaS hostname. */
    record Request(URI uri, String method, Map<String, String> headers, byte[] body, Duration timeout) {
        private static final int MAX_REQUEST_BYTES = 262_144;

        public Request {
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(method, "method");
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
            body = Arrays.copyOf(Objects.requireNonNull(body, "body"), body.length);
            Objects.requireNonNull(timeout, "timeout");
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getUserInfo() != null || uri.getPort() != -1
                    || !(host.endsWith(".service-now.com") && host.length() > ".service-now.com".length())) {
                throw new IllegalArgumentException("ServiceNow transport is restricted to https://*.service-now.com");
            }
            String path = uri.getPath();
            if (path == null || !path.startsWith("/api/now/table/") || path.length() <= "/api/now/table/".length()
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("ServiceNow transport is restricted to the Table API");
            }
            if (!(method.equals("GET") || method.equals("POST") || method.equals("PATCH"))) {
                throw new IllegalArgumentException("unsupported ServiceNow HTTP method");
            }
            if (method.equals("GET") && body.length != 0) {
                throw new IllegalArgumentException("ServiceNow GET requests cannot contain a body");
            }
            if (!method.equals("GET") && (body.length == 0 || body.length > MAX_REQUEST_BYTES)) {
                throw new IllegalArgumentException("ServiceNow mutation body must contain 1..262144 bytes");
            }
        }

        @Override public byte[] body() { return Arrays.copyOf(body, body.length); }
    }

    /** Fully buffered but strictly bounded HTTP response returned by the transport. */
    record Response(int statusCode, Map<String, List<String>> headers, byte[] body) {
        public Response {
            if (statusCode < 100 || statusCode > 599) throw new IllegalArgumentException("invalid HTTP status code");
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
            body = Arrays.copyOf(Objects.requireNonNull(body, "body"), body.length);
        }
        @Override public byte[] body() { return Arrays.copyOf(body, body.length); }
    }
}
