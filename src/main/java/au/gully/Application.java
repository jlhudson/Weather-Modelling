package au.gully;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Gully: the weather, and the fire danger, at a point, now or at a time (docs/01-what-it-does.md).
 *
 * <p>No component-scan filters and no entity manager: every bean is an ordinary {@code @Component},
 * {@code @Service} or {@code @Configuration}, the configuration records are discovered by
 * {@code @ConfigurationProperties}, and the schema is Flyway's ({@code db/migration}).
 *
 * <p>Nothing is {@code @Scheduled}. The timers are set up by {@code au.gully.platform.Timers} once
 * the store has been rebuilt, because the first poll has to find the hexagons already in memory.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("au.gully")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
