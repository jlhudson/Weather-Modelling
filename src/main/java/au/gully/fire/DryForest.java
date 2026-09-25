package au.gully.fire;

import java.time.LocalDateTime;

/**
 * The AFDRS dry forest model (W-33): the Dry Eucalypt Forest Fire Model ("Vesta", Cheney et al. 2012) as the AFDRS
 * computes it, and the Fire Behaviour Index and rating drawn from it. Every equation and constant is from the
 * <em>AFDRS Fire Behaviour Index Technical Guide – Forest</em>, version 2024.6.0 (NSW RFS, June 2024), which documents
 * the official AFDRS code of that version; equation numbers below are the guide's. Checked line by line against that
 * code, {@code fdrs_calcs.spread_models.dry_forest} 2024.6.0 (W-38). Pure arithmetic, no state.
 * <p>
 * <strong>The fuel.</strong> The model needs fuel hazard scores, heights, loads and the time since fire, which no weather
 * service observes. The AFDRS's own default table is not published openly; {@link Fuel#PROVISIONAL} is the dry forest
 * fuel set the public reimplementation (PyroXL, tested against the AFDRS code) uses, long unburnt - so the index here is
 * what a long-unburnt dry eucalypt forest would do in this weather, not the rating of the ground at the point, which
 * the fuel map decides. Every answer says so.
 */
public final class DryForest {

    private DryForest() {
    }

    /**
     * The fuel a forest carries. Loads in t/ha, near-surface height in cm, elevated and overstorey heights in m.
     *
     * @param k              the accumulation rate per year of the surface, near-surface, elevated and bark fuels and the
     *                       near-surface height - the official code takes one per layer; the provisional set gives them alike
     * @param wrf            the wind reduction factor from 10 m to the fuel, 3 for forest
     * @param yearsSinceFire the time since the fuel last burnt
     */
    public record Fuel(double fhsSurface, double fhsNearSurface, double heightNearSurfaceCm, double heightElevatedM, double heightOverstoreyM,
                       double loadSurface, double loadNearSurface, double loadElevated, double loadBark, double loadOverstorey,
                       double k, double wrf, double yearsSinceFire, String basis) {

        /**
         * PyroXL's dry forest set (src/tests_dry_forests.py), twenty-five years unburnt: provisional until the AFDRS's own
         * defaults can be read.
         */
        public static final Fuel PROVISIONAL = new Fuel(3, 1, 20, 2, 10, 10, 2, 2, 2, 4.5, 0.3, 3, 25,
                "provisional dry forest fuel (PyroXL's test set), 25 years unburnt; the AFDRS's own defaults are not published openly");
    }

    /**
     * What the model says for one set of weather.
     */
    public record Result(double moisturePct, double rateOfSpreadMh, double flameHeightM, double fuelLoadTHa, double intensityKwm, int fbi, String rating) {
    }

    /**
     * Dead fuel moisture, per cent (eqs 5-7): a sunny afternoon's (October to March, noon to 5 pm), a night's (7 pm to
     * 6 am), or the rest of the day's. No drought term: drought enters through availability.
     */
    public static double moisture(double temperatureC, double humidityPct, LocalDateTime local) {
        int month = local.getMonthValue(), hour = local.getHour();
        if ((month >= 10 || month <= 3) && hour >= 12 && hour <= 17) {
            return 2.76 + 0.124 * humidityPct - 0.0187 * temperatureC;
        }
        if (hour <= 6 || hour >= 19) {
            return 3.08 + 0.198 * humidityPct - 0.0483 * temperatureC;
        }
        return 3.60 + 0.169 * humidityPct - 0.0450 * temperatureC;
    }

    /**
     * The moisture function (eq 13): capped at its value for 4 per cent below, and 0.05 above 20.
     */
    public static double moistureFactor(double moisturePct) {
        if (moisturePct > 20) {
            return 0.05;
        }
        return 18.35 * Math.pow(Math.max(4, moisturePct), -1.495);
    }

    /**
     * The share of the fuel available to burn (eq 2): a tenth of the drought factor.
     */
    public static double availability(double droughtFactor) {
        return Math.max(0, Math.min(1, 0.1 * droughtFactor));
    }

    /**
     * A quantity grown since the last fire (eq 1), {@code X(t) = Xmax (1 - e^(-k t))} - unrounded, as the official code
     * computes it (the public reimplementation rounds to the tenth; the official does not).
     */
    static double accumulated(double max, double k, double years) {
        return max * (1 - Math.exp(-k * years));
    }

    /**
     * Rate of forward spread, m/h (eqs 9-12), on level ground.
     */
    public static double rateOfSpread(double windKmh, double moisturePct, double availability, Fuel f) {
        double m = moistureFactor(moisturePct);
        double u = windKmh * 3 / f.wrf();
        if (u <= 5) {
            return 30 * m;
        }
        double fhsS = accumulated(f.fhsSurface(), f.k(), f.yearsSinceFire()) * availability;
        double fhsNs = accumulated(f.fhsNearSurface(), f.k(), f.yearsSinceFire()) * availability;
        double hNs = Math.min(20, accumulated(f.heightNearSurfaceCm(), f.k(), f.yearsSinceFire()));
        return m * (30 + 1.5308 * Math.pow(u - 5, 0.8576) * Math.pow(fhsS, 0.9301) * Math.pow(fhsNs * hNs, 0.6366) * 1.03);
    }

    /**
     * Flame height, m (eq 15).
     */
    public static double flameHeight(double rateOfSpreadMh, Fuel f) {
        return 0.0193 * Math.pow(rateOfSpreadMh, 0.723) * Math.exp(0.64 * f.heightElevatedM()) * 1.07;
    }

    /**
     * The fuel that burns, t/ha: the surface (at most 10) and the near-surface always, the elevated fuel where the
     * flames stand over a metre, half the overstorey where they reach two thirds of its height; each times the
     * availability, and each but the overstorey grown since the fire - the official code holds the canopy at its steady
     * state.
     */
    public static double fuelLoad(double flameHeightM, double availability, Fuel f) {
        double k = f.k(), t = f.yearsSinceFire();
        double load = Math.min(10, accumulated(f.loadSurface(), k, t) * availability) + accumulated(f.loadNearSurface(), k, t) * availability;
        if (flameHeightM > 1) {
            load += accumulated(f.loadElevated(), k, t) * availability;
        }
        if (flameHeightM > 0.66 * f.heightOverstoreyM()) {
            load += 0.5 * f.loadOverstorey() * availability;
        }
        return load;
    }

    /**
     * Byram's intensity, kW/m (eq 16), at a heat yield of 18,600 kJ/kg.
     */
    public static double intensity(double rateOfSpreadMh, double fuelLoadTHa) {
        return 18600 * (rateOfSpreadMh / 3600) * (fuelLoadTHa / 10);
    }

    private static final double[] INTENSITY = {0, 100, 750, 4000, 10000, 30000, 90000};
    private static final double[] FBI = {0, 6, 12, 24, 50, 100, 200};

    /**
     * The Fire Behaviour Index for a forest intensity: linear between the published anchors, the last segment carried
     * on above 30,000 kW/m, rounded down.
     */
    public static int fbi(double intensityKwm) {
        double i = Math.max(0, intensityKwm);
        for (int s = 1; s < INTENSITY.length; s++) {
            if (i <= INTENSITY[s] || s == INTENSITY.length - 1) {
                double f = (i - INTENSITY[s - 1]) / (INTENSITY[s] - INTENSITY[s - 1]);
                return (int) Math.floor(FBI[s - 1] + f * (FBI[s] - FBI[s - 1]));
            }
        }
        return 0;
    }

    /**
     * The whole model for one set of weather, or null where an input is missing.
     *
     * @param local the local time the weather is for: the moisture equation depends on it
     */
    public static Result of(Double temperatureC, Double humidityPct, Double windKmh, Double droughtFactor, LocalDateTime local, Fuel f) {
        if (temperatureC == null || humidityPct == null || windKmh == null || droughtFactor == null || local == null) {
            return null;
        }
        double mc = moisture(temperatureC, humidityPct, local);
        double avail = availability(droughtFactor);
        double ros = rateOfSpread(windKmh, mc, avail, f);
        double fh = flameHeight(ros, f);
        double load = fuelLoad(fh, avail, f);
        double i = intensity(ros, load);
        int fbi = fbi(i);
        return new Result(round(mc, 2), round(ros, 1), round(fh, 2), round(load, 2), Math.round(i), fbi, CsiroGrassland.afdrs(fbi));
    }

    private static double round(double v, int places) {
        double p = Math.pow(10, places);
        return Math.round(v * p) / p;
    }
}
