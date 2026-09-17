package au.weather.core;

import java.time.LocalDate;
import java.util.List;

import static au.weather.core.Numbers.round1;

/**
 * A soil moisture deficit for a place, integrated from a year of daily rain and temperature, and the
 * drought factor that falls out of it.
 * <p>
 * This is the one weather quantity that cannot be fetched. It is a running total, so it has to be
 * spun up from history and then carried forward, and it is the difference between an FFDI that means
 * something and an FFDI resting on a number somebody typed into a configuration file.
 * <p>
 * It varies slowly and smoothly, so one of these serves a wide area and a whole day - far wider and
 * far longer than a weather anchor, which is why it is cached separately with its own radius.
 *
 * @param kbdiMm               soil moisture deficit, 0 at field capacity to 203.2 at the dry end
 * @param droughtFactor        0-10, the Griffiths factor; this is what the FFDI actually consumes
 * @param meanAnnualRainfallMm derived from the spin-up window, not looked up from a climate atlas
 * @param spunUpFrom           the first day of the integration. The starting deficit is assumed, so a
 *                             short window is a weak result and this field is how a reader can tell
 * @param computedFor          the day the values describe
 * @param recentRainMm         the daily rain that fed the drought factor, oldest first, today last
 * @param complete             true when the window was long enough for the starting assumption to have
 *                             washed out and the drought factor saw a full twenty days of rain
 */
public record DroughtIndex(
        double kbdiMm,
        String kbdiBand,
        double droughtFactor,
        double meanAnnualRainfallMm,
        LocalDate spunUpFrom,
        LocalDate computedFor,
        int spinUpDays,
        List<Double> recentRainMm,
        boolean complete,
        String basis
) {

    public DroughtIndex {
        recentRainMm = recentRainMm == null ? List.of() : List.copyOf(recentRainMm);
    }

    /**
     * Rain over the last {@code days} days, for the flood block. Null when the history is too short.
     */
    public Double rainOverLast(int days) {
        if (recentRainMm.size() < days) {
            return null;
        }
        double total = 0;
        for (int i = recentRainMm.size() - days; i < recentRainMm.size(); i++) {
            Double v = recentRainMm.get(i);
            total += v == null ? 0 : v;
        }
        return round1(total);
    }
}
