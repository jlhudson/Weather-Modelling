package au.weather.core;

import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * The Keetch-Byram Drought Index and the drought factor derived from it, as arithmetic.
 * <p>
 * This is the input the Forest Fire Danger Index could not previously be honest about. Three of the
 * four FFDI inputs come straight off a weather API; the fourth is a <em>soil moisture deficit</em>,
 * which is not an observation at all but the running total of a year of evaporation minus a year of
 * rain. It has to be integrated day by day from history, which is why it needed a spin-up before it
 * could stop being a configured guess.
 * <p>
 * <strong>KBDI</strong> is Keetch and Byram (1968) in the metric form corrected by Crane (1982) - the
 * original paper has a typographical error in the last numerator constant, 0.830 where 8.30 was meant,
 * and an implementation that copies it is wrong in a way that looks plausible. Units are millimetres
 * of soil moisture deficit, 0 at field capacity and 203.2 at the dry end.
 * <p>
 * <strong>The drought factor</strong> is Griffiths (1999) as corrected by Finkele et al. (2006): a
 * 0-10 measure of how much of the deep litter bed is actually available to burn, which is the KBDI
 * tempered by how recently it rained and how hard. Checked against the reference implementation in
 * xclim; note the numerator is {@code 41x² + x}, not {@code 41x² + 1}, which is a difference of about
 * one whole drought-factor unit in the middle of the range.
 */
@UtilityClass
public class Kbdi {

    /**
     * Field capacity in millimetres: KBDI runs 0 (saturated) to 203.2 (the 8-inch dry end).
     */
    public static final double FIELD_CAPACITY_MM = 203.2;

    /**
     * Rain intercepted by the canopy before any reaches the soil, per rain <em>event</em> rather than
     * per day. A dry day ends the event and restores the allowance; this is why the calculation needs
     * a consecutive daily series and cannot be done from a single day's total.
     */
    public static final double INTERCEPTION_MM = 5.1;

    /**
     * The drought factor looks back this far for rain events.
     */
    public static final int WINDOW_DAYS = 20;

    /**
     * Below this, a rain event is not considered to have wet the fuel at all.
     */
    public static final double EVENT_THRESHOLD_MM = 2.0;

    /**
     * One day of KBDI evolution: rain comes off first, then the day's evapotranspiration goes back on.
     *
     * @param kbdiMm           yesterday's deficit, millimetres
     * @param maxTempC         today's maximum air temperature
     * @param effectiveRainMm  today's rain after canopy interception
     * @param meanAnnualRainMm the site's mean annual rainfall, which sets how fast soil dries here
     */
    public static double step(double kbdiMm, double maxTempC, double effectiveRainMm, double meanAnnualRainMm) {
        double q = clamp(kbdiMm - effectiveRainMm);
        double evapotranspiration = (FIELD_CAPACITY_MM - q)
                * (0.968 * Math.exp(0.0875 * maxTempC + 1.5552) - 8.30)
                / (1.0 + 10.88 * Math.exp(-0.001736 * meanAnnualRainMm))
                * 1e-3;
        return clamp(q + evapotranspiration);
    }

    /**
     * Integrates a whole daily series, tracking canopy interception across rain events. The returned
     * array is the deficit at the end of each day, so its last element is the value for the last day
     * given.
     *
     * @param kbdi0 the starting deficit. The spin-up begins from field capacity, which is why it has to
     *              be long enough for the starting assumption to stop mattering
     */
    public static double[] series(double[] rainMm, double[] maxTempC, double meanAnnualRainMm, double kbdi0) {
        int n = Math.min(rainMm.length, maxTempC.length);
        double[] out = new double[n];
        double kbdi = clamp(kbdi0);
        double interceptionLeft = INTERCEPTION_MM;
        for (int i = 0; i < n; i++) {
            double rain = valueOr(rainMm[i], 0);
            double intercepted;
            if (rain > 0) {
                intercepted = Math.min(rain, interceptionLeft);
                interceptionLeft -= intercepted;
            } else {
                intercepted = 0;
                interceptionLeft = INTERCEPTION_MM;
            }
            kbdi = step(kbdi, valueOr(maxTempC[i], 20), rain - intercepted, meanAnnualRainMm);
            out[i] = kbdi;
        }
        return out;
    }

    /**
     * The Griffiths drought factor, 0 to 10, for a deficit and the last twenty days of rain.
     *
     * @param rainMm oldest day first, today last. Shorter series are accepted and simply see fewer
     *               events, which biases the factor upward - so a caller with less than twenty days of
     *               history should say so rather than presenting the result as settled
     */
    public static double droughtFactor(double kbdiMm, double[] rainMm) {
        double x = 1.0;
        int today = rainMm.length - 1;
        int i = 0;
        while (i < rainMm.length) {
            if (valueOr(rainMm[i], 0) <= 0) {
                i++;
                continue;
            }
            // Consecutive wet days are one event; its age is measured from the day it began.
            int start = i;
            double total = 0;
            while (i < rainMm.length && valueOr(rainMm[i], 0) > 0) {
                total += rainMm[i];
                i++;
            }
            if (total <= EVENT_THRESHOLD_MM) {
                continue;
            }
            double days = today - start;
            double aged = Math.pow(days, 1.3);
            x = Math.min(x, aged / (aged + total - EVENT_THRESHOLD_MM));
        }
        x = Math.min(x, limit(kbdiMm));
        double factor = 10.5 * (1 - Math.exp(-(kbdiMm + 30) / 40))
                * (41 * x * x + x) / (40 * x * x + x + 1);
        return Math.min(10.0, Math.max(0.0, factor));
    }

    /**
     * Finkele's ceiling on {@code x}: a soil that is not actually dry cannot produce a high drought
     * factor however long ago it last rained. Without it, twenty dry days over saturated ground reads
     * the same as twenty dry days over a drought.
     */
    static double limit(double kbdiMm) {
        return kbdiMm < 20 ? 1.0 / (1.0 + 0.1135 * kbdiMm) : 75.0 / (270.525 - 1.267 * kbdiMm);
    }

    /**
     * The six bands the Bureau publishes KBDI against, millimetres of deficit, wettest first. A table
     * so that {@code /api/vocabulary} serves the same boundaries {@link #band} walks (G13) - the first
     * consumer to draw this scale typed 51/102/152, which is the imperial scale converted, not this one.
     */
    public static final List<Band> BANDS = List.of(
            new Band("SATURATED", 0.0, 25.0),
            new Band("MOIST", 25.0, 50.0),
            new Band("DRYING", 50.0, 100.0),
            new Band("DRY", 100.0, 150.0),
            new Band("VERY DRY", 150.0, 185.0),
            new Band("SEVERE", 185.0, null));

    /**
     * The word for a deficit, from {@link #BANDS}, for the console and the API.
     */
    public static String band(double kbdiMm) {
        return Band.of(BANDS, kbdiMm);
    }

    private static double clamp(double v) {
        return Math.min(FIELD_CAPACITY_MM, Math.max(0.0, v));
    }

    private static double valueOr(double v, double fallback) {
        return Double.isNaN(v) ? fallback : v;
    }
}
