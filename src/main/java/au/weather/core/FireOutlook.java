package au.weather.core;

import java.time.LocalDate;

/**
 * One forecast day of fire weather: the index, and the four numbers it was built from.
 * <p>
 * The inputs travel with the output because a forecast index is a product of four forecasts, and
 * anyone deciding anything on the strength of "Severe on Thursday" needs to be able to see that it
 * rests on 38 degrees, 12 per cent and 45 km/h rather than on nothing in particular.
 * <p>
 * The deficit is <strong>projected</strong>, not observed: each forecast day's rain and maximum
 * temperature are run through the same daily step as the history, so a wet Wednesday lowers Thursday's
 * index the way it actually would.
 *
 * @param kbdiMm        the deficit projected to the end of that day
 * @param droughtFactor the factor recomputed against the projected deficit and the rolling rain window
 */
public record FireOutlook(
        LocalDate date,
        Double ffdi,
        String ffdiRating,
        Double kbdiMm,
        Double droughtFactor,
        Double maxTemperatureC,
        Integer minHumidityPct,
        Double maxWindKmh,
        Double maxGustKmh,
        Double rainMm
) {
}
