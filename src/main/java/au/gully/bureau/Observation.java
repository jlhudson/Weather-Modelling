package au.gully.bureau;

import java.time.Instant;

/**
 * A station's latest values, exactly as published: not an estimate, a prediction or a reading of a
 * different grade, but the values. Every field is nullable, because a station that
 * does not report humidity reports no humidity rather than a zero.
 *
 * @param at              the observation time, which the Bureau states in UTC
 * @param rainSince9amMm  the running total since 9 am local
 * @param rain24hMm       the day's total to 9 am local
 * @param maxTemperatureC the running maximum since 6 am local
 * @param cloud           the Bureau's word for the sky, verbatim
 */
public record Observation(
        String stationId,
        Instant at,
        Double temperatureC,
        Double apparentTemperatureC,
        Double dewPointC,
        Integer humidityPct,
        Double windSpeedKmh,
        Integer windDirectionDeg,
        String windDirection,
        Double windGustKmh,
        Double pressureMslHpa,
        Double rainSince9amMm,
        Double rain24hMm,
        Double maxTemperatureC,
        Double minTemperatureC,
        Double visibilityKm,
        String cloud,
        Integer cloudOktas,
        Double deltaTC
) {
}
