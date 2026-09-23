package au.gully.upstreams;

import java.time.Instant;
import java.util.List;

/**
 * One upstream's answer for one point: the conditions now as its model saw them when it was
 * fetched, the hourly series and the daily outlook, in the shape every upstream is normalised into.
 *
 * @param attribution     the licence line the upstream requires
 * @param fetchedAt       when it was fetched
 * @param modelElevationM the height of the model cell the upstream answered from, which explains
 *                        a reading
 * @param current         the model's own "now" at the moment of the fetch
 */
public record Forecast(
        String upstream,
        String model,
        String attribution,
        Instant fetchedAt,
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
}
