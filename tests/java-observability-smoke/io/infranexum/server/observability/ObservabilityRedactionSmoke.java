package io.infranexum.server.observability;

/** Offline regression for the pure-JDK observability redaction policy. */
public final class ObservabilityRedactionSmoke {
    private ObservabilityRedactionSmoke() {}

    public static void main(String[] args) {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor();

        requireRedacted(redactor.redact("spring.datasource.password", "s3cr3t"), "s3cr3t");
        requireRedacted(redactor.redact("message", "Authorization: Bearer abc.def.ghi"), "abc.def.ghi");
        requireRedacted(redactor.redact("message", "Cookie: sid=very-secret; mode=admin"), "very-secret");
        requireRedacted(redactor.redact("message", "password=supersecret"), "supersecret");
        requireRedacted(redactor.redact("message", "client_secret: abc-123"), "abc-123");
        requireRedacted(redactor.redact("message", "postgresql://admin:p455@db.internal/inx"), "p455");
        requireRedacted(redactor.redact(
                        "message",
                        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature"),
                "eyJhbGciOiJIUzI1NiJ9");
        requireRedacted(redactor.redact(
                        "error.stack_trace",
                        "java.lang.IllegalStateException: api_key=key-123\\n at Example.run(Example.java:1)"),
                "key-123");
        requireRedacted(redactor.redact(
                        "message",
                        "-----BEGIN " + "PRIVATE" + " KEY-----\\nabc123\\n-----END " + "PRIVATE" + " KEY-----"),
                "abc123");

        String safe = "installation=018bcfe5-6800-7001-8000-000000000001 region=eu-west";
        if (!safe.equals(redactor.redact("message", safe))) {
            throw new AssertionError("safe observability value was modified");
        }
    }

    private static void requireRedacted(String value, String forbidden) {
        if (value.contains(forbidden) || !value.contains(SensitiveDataRedactor.REDACTED)) {
            throw new AssertionError("sensitive value was not redacted: " + value);
        }
    }
}
