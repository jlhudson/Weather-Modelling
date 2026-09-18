package au.gully.science;

import lombok.experimental.UtilityClass;

import java.util.List;

import static au.gully.science.Numbers.round1;

/**
 * McArthur's grassland fire danger meter, Mk5, in the form given by Noble, Bary and Gill (1980) —
 * the same paper {@link FireDanger} takes the forest meter from, so the two McArthur indices served
 * here rest on one published source. Moved from The Hub's {@code core/fuel/GrassFireDanger} in the
 * overhaul (docs/06 item 4): the service that holds the curing figure is the one that computes the
 * grassland index.
 * <p>
 * Pure arithmetic, no state, no Spring. What it needs that weather cannot supply — the degree of
 * curing and the fuel load — arrives as arguments the caller had to find somewhere honest: the curing
 * register ({@code au.gully.cfs.Curing}) and the land-use fuel class.
 *
 * <p><b>What this is not.</b> Not the AFDRS grass model — that is {@link CsiroGrassland}, and the
 * official rating beside it. Not a prediction of where a fire will go: the rate of spread here is the
 * meter's own headline figure for a fire in the open on flat ground, an index of how fast, never a
 * perimeter.
 */
@UtilityClass
public class GrassFireDanger {

    /**
     * McArthur drew the meter for this load, and the rate-of-spread relation below assumes it.
     */
    public static final double STANDARD_LOAD_T_HA = 4.5;

    /**
     * Grass fuel moisture content, per cent (Noble, Bary and Gill 1980, eq. 9):
     * {@code M = (97.7 + 4.06 H) / (T + 6) - 0.00854 H + 3000 / C - 30}.
     *
     * @param curingPct degree of curing, 1-100; the term {@code 3000/C} is why a curing of zero is not a number
     */
    public static double moisture(double temperatureC, double humidityPct, double curingPct) {
        double c = Math.max(1.0, Math.min(100.0, curingPct));
        return (97.7 + 4.06 * humidityPct) / (temperatureC + 6.0) - 0.00854 * humidityPct + 3000.0 / c - 30.0;
    }

    /**
     * The index (Noble, Bary and Gill 1980, eqs. 10-12), for fuel load {@code W} tonnes per hectare and
     * 10 m wind {@code V} km/h:
     * <pre>
     *   M &lt; 18.8        F = 3.35 W exp(-0.0897 M + 0.0403 V)
     *   18.8 &lt;= M &lt; 30  F = 0.299 W exp(-1.686 + 0.0403 V) (30 - M)
     *   M &gt;= 30         F = 0
     * </pre>
     */
    public static double gfdi(double temperatureC, double humidityPct, double windKmh, double curingPct, double fuelLoadTHa) {
        double m = moisture(temperatureC, humidityPct, curingPct);
        if (m >= 30.0) {
            return 0.0;
        }
        double w = Math.max(0.0, fuelLoadTHa);
        if (m < 18.8) {
            return 3.35 * w * Math.exp(-0.0897 * m + 0.0403 * windKmh);
        }
        return 0.299 * w * Math.exp(-1.686 + 0.0403 * windKmh) * (30.0 - m);
    }

    /**
     * The grassland bands, pre-2022, lowest first — the bands the number was drawn against, and they
     * are not the forest bands: grass runs to Catastrophic at 150 where forest does at 100.
     */
    public static final List<Band> BANDS = List.of(
            new Band("LOW-MODERATE", 0.0, 12.0),
            new Band("HIGH", 12.0, 25.0),
            new Band("VERY HIGH", 25.0, 50.0),
            new Band("SEVERE", 50.0, 100.0),
            new Band("EXTREME", 100.0, 150.0),
            new Band("CATASTROPHIC", 150.0, null));

    public static String rating(double index) {
        return Band.of(BANDS, index);
    }

    /**
     * Rate of forward spread in the open on level ground, km/h (Noble, Bary and Gill 1980, eq. 13):
     * {@code R = 0.13 F}. The fuel load is already inside {@code F}, which is why it is not an argument.
     */
    public static double spreadKmh(double gfdi) {
        return 0.13 * Math.max(0.0, gfdi);
    }

    /**
     * McArthur's slope rule: a fire doubles its rate for every ten degrees of upslope and halves for
     * every ten downslope, {@code exp(0.069 θ)}.
     */
    public static double slopeFactor(double slopeDegrees) {
        double clamped = Math.max(-20.0, Math.min(20.0, slopeDegrees));
        return Math.exp(0.069 * clamped);
    }

    /**
     * The compass direction a fire in the open travels: with the wind, so opposite to where it blows from.
     */
    public static int spreadDirectionDeg(int windFromDeg) {
        return Math.floorMod(windFromDeg + 180, 360);
    }

    /**
     * The index for one set of conditions, rounded for publication, or null when an input is missing.
     */
    public static Double of(Double temperatureC, Integer humidityPct, Double windKmh, Double curingPct, Double fuelLoadTHa) {
        if (temperatureC == null || humidityPct == null || windKmh == null || curingPct == null || fuelLoadTHa == null || curingPct <= 0) {
            return null;
        }
        return round1(gfdi(temperatureC, humidityPct, windKmh, curingPct, fuelLoadTHa));
    }
}
