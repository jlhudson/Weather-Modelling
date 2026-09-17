package au.weather.core;

import lombok.experimental.UtilityClass;

/**
 * How far a number is worth reporting to, in the two places this system needs an answer.
 *
 * <p>Both methods existed as a private {@code round} in seven different classes — four of them
 * identical to the tenth, three to the thousandth, and none of them able to say why. Rounding is not
 * arithmetic here, it is a statement about precision, and a statement worth making once.
 *
 * <p>Neither is used before a calculation. A drought factor is integrated at full precision and
 * rounded on the way out; rounding on the way in would compound across 365 days of spin-up.
 */
@UtilityClass
public class Numbers {

    /**
     * One decimal place: a measurement. A temperature read to a tenth of a degree, a wind speed, a
     * drought factor, an allowance count. The instruments do not resolve finer and neither should the
     * display, because a second decimal on a modelled wind speed is a claim nobody can support.
     */
    public static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * Two: a river discharge in cubic metres a second. A creek runs at 0.03 and floods at 0.9, so the
     * tenth this system rounds everything else to would erase the bottom of the scale entirely.
     */
    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * A fraction as a percentage to one decimal - a cache hit rate, an allowance consumed. Written
     * out because {@code Math.round(x * 1000) / 10.0} is two conversions in one expression and reads
     * like a typo for one of the two above.
     */
    public static double percent1(double fraction) {
        return Math.round(fraction * 1000.0) / 10.0;
    }

    /**
     * Three: a probability or a log-odds contribution. These live between 0 and 1, so a tenth would
     * flatten the difference between a 0.84 that stays a proposal and an 0.86 that becomes a merge -
     * which is exactly the difference the bridge exists to draw.
     */
    public static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
