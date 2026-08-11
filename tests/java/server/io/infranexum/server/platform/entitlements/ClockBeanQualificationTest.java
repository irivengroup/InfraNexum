package io.infranexum.server.platform.entitlements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;

/** Regression coverage for explicit Clock ownership across Server bounded contexts. */
class ClockBeanQualificationTest {
    private static final Instant ENTITLEMENT_NOW = Instant.parse("2026-08-11T19:51:16Z");
    private static final Instant WORKER_NOW = Instant.parse("2026-08-11T20:51:16Z");

    @Test
    void entitlementHandlerResolvesItsClockWhenWorkerClockAlsoExists() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    "entitlementClock",
                    Clock.class,
                    () -> Clock.fixed(ENTITLEMENT_NOW, ZoneOffset.UTC));
            context.registerBean(
                    "workerClock",
                    Clock.class,
                    () -> Clock.fixed(WORKER_NOW, ZoneOffset.UTC));
            context.registerBean(EntitlementExceptionHandler.class);
            context.refresh();

            var handler = context.getBean(EntitlementExceptionHandler.class);
            var response = handler.handleUnavailable(
                    new io.infranexum.core.entitlements.EntitlementRuntimeUnavailableException(
                            "runtime unavailable"),
                    new MockHttpServletRequest("GET", "/api/v1/platform/evaluation/status"));

            assertEquals(ENTITLEMENT_NOW, response.getBody().occurred_at());
        }
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
