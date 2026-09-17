package au.weather.service;

import au.weather.http.HttpFetcher;
import org.springframework.stereotype.Component;

/**
 * The Bureau of Meteorology ACCESS-G model, served by Open-Meteo at a second path. The same code, the
 * same parser, a different endpoint and a narrower variable set — so it is the same class with a
 * different {@link WeatherProvider.Spec} rather than a copy of it.
 *
 * <p>It ships out of {@code weather.order}: see {@link OpenMeteoProvider#BOM} for why.
 */
@Component
public class OpenMeteoBomProvider extends OpenMeteoProvider {

    public OpenMeteoBomProvider(WeatherProperties properties, HttpFetcher fetcher) {
        super(BOM, properties, fetcher);
    }
}
