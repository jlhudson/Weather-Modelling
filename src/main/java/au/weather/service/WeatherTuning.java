package au.weather.service;

import java.time.Duration;
import java.time.Instant;

/**
 * Every value the governor moves, as one immutable snapshot, plus the sentence explaining why they are
 * what they are.
 *
 * <p><strong>One record rather than a handful of volatile fields</strong>, and that is a correctness
 * argument rather than a tidiness one. A cache lookup reads the reach, the time-to-live and the vertical
 * weight in the same breath; an hourly step landing between two of those reads would pair a new reach with
 * an old time-to-live, and the resulting answer would be one no configuration ever described. Swapping a
 * single reference makes the set atomic, which is the same reason {@code IncidentProjection} and
 * {@code MeshcoreManager} are rebuilt and swapped rather than mutated.
 *
 * <p><strong>The reason is mandatory.</strong> Every governor in this system is required to say why, in
 * English, on the console: a cadence or a radius that moved for a reason nobody can see is indistinguishable
 * from a bug, and the person who has to tell those two apart is looking at a map at two in the morning.
 *
 * @param applied  where the governor currently sits between its floor and its ceiling, from {@code -1}
 *                 (everything at its tightest, spend freely) through {@code 0} (the configured nominals) to
 *                 {@code +1} (everything at its widest, conserve). Carried so the console can show the
 *                 direction of travel rather than only the endpoint
 * @param pressure allowance spent today against allowance the day should have spent by now, for the
 *                 provider nearest its guard. Below 1 is headroom, above 1 is overspending
 */
public record WeatherTuning(
        double anchorReachMetres,
        double droughtCellRadiusMetres,
        double riverCellRadiusMetres,
        Duration ttl,
        int forecastDays,
        int forecastHours,
        double verticalWeight,
        double pressure,
        double applied,
        Instant computedAt,
        String reason
) {

    public WeatherTuning {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a tuned value without a reason is indistinguishable from a bug");
        }
    }

    /**
     * The configured nominals, for before the governor has run and for when it is switched off.
     */
    public static WeatherTuning nominal(WeatherProperties p, String reason) {
        return new WeatherTuning(
                p.cache().reachMetres(),
                p.drought().cellRadiusMetres(),
                p.flood().cellRadiusMetres(),
                p.cache().ttl(),
                p.forecastDays(),
                p.forecastHours(),
                p.cache().verticalWeight(),
                0, 0, Instant.now(), reason);
    }

    /**
     * The forecast span a provider is allowed to ask for on this call.
     */
    public WeatherProvider.Span span() {
        return new WeatherProvider.Span(forecastDays, forecastHours);
    }

    public double anchorReachKm() {
        return anchorReachMetres / 1000.0;
    }

    public double droughtCellRadiusKm() {
        return droughtCellRadiusMetres / 1000.0;
    }

    public double riverCellRadiusKm() {
        return riverCellRadiusMetres / 1000.0;
    }
}
