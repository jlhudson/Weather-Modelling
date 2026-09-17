package au.weather.core;

import java.util.List;

/**
 * One band of a scale: the word, and the half-open range {@code [from, to)} it names. {@code to} is null
 * on the open top band; {@code from} is null on a scale that is open at the bottom too, which only a
 * temperature is.
 * <p>
 * Exists so a rating function and the vocabulary that describes it read <em>one table</em>. Before
 * this the thresholds were literals inside {@code if} chains in {@link FireDanger#rating},
 * {@link Kbdi#band} and {@code GrassFireDanger.rating}, and {@code /api/vocabulary} could only carry
 * the words: a consumer that wanted to draw an axis typed the boundaries itself, and the first one to
 * do so banded GFDI on FFDI's thresholds and KBDI at 51/102/152 where the code says 25/50/100/150/185
 * (docs/24 24.7). A table the arithmetic walks and the endpoint serves cannot drift from itself.
 *
 * @param from inclusive, null for an open bottom
 * @param to   exclusive, null for the open top band
 */
public record Band(String value, Double from, Double to) {

    /**
     * The word for a value: the first band whose ceiling it is under, else the open top band. This is
     * the same cascade the {@code if} chains were - a value below the first band's floor, or a NaN,
     * lands where it always did - so replacing them with it changed no answer.
     */
    public static String of(List<Band> scale, double value) {
        for (Band b : scale) {
            if (b.to == null || value < b.to) {
                return b.value;
            }
        }
        return scale.getLast().value;
    }
}
