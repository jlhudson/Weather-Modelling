package au.weather.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The first forecast hour at which the wind swings — the thing a crew on a flank most needs to know
 * about the next two days, and a figure the previous package computed for itself (docs/24 G6).
 * <p>
 * <strong>The rule.</strong> An hour is a wind change when its direction has turned by more than
 * {@link #WIND_CHANGE_TURN_DEG} degrees — the smallest angular difference, so 350 to 20 is thirty and
 * not three hundred and thirty — relative to the hour {@link #WIND_CHANGE_LOOKBACK_HOURS} earlier, and
 * the mean wind at that hour is at least {@link #WIND_CHANGE_MIN_SPEED_KMH} km/h. The speed floor is
 * what stops a calm night backing through every point of the compass from reading as a change; the
 * lookback is what lets a front that takes two hours to pass still register. The first such hour in
 * the window is the answer and the rest of the series is not searched: "when does it change" has one
 * answer, and "how often" is not the question.
 * <p>
 * Constants, not configuration: a wind change that means one thing on one installation and another
 * on the next is not one a consumer can act on.
 *
 * @param at       the forecast hour the turned wind is first blowing
 * @param fromDeg  where it blew from {@link #WIND_CHANGE_LOOKBACK_HOURS} earlier
 * @param toDeg    where it blows from at {@code at}
 * @param speedKmh the 10 m mean wind at {@code at}
 * @param gustKmh  the gust at {@code at}, where the provider carries one
 */
public record WindChange(Instant at, int fromDeg, int toDeg, double speedKmh, Double gustKmh) {

    /** More than this many degrees of turn is a change. */
    public static final int WIND_CHANGE_TURN_DEG = 45;

    /** Below this mean wind a turn is weather, not a wind change. */
    public static final int WIND_CHANGE_MIN_SPEED_KMH = 15;

    /** The turn is measured against the direction this many hours earlier. */
    public static final int WIND_CHANGE_LOOKBACK_HOURS = 3;

    /**
     * The first change in a chronological series within {@code withinHours} of its first timestep, or
     * null when there is none.
     * <p>
     * The reference is the row exactly {@link #WIND_CHANGE_LOOKBACK_HOURS} before the candidate. In the
     * first hours of a series there is no such row and the series' own first hour stands in for it: a
     * turn of more than the threshold in one or two hours is a turn over three as well, and the
     * alternative was that a front arriving in the next two hours could never be reported. Where the
     * series steps more coarsely than an hour and the reference is simply absent, the hour is skipped
     * rather than compared with something six hours back.
     */
    public static WindChange find(List<Conditions> hours, int withinHours) {
        Instant start = firstTimestamp(hours);
        if (start == null) {
            return null;
        }
        Instant until = start.plus(Duration.ofHours(withinHours));
        for (int i = 0; i < hours.size(); i++) {
            Conditions c = hours.get(i);
            if (c == null || c.at() == null || c.windDirectionDeg() == null || c.windSpeedKmh() == null) {
                continue;
            }
            if (!c.at().isBefore(until)) {
                break;
            }
            if (c.windSpeedKmh() < WIND_CHANGE_MIN_SPEED_KMH) {
                continue;
            }
            Conditions ref = reference(hours, i, c.at().minus(Duration.ofHours(WIND_CHANGE_LOOKBACK_HOURS)));
            if (ref != null && turn(ref.windDirectionDeg(), c.windDirectionDeg()) > WIND_CHANGE_TURN_DEG) {
                return new WindChange(c.at(), ref.windDirectionDeg(), c.windDirectionDeg(), c.windSpeedKmh(), c.windGustKmh());
            }
        }
        return null;
    }

    /**
     * The smallest angle between two compass directions, 0 to 180: the turn, whichever way round the
     * compass it went and whichever side of north it crossed.
     */
    public static int turn(int fromDeg, int toDeg) {
        int d = Math.floorMod(toDeg - fromDeg, 360);
        return Math.min(d, 360 - d);
    }

    private static Instant firstTimestamp(List<Conditions> hours) {
        if (hours == null) {
            return null;
        }
        for (Conditions c : hours) {
            if (c != null && c.at() != null) {
                return c.at();
            }
        }
        return null;
    }

    /**
     * The row with a direction at {@code target}; the earliest such row when the series began after
     * {@code target}; null across a gap in the series.
     */
    private static Conditions reference(List<Conditions> hours, int before, Instant target) {
        Conditions earliest = null;
        for (int j = 0; j < before; j++) {
            Conditions r = hours.get(j);
            if (r == null || r.at() == null || r.windDirectionDeg() == null) {
                continue;
            }
            if (r.at().equals(target)) {
                return r;
            }
            if (earliest == null) {
                earliest = r;
            }
        }
        return earliest != null && earliest.at().isAfter(target) ? earliest : null;
    }
}
