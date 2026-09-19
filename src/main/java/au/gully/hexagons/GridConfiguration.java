package au.gully.hexagons;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The reading's grid, as a bean: the default 32 km hexagons from the anchor. A test stands a different one in.
 */
@Configuration
public class GridConfiguration {

    @Bean
    public Grid grid() {
        return Grid.DEFAULT;
    }
}
