package au.weather.core;

import java.time.Duration;

/**
 * A report, plus the derived fire and flood blocks, plus the honest account of how it reached the
 * caller. The last part is the point: a system whose whole design is "do not call the upstream" has to
 * be able to show that it did not, and how far it stretched a reading to avoid it (docs/09 9.2).
 *
 * @param fire                 the fire indices and their forecast, or null when the type of answer or the
 *                             available inputs do not support them
 * @param flood                antecedent and forecast rainfall, ground saturation and river discharge
 * @param drought              the soil moisture deficit behind the fire block, kept separately because it is
 *                             cached on a different radius and a different clock from everything else
 * @param cached               true when no upstream call was made for this answer
 * @param offsetMetres         how far the serving anchor is from the point that was asked about, on the ground
 * @param reachMetres          the three-dimensional separation the cache actually decided on. Equal to
 *                             {@code offsetMetres} on flat ground and wherever a terrain height was missing, and
 *                             larger wherever the anchor sits at a different altitude. Reported alongside rather
 *                             than instead, because a reader picturing a map wants the ground distance and a
 *                             reader asking why this anchor won needs the number that chose it
 * @param elevationDeltaMetres how far the query point sits above the serving anchor, positive when the
 *                             anchor is lower. Null when either height was unknown, which is also the signal
 *                             that the match was horizontal only
 * @param age                  how old the report was when it was served
 * @param decision             one line of English naming why this path was taken
 */
public record WeatherAnswer(
        WeatherReport report,
        FireWeather fire,
        FloodWeather flood,
        DroughtIndex drought,
        boolean cached,
        double offsetMetres,
        double reachMetres,
        Double elevationDeltaMetres,
        Duration age,
        String anchorId,
        String decision
) {

    public String provider() {
        return report.provider();
    }
}
