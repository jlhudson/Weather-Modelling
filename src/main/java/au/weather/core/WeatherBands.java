package au.weather.core;

import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Three scales this system did not have until FireBuddy asked for one vocabulary (docs/24 G13): the
 * words for a wind, a humidity and a temperature, with the boundaries every consumer draws them at.
 * <p>
 * <strong>Presentation bands, not a published product.</strong> No Bureau scale sits behind these the
 * way the pre-2022 ratings sit behind {@link FireDanger#BANDS}; they exist so that two apps colouring
 * the same 42 km/h do not pick two different oranges, and so that the boundaries live here, once,
 * rather than in each app's tables. The numbers are round on purpose, and placed where a crew already
 * changes its mind:
 * <ul>
 *   <li><b>Wind, km/h at 10 m</b> — {@code LIGHT} under 20, {@code MODERATE} 20-40, {@code STRONG}
 *       40-60, {@code GALE} 60 and above. Round groupings of the Beaufort scale as it is read on a
 *       fire ground: up to a gentle breeze; moderate and fresh; strong to near gale; gale, which
 *       Beaufort puts at 62.</li>
 *   <li><b>Relative humidity, per cent</b> — {@code VERY_DRY} under 15, {@code DRY} 15-30,
 *       {@code MODERATE} 30-60, {@code HUMID} 60 and above. Under 15 is the air of the worst fire
 *       days; under 30 is the range in which cured grass carries fire freely; above 60 the air is
 *       wetting fine fuel rather than drying it. Dry is the danger, so the ladder runs dry-first.</li>
 *   <li><b>Temperature, °C</b> — {@code MILD} under 20, {@code WARM} 20-30, {@code HOT} 30-38,
 *       {@code EXTREME} 38 and above. 38 is the round number past which the heat itself, not the job,
 *       limits a crew in structural kit. Open at the bottom: a frost is still {@code MILD}.</li>
 * </ul>
 * Pure arithmetic, no Spring, like the indices beside it, so the vocabulary and its test walk the
 * same tables the words come from.
 */
@UtilityClass
public class WeatherBands {

    public static final List<Band> WIND = List.of(
            new Band("LIGHT", 0.0, 20.0),
            new Band("MODERATE", 20.0, 40.0),
            new Band("STRONG", 40.0, 60.0),
            new Band("GALE", 60.0, null));

    public static final List<Band> HUMIDITY = List.of(
            new Band("VERY_DRY", 0.0, 15.0),
            new Band("DRY", 15.0, 30.0),
            new Band("MODERATE", 30.0, 60.0),
            new Band("HUMID", 60.0, null));

    public static final List<Band> TEMPERATURE = List.of(
            new Band("MILD", null, 20.0),
            new Band("WARM", 20.0, 30.0),
            new Band("HOT", 30.0, 38.0),
            new Band("EXTREME", 38.0, null));

    /** The word for a 10 m mean wind, km/h. */
    public static String wind(double kmh) {
        return Band.of(WIND, kmh);
    }

    /** The word for a relative humidity, per cent. */
    public static String humidity(double pct) {
        return Band.of(HUMIDITY, pct);
    }

    /** The word for an air temperature, degrees Celsius. */
    public static String temperature(double celsius) {
        return Band.of(TEMPERATURE, celsius);
    }
}
