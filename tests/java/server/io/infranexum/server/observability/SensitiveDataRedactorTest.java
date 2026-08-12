package io.infranexum.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SensitiveDataRedactorTest {
    private final SensitiveDataRedactor redactor = new SensitiveDataRedactor();

    @Test
    void redactsSensitiveStructuredFieldsCompletely() {
        assertEquals(SensitiveDataRedactor.REDACTED, redactor.redact("spring.datasource.password", "secret-value"));
        assertEquals(SensitiveDataRedactor.REDACTED, redactor.redact("labels.api_key", "secret-value"));
        assertEquals(SensitiveDataRedactor.REDACTED, redactor.redact("mdc.Authorization", "Bearer token-value"));
    }

    @Test
    void redactsCredentialEncodingsInsideMessagesAndStackTraces() {
        String input = "Authorization: Bearer bearer-value\n"
                + "password=pwd-value client_secret=client-value\n"
                + "jdbc=postgresql://infra:p455@db.internal/inx\n"
                + "jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature\n"
                + "-----BEGIN " + "PRIVATE" + " KEY-----\nprivate-material\n-----END " + "PRIVATE" + " KEY-----";

        String output = redactor.redact("error.stack_trace", input);

        for (String forbidden : new String[] {
            "bearer-value", "pwd-value", "client-value", "p455", "eyJhbGciOiJIUzI1NiJ9", "private-material"
        }) {
            assertFalse(output.contains(forbidden), forbidden);
        }
        assertTrue(output.contains(SensitiveDataRedactor.REDACTED));
    }

    @Test
    void leavesCanonicalOperationalIdentifiersUntouched() {
        String safe = "correlation=018bcfe5-6800-7001-8000-000000000001 type=inventory.discovery region=eu-west";
        assertEquals(safe, redactor.redact("message", safe));
    }
}
