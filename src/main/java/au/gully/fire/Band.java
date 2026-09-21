package au.gully.fire;

import java.util.List;

/**
 * One band of a scale: the word, and the half-open range {@code [from, to)} it names. {@code to} is null
 * on the open top band; {@code from} is null on a scale that is open at the bottom too, which only a
 * temperature is.
 * <p>
 * Exists so a rating function and whatever describes it read <em>one table</em>: the thresholds a
 * word is given at are never literals inside an {@code if} chain, and a consumer that draws an axis
 * draws the same boundaries the word was chosen by.
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
