package au.gully.science;

import java.time.LocalDate;
import java.util.List;

import static au.gully.science.Numbers.round1;

/**
 * A soil moisture deficit for an area, integrated from daily rain and maximum temperature, and the
 * drought factor that falls out of it.
 * <p>
 * This is the one weather quantity that cannot be fetched. It is a running total, so it has to be
 * spun up from history and then carried forward a day at a time (docs/06 item 7). It varies slowly
 * and smoothly, so one of these serves a hexagon for a whole day.
 * <p>
 * There is no {@code estimated} flag and no {@code basis} sentence: a hexagon either has a drought
 * index or it has none, and a reading with none carries no fire index at all.
 *
 * @param kbdiMm               soil moisture deficit, 0 at field capacity to 203.2 at the dry end
 * @param droughtFactor        0-10, the Griffiths factor; this is what the fire indices consume
 * @param meanAnnualRainfallMm derived from the integration window, not looked up from a climate atlas
 * @param spunUpFrom           the first day of the integration
 * @param computedFor          the day the values describe
 * @param days                 how many days the integration has run over
 * @param recentRainMm         the daily rain that fed the drought factor, oldest first, today last
 */
public record DroughtIndex(
        double kbdiMm,
        String kbdiBand,
        double droughtFactor,
        double meanAnnualRainfallMm,
        LocalDate spunUpFrom,
        LocalDate computedFor,
        int days,
        List<Double> recentRainMm
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
