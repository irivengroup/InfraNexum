package io.infranexum.server.platform.entitlements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import io.infranexum.server.observability.SensitiveDataRedactor;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletRequest;

/** Regression coverage for explicit Clock ownership across Server bounded contexts. */
class ClockBeanQualificationTest {
    private static final Instant ENTITLEMENT_NOW = Instant.parse("2026-08-11T19:51:16Z");
    private static final Instant WORKER_NOW = Instant.parse("2026-08-11T20:51:16Z");

    @Test
    void entitlementHandlerUsesPlatformProblemClockWhenDomainClocksDiffer() {
        Instant platformNow = Instant.parse("2026-08-11T18:51:16Z");
        var problems = new io.infranexum.server.http.ApiProblemSupport(
                Clock.fixed(platformNow, ZoneOffset.UTC),
                new SensitiveDataRedactor(),
                new tools.jackson.databind.ObjectMapper());
        var handler = new EntitlementExceptionHandler(problems);
        var response = handler.handleUnavailable(
                new io.infranexum.core.entitlements.EntitlementRuntimeUnavailableException(
                        "runtime unavailable"),
                new MockHttpServletRequest("GET", "/api/v1/platform/evaluation/status"));

        assertEquals(platformNow.toString(), response.getBody().occurred_at());
        assertEquals(ENTITLEMENT_NOW, Clock.fixed(ENTITLEMENT_NOW, ZoneOffset.UTC).instant());
        assertEquals(WORKER_NOW, Clock.fixed(WORKER_NOW, ZoneOffset.UTC).instant());
    }

    @Test
    void everyEntitlementRuntimeClockParameterIsExplicitlyQualified() {
        assertClockQualifier("entitlementRuntimeAuthority");
        assertClockQualifier("activationImportCoordinator");
    }

    private static void assertClockQualifier(String methodName) {
        Method method = Arrays.stream(ActivationRuntimeConfiguration.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        Parameter parameter = Arrays.stream(method.getParameters())
                .filter(candidate -> candidate.getType() == Clock.class)
                .findFirst()
                .orElseThrow();
        Qualifier qualifier = parameter.getAnnotation(Qualifier.class);
        assertNotNull(qualifier, methodName + " Clock must declare @Qualifier");
        assertEquals("entitlementClock", qualifier.value());
    }
}
