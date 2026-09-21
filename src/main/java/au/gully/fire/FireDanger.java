package au.gully.fire;

import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * McArthur's forest fire danger meter, as arithmetic. Pure, no state, no Spring: the equation is
 * forty years old and settled, so it belongs where it can be checked by hand against the published
 * form.
 * <p>
 * <strong>What this is not.</strong> Not the AFDRS, which since 2022 rates danger by fuel type through
 * a different model and is published by the state. Not a rate of spread, and not a prediction of
 * where a fire will go.
 */
@UtilityClass
public class FireDanger {

    /**
     * McArthur Mk5 Forest Fire Danger Index, in the form given by Noble, Bary and Gill (1980):
     * {@code FFDI = 2.0 exp(-0.450 + 0.987 ln(DF) - 0.0345 RH + 0.0338 T + 0.0234 V)}.
     *
     * @param windKmh       10 m mean wind speed, the FFDI convention - not the gust
     * @param droughtFactor 0-10 from {@link Kbdi}, clamped away from zero because this takes its logarithm
     */
    public static double ffdi(double temperatureC, double humidityPct, double windKmh, double droughtFactor) {
        double df = Math.min(10.0, Math.max(0.1, droughtFactor));
        return 2.0 * Math.exp(-0.450 + 0.987 * Math.log(df) - 0.0345 * humidityPct + 0.0338 * temperatureC + 0.0234 * windKmh);
    }

    /**
     * The classic six-band fire danger rating as a table, lowest first. Deliberately the pre-2022
     * bands, because these are the bands the FFDI number itself was drawn against.
     */
    public static final List<Band> BANDS = List.of(
            new Band("LOW-MODERATE", 0.0, 12.0),
            new Band("HIGH", 12.0, 25.0),
            new Band("VERY HIGH", 25.0, 50.0),
            new Band("SEVERE", 50.0, 75.0),
            new Band("EXTREME", 75.0, 100.0),
            new Band("CATASTROPHIC", 100.0, null));

    public static String rating(double index) {
        return Band.of(BANDS, index);
    }

    /**
     * The index for one set of values, or {@code null} when temperature, humidity, wind or the
     * drought factor is missing. Never substitutes a missing input: an index computed from an absent
     * variable is exactly the confidently-wrong number this service exists not to publish.
     */
    public static Double of(Double temperatureC, Double humidityPct, Double windKmh, Double droughtFactor) {
        if (temperatureC == null || humidityPct == null || windKmh == null || droughtFactor == null) {
            return null;
        }
        return Math.round(ffdi(temperatureC, humidityPct, windKmh, droughtFactor) * 10) / 10.0;
    }
}
