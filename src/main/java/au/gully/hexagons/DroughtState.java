package au.gully.hexagons;

import au.gully.science.DroughtIndex;
import au.gully.science.Kbdi;

import java.time.LocalDate;
import java.util.List;

import static au.gully.science.Numbers.round1;

/**
 * The drought state of a hexagon, stepped forward one day at a time (docs/06 item 7). Held on the
 * hexagon and written to its row, so a quiet hexagon keeps its state and picks up where it left off.
 *
 * @param kbdiMm       the deficit at the end of {@code computedFor}
 * @param computedFor  the last complete day the integration has run to
 * @param spunUpFrom   the first day of the integration
 * @param days         how many days it has run over
 * @param recentRainMm the last twenty days' rain, oldest first, which the drought factor needs
 * @param from         where the inputs came from: {@code stations}, {@code archive}, both — or
 *                     {@code interpolated}, from the spun-up hexagons around it (W-22)
 * @param fromHexagons the hexagons an interpolated state was made from, nearest first; empty for a
 *                     state of the hexagon's own
 */
public record DroughtState(
        double kbdiMm,
        double meanAnnualRainfallMm,
        LocalDate spunUpFrom,
        LocalDate computedFor,
        int days,
        List<Double> recentRainMm,
        String from,
        List<String> fromHexagons
) {

    public DroughtState {
        recentRainMm = recentRainMm == null ? List.of() : List.copyOf(recentRainMm);
        fromHexagons = fromHexagons == null ? List.of() : List.copyOf(fromHexagons);
    }

    /**
     * A state of the hexagon's own.
     */
    public DroughtState(double kbdiMm, double meanAnnualRainfallMm, LocalDate spunUpFrom, LocalDate computedFor, int days,
                        List<Double> recentRainMm, String from) {
        this(kbdiMm, meanAnnualRainfallMm, spunUpFrom, computedFor, days, recentRainMm, from, List.of());
    }

    /**
     * Whether the state was made from the hexagons around, not from this hexagon's own days: such a
     * state is not stepped, it is made again from theirs.
     */
    public boolean interpolated() {
        return "interpolated".equals(from);
    }

    public double droughtFactor() {
        return Kbdi.droughtFactor(kbdiMm, array(recentRainMm));
    }

    /**
     * The state as the science's own record, rounded for publication.
     */
    public DroughtIndex index() {
        double factor = droughtFactor();
        return new DroughtIndex(round1(kbdiMm), Kbdi.band(kbdiMm), round1(factor), round1(meanAnnualRainfallMm),
                spunUpFrom, computedFor, days, recentRainMm);
    }

    /**
     * One more day: rain comes off, evapotranspiration goes back on, and the window slides.
     */
    public DroughtState step(LocalDate day, double rainMm, double maxTemperatureC, double interceptionLeft) {
        double intercepted = rainMm > 0 ? Math.min(rainMm, interceptionLeft) : 0;
        double kbdi = Kbdi.step(kbdiMm, maxTemperatureC, rainMm - intercepted, meanAnnualRainfallMm);
        List<Double> window = new java.util.ArrayList<>(recentRainMm);
        window.add(rainMm);
        while (window.size() > Kbdi.WINDOW_DAYS) {
            window.removeFirst();
        }
        return new DroughtState(kbdi, meanAnnualRainfallMm, spunUpFrom, day, days + 1, window, from, fromHexagons);
    }

    static double[] array(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            Double v = values.get(i);
            out[i] = v == null ? 0 : v;
        }
        return out;
    }
}
