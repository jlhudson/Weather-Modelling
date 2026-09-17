package au.weather;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The weather service, and the only place wiring is declared.
 *
 * <p>One point in, the weather out (docs/01-what-it-does.md). The anchor cache, the provider chain and
 * its budget governor, the drought accumulators and the flood cells, lifted out of The Hub Database
 * under D-242 and D-249.
 *
 * <p>No component-scan filters: the Hub discovered sources and managers by their {@code @Source} and
 * {@code @Manager} declarations, and there is neither here — every bean is an ordinary
 * {@code @Component}, {@code @Service} or {@code @Configuration}. Configuration records are still
 * discovered by {@code @ConfigurationProperties} rather than listed on a config class.
 *
 * <p>Entities live with the service that owns them (D-106) and repositories are nested interfaces per
 * owner, hence {@code considerNestedRepositories}. Hibernate owns the DDL while the schema is still
 * growing (D-091, D-115); Flyway owns the changes it cannot make (D-206,
 * {@code src/main/resources/db/migration/README.md}).
 *
 * <p>Nothing is {@code @Scheduled}. The one timer this service has is set up by
 * {@code au.weather.startup.WeatherSweeper} on the {@code weatherTaskScheduler} bean, because it needs
 * an interval read from configuration and a start that waits for the rehydrate.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("au.weather")
@EnableJpaRepositories(basePackages = "au.weather", considerNestedRepositories = true)
public class WeatherApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeatherApplication.class, args);
    }
}
