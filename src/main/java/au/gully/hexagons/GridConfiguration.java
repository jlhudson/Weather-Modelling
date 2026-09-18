package au.gully.hexagons;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The reading's grid, as a bean: the default 15 km hexagons. A test stands a different one in.
 */
@Configuration
public class GridConfiguration {

    @Bean
    public Grid grid() {
        return Grid.DEFAULT;
    }
}
