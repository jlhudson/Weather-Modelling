package au.gully.science;

import lombok.experimental.UtilityClass;

/**
 * The AFDRS grassland model: the CSIRO grassland fire spread meter (Cheney, Gould and Catchpole 1998,
 * with the curing function of Cruz et al. 2015), and the Fire Behaviour Index the rating system draws
 * from it. Every constant below is from the AFDRS Fire Behaviour Index Technical Guide — Grassland,
 * version 2024.6.0 (NSW RFS, June 2024), section 1.5, and the AFDRS Technical User Guide's Table 7.
 * <p>
 * This is the model behind the {@code Moderate / High / Extreme / Catastrophic} the public are told
 * since September 2022, for the 46 per cent of fire weather areas that grass dominates. It is served
 * beside the McArthur indices, not instead of them: the McArthur numbers are what forty years of
 * records were kept against, this is what the sign on the highway says today.
 * <p>
 * <strong>Grass condition</strong> is the one input a weather service cannot observe. AFDRS uses
 * {@code grazed} where nothing better is known, and infers it from the reported fuel load otherwise
 * (natural at 6 t/ha and over, eaten-out under 3): {@link Condition#fromLoad} does the same.
 */
@UtilityClass
public class CsiroGrassland {

    /**
     * Heat yield, kJ/kg, for Byram's intensity (Guide eq. 9). The guide notes grass may yield less.
     */
    public static final double HEAT_YIELD_KJ_KG = 18_600;

    /**
     * The lower bound on dead fuel moisture in the AFDRS implementation (Guide 1.5.2): Cheney's 2 per
     * cent was raised to 5 after Cruz et al. (2022).
     */
    public static final double MOISTURE_FLOOR_PCT = 5.0;

    /**
     * Grass condition, and the four rate-of-spread coefficients each carries (Guide eqs. 5-7).
     */
    public enum Condition {
        NATURAL(0.054, 0.269, 1.4, 0.838),
        GRAZED(0.054, 0.209, 1.1, 0.715),
        EATEN_OUT(0.027, 0.1045, 0.55, 0.357);

        final double lowIntercept, lowSlope, highIntercept, highSlope;

        Condition(double lowIntercept, double lowSlope, double highIntercept, double highSlope) {
            this.lowIntercept = lowIntercept;
            this.lowSlope = lowSlope;
            this.highIntercept = highIntercept;
            this.highSlope = highSlope;
        }

        /**
         * AFDRS's default when no condition layer is provided (Guide 1.5.1): natural at 6 t/ha and
         * over, grazed from 3, eaten-out below.
         */
        public static Condition fromLoad(double fuelLoadTHa) {
            return fuelLoadTHa >= 6 ? NATURAL : fuelLoadTHa >= 3 ? GRAZED : EATEN_OUT;
        }

        public static Condition parse(String name) {
            if (name == null || name.isBlank()) {
                return GRAZED;
            }
            String key = name.trim().toUpperCase().replace('-', '_').replace(' ', '_');
            return switch (key) {
                case "NATURAL", "UNDISTURBED", "UNGRAZED" -> NATURAL;
                case "EATEN_OUT", "EATENOUT", "EATEN" -> EATEN_OUT;
                default -> GRAZED;
            };
        }
    }

    /**
     * Dead fuel moisture content, per cent (Guide eq. 1, McArthur 1966 as fitted by Cruz et al. 2015):
     * {@code MC = 9.58 - 0.205 T + 0.138 RH}, floored at {@link #MOISTURE_FLOOR_PCT}.
     */
    public static double moisturePct(double temperatureC, double humidityPct) {
        return Math.max(MOISTURE_FLOOR_PCT, 9.58 - 0.205 * temperatureC + 0.138 * humidityPct);
    }

    /**
     * The fuel moisture coefficient (Guide eq. 2): exponential below 12 per cent, linear above it with
     * the slope depending on the wind, and zero once the grass is too wet to carry fire.
     */
    public static double moistureCoefficient(double moisturePct, double windKmh) {
        double factor;
        if (moisturePct < 12) {
            factor = Math.exp(-0.108 * moisturePct);
        } else if (windKmh <= 10) {
            factor = 0.684 - 0.0342 * moisturePct;
        } else {
            factor = 0.547 - 0.0228 * moisturePct;
        }
        // "If MC > 20%, no fire propagation will occur" (Guide 1.3).
        return moisturePct > 20 ? 0 : Math.max(0, factor);
    }

    /**
     * The curing coefficient of Cruz, Gould, Kidnie et al. (2015), Guide eq. 8:
     * {@code 1.036 / (1 + 103.989 exp(-0.0996 (C - 20)))}. Fires self-extinguish under 20 per cent.
     */
    public static double curingCoefficient(double curingPct) {
        if (curingPct < 20) {
            return 0;
        }
        return 1.036 / (1 + 103.989 * Math.exp(-0.0996 * (curingPct - 20)));
    }

    /**
     * Rate of spread in metres per hour (Guide eqs. 3 and 4): linear in the wind below 5 km/h, a power
     * function above it.
     *
     * @param windKmh 10 m wind speed
     */
    public static double rateOfSpreadMh(Condition condition, double windKmh, double moisturePct, double curingPct) {
        double phiM = moistureCoefficient(moisturePct, windKmh);
        double phiC = curingCoefficient(curingPct);
        double w = Math.max(0, windKmh);
        double base = w < 5
                ? condition.lowIntercept + condition.lowSlope * w
                : condition.highIntercept + condition.highSlope * Math.pow(w - 5, 0.844);
        return 1000 * base * phiM * phiC;
    }

    /**
     * Byram's fireline intensity, kW/m (Guide eq. 9): {@code I = h F R} with the load in kg/m² and the
     * rate in m/s.
     */
    public static double intensityKwm(double rateOfSpreadMh, double fuelLoadTHa) {
        double loadKgM2 = Math.max(0, fuelLoadTHa) / 10.0;
        return HEAT_YIELD_KJ_KG * loadKgM2 * (rateOfSpreadMh / 3600.0);
    }

    /**
     * Flame height, metres (Guide eqs. 10 and 11, Plucinski pers. comm.).
     */
    public static double flameHeightM(Condition condition, double rateOfSpreadMh) {
        double kmh = rateOfSpreadMh / 1000.0;
        double scale = condition == Condition.NATURAL ? 2.66 : 1.12;
        return scale * Math.pow(kmh / 3.6, 0.295);
    }

    /**
     * The grassland Fire Behaviour Index from fireline intensity: linear inside each of the published
     * ranges (Guide Table 2), and above 25,000 kW/m on the line that puts the Kilmore East fire's
     * 90,000 kW/m at 200. Not rounded here; the published value is the floor of it, which
     * {@link Bands#afdrs} takes.
     */
    public static double fbi(double intensityKwm) {
        double[] intensity = {0, 100, 3_000, 9_000, 17_500, 25_000, 90_000};
        double[] index = {0, 6, 12, 24, 50, 100, 200};
        double i = Math.max(0, intensityKwm);
        for (int k = 1; k < intensity.length; k++) {
            if (i <= intensity[k]) {
                double f = (i - intensity[k - 1]) / (intensity[k] - intensity[k - 1]);
                return index[k - 1] + f * (index[k] - index[k - 1]);
            }
        }
        // Past Kilmore East: the same slope as the last range.
        double slope = (index[6] - index[5]) / (intensity[6] - intensity[5]);
        return index[6] + (i - intensity[6]) * slope;
    }

    /**
     * Everything the model says about one set of conditions, or null when an input the model cannot do
     * without is missing. Never substitutes: a grass index resting on an assumed curing is exactly the
     * confidently wrong number the overhaul set out to stop publishing.
     *
     * @param fuelLoadTHa fine fuel load, tonnes per hectare; the McArthur standard 4.5 where nothing
     *                    better is known
     */
    public static Result of(Double temperatureC, Integer humidityPct, Double windKmh, Double curingPct,
                            Double fuelLoadTHa, Condition condition) {
        if (temperatureC == null || humidityPct == null || windKmh == null || curingPct == null || fuelLoadTHa == null) {
            return null;
        }
        Condition c = condition == null ? Condition.fromLoad(fuelLoadTHa) : condition;
        double moisture = moisturePct(temperatureC, humidityPct);
        double ros = rateOfSpreadMh(c, windKmh, moisture, curingPct);
        double intensity = intensityKwm(ros, fuelLoadTHa);
        double fbi = fbi(intensity);
        return new Result(c, Numbers.round1(moisture), Numbers.round2(ros / 1000.0), Math.round(intensity),
                Numbers.round1(flameHeightM(c, ros)), (int) Math.floor(fbi), Bands.afdrs(fbi));
    }

    /**
     * @param rateOfSpreadKmh the headline figure, in the open on level ground
     * @param fbi             the published, floored Fire Behaviour Index
     * @param rating          the AFDRS word for it
     */
    public record Result(Condition condition, double moisturePct, double rateOfSpreadKmh, long intensityKwm,
                         double flameHeightM, int fbi, String rating) {
    }
}
