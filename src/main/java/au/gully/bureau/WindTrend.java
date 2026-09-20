package au.gully.bureau;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where a station's wind has been, where it is, and how far the two are apart: the mean of the
 * readings before the latest — up to five, the rest of the hour {@link StationRegistry#RECENT}
 * keeps — against the latest reading itself. {@link WindShift} finds the sharpest pair in the
 * window and grades it; this is the smoother companion the map draws beside it, so a change reads as
 * a trend and not only as a flag: the arrow the wind has mostly been, the arrow it is, and — from
 * the hexagon's forecast, put beside these by the map — the arrow the model says it will be.
 * <p>
 * The mean direction is a vector mean over the readings whose speed is at least
 * {@link WindShift#CALM_KMH} (a vane in a calm points anywhere), so 350° and 10° average to north,
 * not to south. The mean speed and gust are plain means over every reading that has them.
 *
 * @param at          the latest reading's time
 * @param latestDeg   the latest direction, or null
 * @param latestKmh   the latest speed, or null
 * @param latestGust  the latest gust, or null
 * @param meanDeg     the vector-mean direction of the readings before the latest, or null when none had a usable direction
 * @param meanKmh     the mean speed of those readings, or null
 * @param meanGust    the mean gust of those readings, or null
 * @param readings    how many readings the means are over
 * @param overMinutes the span the means cover, from the oldest of them to the latest reading
 * @param swingDeg    the angle from the mean direction to the latest, the short way round, or null
 * @param deltaKmh    the latest speed minus the mean speed, or null
 */
public record WindTrend(Instant at, Integer latestDeg, Double latestKmh, Double latestGust,
                        Integer meanDeg, Double meanKmh, Double meanGust, int readings, long overMinutes,
                        Integer swingDeg, Double deltaKmh) {

    /**
     * The trend in a station's recent readings, newest first, or empty with fewer than two.
     */
    public static Optional<WindTrend> of(List<Observation> newestFirst) {
        if (newestFirst == null || newestFirst.size() < 2) {
            return Optional.empty();
        }
        Observation now = newestFirst.getFirst();
        if (now.at() == null) {
            return Optional.empty();
        }
        double x = 0, y = 0;
        int directions = 0, speeds = 0, gusts = 0, readings = 0;
        double speedSum = 0, gustSum = 0;
        Instant oldest = now.at();
        for (int i = 1; i < newestFirst.size(); i++) {
            Observation then = newestFirst.get(i);
            if (then.at() == null) {
                continue;
            }
            readings++;
            if (then.at().isBefore(oldest)) {
                oldest = then.at();
            }
            if (then.windDirectionDeg() != null && then.windSpeedKmh() != null && then.windSpeedKmh() >= WindShift.CALM_KMH) {
                double rad = Math.toRadians(then.windDirectionDeg());
                x += Math.sin(rad);
                y += Math.cos(rad);
                directions++;
            }
            if (then.windSpeedKmh() != null) {
                speedSum += then.windSpeedKmh();
                speeds++;
            }
            if (then.windGustKmh() != null) {
                gustSum += then.windGustKmh();
                gusts++;
            }
        }
        if (readings == 0) {
            return Optional.empty();
        }
        Integer meanDeg = directions == 0 || (Math.abs(x) < 1e-9 && Math.abs(y) < 1e-9) ? null
                : (int) Math.round((Math.toDegrees(Math.atan2(x, y)) + 360) % 360) % 360;
        Double meanKmh = speeds == 0 ? null : Math.round(speedSum / speeds * 10) / 10.0;
        Double meanGust = gusts == 0 ? null : Math.round(gustSum / gusts * 10) / 10.0;
        boolean latestBlows = now.windDirectionDeg() != null && now.windSpeedKmh() != null && now.windSpeedKmh() >= WindShift.CALM_KMH;
        Integer swing = meanDeg == null || !latestBlows ? null : WindShift.angle(meanDeg, now.windDirectionDeg());
        Double delta = meanKmh == null || now.windSpeedKmh() == null ? null : Math.round((now.windSpeedKmh() - meanKmh) * 10) / 10.0;
        return Optional.of(new WindTrend(now.at(), now.windDirectionDeg(), now.windSpeedKmh(), now.windGustKmh(),
                meanDeg, meanKmh, meanGust, readings, Math.max(0, Duration.between(oldest, now.at()).toMinutes()), swing, delta));
    }
}
