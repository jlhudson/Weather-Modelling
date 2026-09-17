package au.weather.core;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One day of the forecast, reduced to what an incident actually turns on: how hot, how dry, how
 * windy, and whether rain is coming. The hourly series carries the detail; this carries the shape of
 * the day, which is what a duty officer reads first.
 *
 * @param date           the local date at the point, not a UTC day
 * @param minHumidityPct the day's driest hour, which is when a fire runs, not the daily mean
 * @param maxWindKmh     the day's strongest mean wind, for the same reason
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
