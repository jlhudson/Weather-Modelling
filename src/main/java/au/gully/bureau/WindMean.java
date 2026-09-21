package au.gully.bureau;

import java.time.Duration;
import java.util.List;

/**
 * The wind over a station's last few readings (W-9): the speed's mean, the gust's mean, and the
 * direction as the mean of unit vectors - so 350° and 10° make north, not 180°. Readings without a
 * speed are skipped; with none, there is no mean.
 *
 * @param readings how many readings carried a speed
 * @param minutes  from the oldest of them to the newest
 */
public record WindMean(double kmh, Integer deg, Double gustKmh, int readings, long minutes) {

    /**
     * The mean of the newest {@code over} readings, newest first as the register keeps them.
     */
    public static WindMean of(List<Observation> newestFirst, int over) {
        double speed = 0, gust = 0, x = 0, y = 0;
        int n = 0, gusts = 0, dirs = 0;
        java.time.Instant oldest = null, newest = null;
        for (Observation o : newestFirst) {
            if (n >= over) {
                break;
            }
            if (o.windSpeedKmh() == null) {
                continue;
            }
            speed += o.windSpeedKmh();
            n++;
            if (o.windGustKmh() != null) {
                gust += o.windGustKmh();
                gusts++;
            }
            if (o.windDirectionDeg() != null && o.windSpeedKmh() > 0) {
                double a = Math.toRadians(o.windDirectionDeg());
                x += Math.sin(a);
                y += Math.cos(a);
                dirs++;
            }
            if (o.at() != null) {
                newest = newest == null ? o.at() : newest;
                oldest = o.at();
            }
        }
        if (n == 0) {
            return null;
        }
        Integer deg = dirs == 0 || (Math.abs(x) < 1e-9 && Math.abs(y) < 1e-9) ? null : (int) Math.round((Math.toDegrees(Math.atan2(x, y)) + 360) % 360);
        long minutes = oldest == null || newest == null ? 0 : Duration.between(oldest, newest).toMinutes();
        return new WindMean(Math.round(speed / n * 10) / 10.0, deg, gusts == 0 ? null : Math.round(gust / gusts * 10) / 10.0, n, minutes);
    }
}
