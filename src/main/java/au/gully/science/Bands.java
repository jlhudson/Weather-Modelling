package au.gully.science;

import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * The scales that are words rather than numbers: what a wind, a humidity and a temperature are called,
 * and the official AFDRS rating for a fire behaviour index. Every boundary lives here, once, so the
 * arithmetic that bands a value and the vocabulary a consumer draws an axis from cannot drift apart
 * (the Hub's docs/24 G13: the first consumer to draw a KBDI axis typed the wrong thresholds).
 * <p>
 * <strong>Presentation bands</strong> (wind, humidity, temperature) are round numbers placed where a
 * crew already changes its mind: under 15 per cent is the air of the worst fire days, 38 degrees is
 * where the heat itself limits a crew in structural kit, 62 km/h is Beaufort's gale.
 * <p>
 * <strong>The AFDRS rating</strong> is the published one (AFDRS Technical User Guide, Table 2): a
 * simple threshold on the Fire Behaviour Index at 12, 24, 50 and 100, with everything under 12 being
 * {@code No rating}. An FBI is rounded <em>down</em> to a whole number before it is banded, which is
 * why the published tables read 12–23 rather than 12–24.
 */
@UtilityClass
public class Bands {

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

    /**
     * The Australian Fire Danger Rating System's four ratings and the band below them, on the Fire
     * Behaviour Index, as the Bureau and the fire services publish them since September 2022. The words
     * are spelled as the CFS feed spells them, so a rating computed here and a rating read from the
     * district feed compare equal.
     */
    public static final List<Band> AFDRS = List.of(
            new Band("No Rating", 0.0, 12.0),
            new Band("Moderate", 12.0, 24.0),
            new Band("High", 24.0, 50.0),
            new Band("Extreme", 50.0, 100.0),
            new Band("Catastrophic", 100.0, null));

    public static String wind(double kmh) {
        return Band.of(WIND, kmh);
    }

    public static String humidity(double pct) {
        return Band.of(HUMIDITY, pct);
    }

    public static String temperature(double celsius) {
        return Band.of(TEMPERATURE, celsius);
    }

    /**
     * The AFDRS rating for a fire behaviour index. The index is floored first, as the published
     * products do: 23.9 is 23, and 23 is {@code Moderate}.
     */
    public static String afdrs(double fbi) {
        return Band.of(AFDRS, Math.floor(Math.max(0, fbi)));
    }
}
