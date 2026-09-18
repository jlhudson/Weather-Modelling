package au.gully.upstreams;

import au.gully.science.Conditions;
import au.gully.science.DayOutlook;

import java.time.Instant;
import java.util.List;

/**
 * One upstream's answer for one hexagon centre: the conditions now as its model sees them, the hourly
 * series and the daily outlook, in the shape every upstream is normalised into. This is what is held
 * on the hexagon and written to its row.
 *
 * @param attribution       the licence line the upstream requires; it travels to the API edge
 * @param currentExpiresAt  when the upstream's own "now" goes stale — the next quarter-hour for
 *                          Open-Meteo, which states the interval its current block covers
 * @param forecastExpiresAt when the series should be re-asked for: the model's update cadence
 * @param modelElevationM   the height of the model cell the upstream answered from, which explains
 *                          a reading; the hexagon's own elevation, from the terrain file, places it
 */
public record Forecast(
        String upstream,
        String model,
        String attribution,
        Instant fetchedAt,
        Instant currentExpiresAt,
        Instant forecastExpiresAt,
        Double modelElevationM,
        String zoneId,
        Conditions current,
        List<Conditions> hourly,
        List<DayOutlook> daily
) {

    public Forecast {
        hourly = hourly == null ? List.of() : List.copyOf(hourly);
        daily = daily == null ? List.of() : List.copyOf(daily);
    }

    public boolean currentExpired(Instant now) {
        return currentExpiresAt == null || !now.isBefore(currentExpiresAt);
    }

    public boolean forecastExpired(Instant now) {
        return forecastExpiresAt == null || !now.isBefore(forecastExpiresAt);
    }
}
