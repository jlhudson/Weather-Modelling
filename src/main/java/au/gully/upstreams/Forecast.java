package au.gully.upstreams;

import au.gully.science.Conditions;
import au.gully.science.DayOutlook;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One upstream's answer for one hexagon centre: the conditions now as its model saw them when it was
 * fetched, the hourly series and the daily outlook, in the shape every upstream is normalised into.
 * This is what is held on the hexagon and written to its row.
 * <p>
 * A forecast carries no expiry of its own: how long it is kept is the service's rule ({@code Life}:
 * three hours, stretched when the allowance is tight) and a station's word ({@code Drift}: thrown out
 * early when the station in the hexagon says the model has drifted). What it does carry is the
 * series, so "now" can be read off it at any moment inside its life ({@link #at}) rather than off a
 * current block that is hours old.
 *
 * @param attribution     the licence line the upstream requires; it travels to the API edge
 * @param fetchedAt       when it was fetched, which its life is counted from
 * @param modelElevationM the height of the model cell the upstream answered from, which explains
 *                        a reading; the hexagon's own elevation, from the terrain file, places it
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
     * proportion, the words and the flags from the nearer one, a total for the hour (rain, showers,
     * snow) from the hour it falls in. Outside the series, its nearest end; without a series, the
     * current block. Null only when there is neither.
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
                .vapourPressureDeficit(blend(before.vapourPressureDeficitKpa(), after.vapourPressureDeficitKpa(), f))
                .evapotranspiration(before.evapotranspirationMm())
                .soilMoistureSurface(blend(before.soilMoistureSurface(), after.soilMoistureSurface(), f))
                .soilMoistureShallow(blend(before.soilMoistureShallow(), after.soilMoistureShallow(), f))
                .soilMoistureRootZone(blend(before.soilMoistureRootZone(), after.soilMoistureRootZone(), f))
                .soilTemperature(blend(before.soilTemperatureC(), after.soilTemperatureC(), f))
                .boundaryLayerHeight(blend(before.boundaryLayerHeightM(), after.boundaryLayerHeightM(), f))
                .cape(blend(before.capeJkg(), after.capeJkg(), f))
                .liftedIndex(blend(before.liftedIndex(), after.liftedIndex(), f))
                .convectiveInhibition(blend(before.convectiveInhibitionJkg(), after.convectiveInhibitionJkg(), f))
                .wind80m(blend(before.windSpeed80mKmh(), after.windSpeed80mKmh(), f))
                .windDirection80m(blendBearing(before.windDirection80mDeg(), after.windDirection80mDeg(), f))
                .shortwaveRadiation(blend(before.shortwaveRadiationWm2(), after.shortwaveRadiationWm2(), f))
                .showers(before.showersMm())
                .snowfall(before.snowfallCm())
                .build();
    }

    /**
     * The rain the series holds between two instants: the hourly totals of the hours starting in the
     * window. Null when the series does not reach back to {@code from}, so a comparison is never made
     * against a partial sum.
     */
    public Double precipitationBetween(Instant from, Instant to) {
        if (hourly.isEmpty() || from == null || to == null || !from.isBefore(to)) {
            return null;
        }
        Instant first = hourly.getFirst().at();
        if (first == null || first.isAfter(from)) {
            return null;
        }
        double total = 0;
        for (Conditions c : hourly) {
            if (c.at() == null || c.at().isBefore(from) || !c.at().isBefore(to)) {
                continue;
            }
            if (c.precipitationMm() != null) {
                total += c.precipitationMm();
            }
        }
        return total;
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
