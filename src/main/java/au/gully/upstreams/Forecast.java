package au.gully.upstreams;

import java.time.Duration;
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

    /**
     * The conditions at an instant, read off the hourly series: the two hours around it blended in
     * proportion, the words and the flags from the nearer one, the hour's rain from the hour it falls
     * in. Outside the series, its nearest end; without a series, the current block. Null only when
     * there is neither.
     */
    public Conditions at(Instant t) {
        if (hourly.isEmpty() || t == null) {
            return current;
        }
        Conditions before = null, after = null;
        for (Conditions c : hourly) {
            if (c.at() == null) {
                continue;
            }
            if (!c.at().isAfter(t)) {
                before = c;
            } else {
                after = c;
                break;
            }
        }
        if (before == null) {
            return after;
        }
        if (after == null || before.at().equals(t)) {
            return before;
        }
        double span = Duration.between(before.at(), after.at()).toSeconds();
        double f = span <= 0 ? 0 : Duration.between(before.at(), t).toSeconds() / span;
        Conditions nearer = f < 0.5 ? before : after;
        return Conditions.at(t)
                .temperature(blend(before.temperatureC(), after.temperatureC(), f))
                .apparent(blend(before.apparentTemperatureC(), after.apparentTemperatureC(), f))
                .dewPoint(blend(before.dewPointC(), after.dewPointC(), f))
                .humidity(blend(before.humidityPct(), after.humidityPct(), f))
                .wind(blend(before.windSpeedKmh(), after.windSpeedKmh(), f))
                .windDirection(blendBearing(before.windDirectionDeg(), after.windDirectionDeg(), f))
                .gust(blend(before.windGustKmh(), after.windGustKmh(), f))
                .precipitation(before.precipitationMm())
                .precipitationProbability(nearer.precipitationProbabilityPct())
                .pressure(blend(before.pressureMslHpa(), after.pressureMslHpa(), f))
                .cloud(blend(before.cloudCoverPct(), after.cloudCoverPct(), f))
                .visibility(blend(before.visibilityM(), after.visibilityM(), f))
                .uv(blend(before.uvIndex(), after.uvIndex(), f))
                .daytime(nearer.daytime())
                .condition(nearer.condition())
                .build();
    }

    private static Double blend(Double a, Double b, double f) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a + (b - a) * f;
    }

    private static Integer blend(Integer a, Integer b, double f) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return (int) Math.round(a + (b - a) * f);
    }

    /**
     * Two bearings blended the short way round, so 350° and 10° give 0°, not 180°.
     */
    private static Integer blendBearing(Integer a, Integer b, double f) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        double d = ((b - a + 540) % 360) - 180;
        return (int) Math.round((a + d * f + 360) % 360);
    }
}
