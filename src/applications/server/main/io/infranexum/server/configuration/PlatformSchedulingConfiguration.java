package io.infranexum.server.configuration;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Owns the scheduler used by Spring {@code @Scheduled} processing.
 *
 * <p>Defining the canonical {@code taskScheduler} bean prevents Spring from silently falling back
 * to its local single-thread executor. The pool is bounded, uses the canonical platform clock and
 * participates in the Spring lifecycle so shutdown remains explicit and time-bounded.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchedulingRuntimeProperties.class)
public class PlatformSchedulingConfiguration {

    @Bean("taskScheduler")
    ThreadPoolTaskScheduler taskScheduler(
            SchedulingRuntimeProperties properties,
            @Qualifier("platformClock") Clock platformClock) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.poolSize());
        scheduler.setThreadNamePrefix("infranexum-scheduled-");
        scheduler.setClock(platformClock);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationMillis(properties.shutdownTimeout().toMillis());
        return scheduler;
    }
}
