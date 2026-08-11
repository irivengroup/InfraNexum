package io.infranexum.server.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Regression coverage for the explicit Spring scheduler used by {@code @Scheduled}. */
class PlatformSchedulingConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues(
                    "infranexum.scheduling.pool-size=2",
                    "infranexum.scheduling.shutdown-timeout=PT10S")
            .withUserConfiguration(
                    PlatformClockConfiguration.class,
                    PlatformSchedulingConfiguration.class,
                    SchedulingFixture.class);

    @Test
    void scheduledProcessingUsesCanonicalManagedTaskScheduler() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            TaskScheduler byType = context.getBean(TaskScheduler.class);
            ThreadPoolTaskScheduler scheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);

            assertSame(scheduler, byType);
            assertSame(context.getBean("platformClock"), scheduler.getClock());
            assertEquals(2, scheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
            assertTrue(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy());
            assertFalse(scheduler.getScheduledThreadPoolExecutor().getContinueExistingPeriodicTasksAfterShutdownPolicy());
            assertFalse(scheduler.getScheduledThreadPoolExecutor().getExecuteExistingDelayedTasksAfterShutdownPolicy());
            assertTrue(scheduler.getThreadNamePrefix().startsWith("infranexum-scheduled-"));
            assertNotNull(context.getBean(SchedulingProbe.class));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingFixture {
        @Bean
        SchedulingProbe schedulingProbe() {
            return new SchedulingProbe();
        }
    }

    static final class SchedulingProbe {
        private final AtomicInteger invocations = new AtomicInteger();

        @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
        void tick() {
            invocations.incrementAndGet();
        }
    }
}
