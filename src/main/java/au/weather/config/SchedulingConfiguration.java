package au.weather.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

/**
 * The one scheduler under the weather sweep (docs/14-platform.md: not {@code @Scheduled}, because that
 * needs a per-deployment interval, and the first run has to wait for the rehydrate).
 *
 * <p>Virtual threads: a sweep that blocks on an elevation lookup or a drought spin-up costs nothing
 * parked.
 */
@Configuration
public class SchedulingConfiguration {

    @Bean
    public TaskScheduler weatherTaskScheduler() {
        SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler();
        scheduler.setVirtualThreads(true);
        scheduler.setThreadNamePrefix("weather-");
        return scheduler;
    }
}
