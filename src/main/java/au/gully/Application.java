package au.gully;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Gully: South Australia's weather from the Bureau's stations, each with the ground it speaks for
 * (docs/01-what-it-is.md).
 *
 * <p>No component-scan filters and no entity manager: every bean is an ordinary {@code @Component},
 * {@code @Service} or {@code @Configuration}, the configuration records are discovered by
 * {@code @ConfigurationProperties}, and the schema is Flyway's ({@code db/migration}).
 *
 * <p>Nothing is {@code @Scheduled}: the timers are set up by {@code au.gully.platform.Startup} once
 * the registers are back in memory.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("au.gully")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
