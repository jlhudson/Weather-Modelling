package au.weather.core;

import java.time.Instant;
import java.util.List;

/**
 * One provider's answer for one point: current conditions, the hourly series, and the daily outlook,
 * in the shape every provider is normalised into.
 * <p>
 * This is the payload that gets cached, so it holds nothing about <em>how</em> it was served. Whether
 * a caller got it from the network or from an anchor 12 km away belongs to {@link WeatherAnswer},
 * because a stored report that claimed "fresh" would still claim it an hour later.
 *
 * @param attribution the licence line the provider requires; it travels with the data to the API edge
 * @param expiresAt   the provider's own opinion of when to ask again, where it states one; the cache
 *                    never keeps a report past this even if its own time-to-live has not run out
 */
public record WeatherReport(
        double lat,
        double lon,
        Double elevationM,
        String zoneId,
        String provider,
        String model,
        String attribution,
        Instant fetchedAt,
        Instant expiresAt,
        Conditions current,
        List<Conditions> hourly,
        List<DayOutlook> daily
) {

    public WeatherReport {
        hourly = hourly == null ? List.of() : List.copyOf(hourly);
        daily = daily == null ? List.of() : List.copyOf(daily);
    }
}
