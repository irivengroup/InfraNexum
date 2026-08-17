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

    /** HTTPS-only GET request restricted to the official ServiceNow SaaS domain. */
    record Request(URI uri, String method, Map<String, String> headers, byte[] body, Duration timeout) {
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
            if (!method.equals("GET") || body.length != 0) {
                throw new IllegalArgumentException("ServiceNow federated-read transport supports GET without a body only");
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
