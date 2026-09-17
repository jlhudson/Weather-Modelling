package au.weather.terrain;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The one lever the terrain lookup has (D-147: the default lives here, not in {@code application.yml}).
 *
 * @param enabled whether heights are resolved at all. Turned off, every anchor comparison is horizontal
 *                and {@code WeatherCache} says so in its note — which is exactly the behaviour the
 *                vertical weight shipped at zero to reproduce, so this is a supported state rather than
 *                a degraded one
 */
@ConfigurationProperties(prefix = "weather.terrain")
public record TerrainProperties(@DefaultValue("true") boolean enabled) {
}
