package au.gully.fire;

import au.gully.upstreams.Conditions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The wind change in a forecast (W-37): the hour the wind swings, from what to what, how hard it blows after, and
 * whether the air cools with it. The most dangerous hour on a fire ground is often the change - a south-westerly
 * change turns a long flank into a head fire - so a change coming is said plainly, with its hour. Pure arithmetic over
 * the hourly series, no state.
 * <p>
 * At every hour the mean direction of the {@link #SIDE} hours before is set against the mean of the hour and the ones
 * after it, each a vector mean weighted by speed, so a calm hour's direction counts for little. A <em>change</em> is a
 * swing of at least {@link #SWING_DEG} with at least {@link #BLOWING_KMH} after it; of a run of hours that qualify, the
 * one where the hours after have all come round and the hours before have not begun to is the change's hour
 * ({@link #sharpness}). It is a <em>cool change</em> where the air after is at least
 * {@link #COOLS_C} cooler and the wind comes round into the south or west. Only the hours from now to {@link #AHEAD}
 * are searched.
 */
public final class WindChange {

    public static final int SIDE = 3;
    public static final double SWING_DEG = 45;
    public static final double BLOWING_KMH = 15;
    public static final double COOLS_C = 3;
    public static final Duration AHEAD = Duration.ofHours(48);

    private WindChange() {
    }

    /**
     * One change.
     *
     * @param fromDeg the mean direction the wind came from before, degrees
     * @param toDeg   the mean direction it comes from after
     * @param coolsC  how much cooler the hours after are than the hours before; negative where it warms
     */
    public record Change(Instant at, double hoursAway, int fromDeg, int toDeg, int swingDeg, double speedBeforeKmh, double speedAfterKmh,
                         Double gustAfterKmh, Double coolsC, boolean cool) {
    }

    /**
     * The changes in the next {@link #AHEAD}, earliest first.
     */
    public static List<Change> find(List<Conditions> hourly, Instant now) {
        List<Conditions> h = hourly.stream().filter(c -> c.at() != null).toList();
        List<Change> out = new ArrayList<>();
        Change best = null;
        double bestSharpness = 0;
        for (int i = SIDE; i + SIDE <= h.size(); i++) {
            Instant at = h.get(i).at();
            if (!at.isAfter(now) || at.isAfter(now.plus(AHEAD))) {
                if (best != null) {
                    out.add(best);
                    best = null;
                }
                continue;
            }
            Change c = at(h, i, now);
            if (c != null) {
                double s = sharpness(h, i);
                if (best == null || s > bestSharpness) {
                    best = c;
                    bestSharpness = s;
                }
            } else if (best != null) {
                out.add(best);
                best = null;
            }
        }
        if (best != null) {
            out.add(best);
        }
        return out;
    }

    /**
     * How cleanly the wind has turned at hour {@code i}: how far each hour after lies from the mean before, and each hour
     * before from the mean after, averaged. It is greatest where the hours after have all come round and the hours before
     * have not begun to - the hour of the change - where a vector mean alone cannot tell a full reversal an hour early from
     * the hour itself.
     */
    static double sharpness(List<Conditions> h, int i) {
        double[] before = mean(h, i - SIDE, i), after = mean(h, i, i + SIDE);
        double sum = 0;
        for (int k = i - SIDE; k < i + SIDE; k++) {
            double d = h.get(k).windDirectionDeg();
            sum += Math.abs((((k < i ? after[0] : before[0]) - d + 540) % 360) - 180);
        }
        return sum / (2 * SIDE);
    }

    /**
     * The change at hour {@code i}, or null where there is none: the hours before it against it and the ones after.
     */
    static Change at(List<Conditions> h, int i, Instant now) {
        double[] before = mean(h, i - SIDE, i), after = mean(h, i, i + SIDE);
        if (before == null || after == null || after[1] < BLOWING_KMH) {
            return null;
        }
        int swing = (int) Math.round(Math.abs(((after[0] - before[0] + 540) % 360) - 180));
        if (swing < SWING_DEG) {
            return null;
        }
        Double tBefore = meanTemperature(h, i - SIDE, i), tAfter = meanTemperature(h, i, i + SIDE);
        Double cools = tBefore == null || tAfter == null ? null : Math.round((tBefore - tAfter) * 10) / 10.0;
        Double gust = null;
        for (int k = i; k < i + SIDE; k++) {
            Double g = h.get(k).windGustKmh();
            if (g != null) {
                gust = gust == null ? g : Math.max(gust, g);
            }
        }
        int to = (int) Math.round(after[0]) % 360;
        boolean cool = cools != null && cools >= COOLS_C && to >= 180 && to <= 270;
        double away = Math.round(Duration.between(now, h.get(i).at()).toMinutes() / 6.0) / 10.0;
        return new Change(h.get(i).at(), away, (int) Math.round(before[0]) % 360, to, swing, round1(before[1]), round1(after[1]), gust, cools, cool);
    }

    /**
     * The speed-weighted vector mean of hours {@code [from, to)}: {direction the wind comes from, mean speed}; null where
     * an hour lacks either.
     */
    static double[] mean(List<Conditions> h, int from, int to) {
        double x = 0, y = 0, speed = 0;
        for (int k = from; k < to; k++) {
            Conditions c = h.get(k);
            if (c.windDirectionDeg() == null || c.windSpeedKmh() == null) {
                return null;
            }
            double a = Math.toRadians(c.windDirectionDeg());
            x += Math.sin(a) * c.windSpeedKmh();
            y += Math.cos(a) * c.windSpeedKmh();
            speed += c.windSpeedKmh();
        }
        double deg = (Math.toDegrees(Math.atan2(x, y)) + 360) % 360;
        return new double[]{deg, speed / (to - from)};
    }

    private static Double meanTemperature(List<Conditions> h, int from, int to) {
        double sum = 0;
        for (int k = from; k < to; k++) {
            if (h.get(k).temperatureC() == null) {
                return null;
            }
            sum += h.get(k).temperatureC();
        }
        return sum / (to - from);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
