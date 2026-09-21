package au.gully.upstreams;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One day of the forecast, reduced to the shape of the day: how hot, how dry, how windy, whether
 * rain is coming. The hourly series carries the detail.
 *
 * @param date           the local date at the point, not a UTC day
 * @param minHumidityPct the day's driest hour, not the daily mean
 * @param maxWindKmh     the day's strongest mean wind
 */
public record DayOutlook(
        LocalDate date,
        Double maxTemperatureC,
        Double minTemperatureC,
        Double maxApparentTemperatureC,
        Integer minHumidityPct,
        Double maxWindKmh,
        Double maxGustKmh,
        Integer dominantWindDirectionDeg,
        Double precipitationMm,
        Integer precipitationProbabilityPct,
        Double uvIndexMax,
        Instant sunrise,
        Instant sunset,
        String condition
) {
}
