package au.gully.science;

import lombok.experimental.UtilityClass;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static au.gully.science.Numbers.round1;

/**
 * McArthur's forest fire danger meter, as arithmetic. Pure, no state, no Spring: the equation is
 * forty years old and settled, so it belongs where it can be checked by hand against the published
 * form.
 * <p>
 * <strong>What this is not.</strong> Not the AFDRS, which since 2022 rates danger by fuel type through
 * a different model and is published by the state; the published rating travels beside this on every
 * reading, and the grassland half of the AFDRS is {@link CsiroGrassland}. Not a rate of spread, and
 * not a prediction of where a fire will go.
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
     * bands, because these are the bands the FFDI number itself was drawn against; the AFDRS rating
     * that replaced them operationally is {@link Bands#AFDRS}.
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
     * The index for one set of conditions, or {@code null} when temperature, humidity or wind is
     * missing. Never substitutes a missing input: an index computed from an absent variable is exactly
     * the confidently-wrong number this service exists not to publish.
     */
    public static Double of(Conditions c, double droughtFactor) {
        if (c == null || !c.fireInputsPresent()) {
            return null;
        }
        return round1(ffdi(c.temperatureC(), c.humidityPct(), c.windSpeedKmh(), droughtFactor));
    }

    /**
     * The forecast indices, day by day, with the soil moisture deficit carried forward through each
     * day's forecast rain and heat, and the grassland indices on the same days where a curing figure
     * is held.
     * <p>
     * <strong>One boundary assumption.</strong> Canopy interception is reset to full at the start of the
     * forecast, because the spin-up records how much rain fell but not how much of the current rain
     * event the canopy had already caught. The effect is at most a few millimetres, on the first day
     * only, and only when it is raining as the forecast begins.
     *
     * @param days           one entry per forecast day, in order
     * @param startingKbdiMm the deficit at the end of the history
     * @param historyRainMm  daily rain behind the forecast, oldest first, for the drought factor window
     * @param grass          the curing, load and condition for the grassland indices, or null for none
     */
    public static List<FireOutlook> outlook(List<FireDay> days, double startingKbdiMm, double meanAnnualRainMm,
                                            List<Double> historyRainMm, GrassInputs grass) {
        List<FireOutlook> out = new ArrayList<>();
        List<Double> window = new ArrayList<>(historyRainMm == null ? List.of() : historyRainMm);
        double kbdi = startingKbdiMm;
        double interceptionLeft = Kbdi.INTERCEPTION_MM;

        for (FireDay day : days) {
            double rain = day.rainMm() == null ? 0 : day.rainMm();
            double intercepted;
            if (rain > 0) {
                intercepted = Math.min(rain, interceptionLeft);
                interceptionLeft -= intercepted;
            } else {
                intercepted = 0;
                interceptionLeft = Kbdi.INTERCEPTION_MM;
            }
            double maxTemp = day.maxTemperatureC() == null ? 20 : day.maxTemperatureC();
            kbdi = Kbdi.step(kbdi, maxTemp, rain - intercepted, meanAnnualRainMm);

            window.add(rain);
            while (window.size() > Kbdi.WINDOW_DAYS) {
                window.removeFirst();
            }
            double factor = Kbdi.droughtFactor(kbdi, toArray(window));

            Double index = null;
            Double gfdi = null;
            CsiroGrassland.Result csiro = null;
            if (day.maxTemperatureC() != null && day.minHumidityPct() != null && day.maxWindKmh() != null) {
                index = round1(ffdi(day.maxTemperatureC(), day.minHumidityPct(), day.maxWindKmh(), factor));
                if (grass != null) {
                    gfdi = GrassFireDanger.of(day.maxTemperatureC(), day.minHumidityPct(), day.maxWindKmh(),
                            grass.curingPct(), grass.fuelLoadTHa());
                    csiro = CsiroGrassland.of(day.maxTemperatureC(), day.minHumidityPct(), day.maxWindKmh(),
                            grass.curingPct(), grass.fuelLoadTHa(), grass.condition());
                }
            }
            out.add(new FireOutlook(day.date(), index, index == null ? null : rating(index),
                    gfdi, gfdi == null ? null : GrassFireDanger.rating(gfdi),
                    csiro == null ? null : csiro.fbi(), csiro == null ? null : csiro.rating(),
                    round1(kbdi), round1(factor), day.maxTemperatureC(), day.minHumidityPct(),
                    day.maxWindKmh(), day.maxGustKmh(), day.rainMm()));
        }
        return out;
    }

    private static double[] toArray(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Double v = values.get(i);
            out[i] = v == null ? 0 : v;
        }
        return out;
    }

    /**
     * What one forecast day has to supply for an index. The worst hour of the day, not the mean.
     */
    public record FireDay(LocalDate date, Double maxTemperatureC, Integer minHumidityPct,
                          Double maxWindKmh, Double maxGustKmh, Double rainMm) {
    }

    /**
     * The three grassland inputs weather cannot supply, found somewhere honest by the caller.
     */
    public record GrassInputs(double curingPct, double fuelLoadTHa, CsiroGrassland.Condition condition) {
    }
}
