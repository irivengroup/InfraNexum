package io.infranexum.server.rsot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Prevents durable RSOT HTTP boundaries from breaking the intentionally in-memory test runtime. */
class RsotBoundaryConditionTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Boundaries.class);

    @Test
    void memoryPersistenceDoesNotComposeDurableRsotControllers() {
        contextRunner.withPropertyValues("infranexum.persistence.mode=MEMORY").run(context -> {
            assertTrue(context.getBeansOfType(RsotObjectController.class).isEmpty());
            assertTrue(context.getBeansOfType(RsotSchemaController.class).isEmpty());
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({RsotObjectController.class, RsotSchemaController.class})
    static class Boundaries {}
}
