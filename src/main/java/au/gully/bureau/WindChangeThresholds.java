package au.gully.bureau;

import au.gully.storage.ConsoleSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * What counts as a wind change (W-23): a swing of at least so many degrees, or a speed change of at
 * least so many kilometres an hour, in a station's last hour. Two values turned on the console map
 * and kept in the setting table; the ladder of grades (slight, marked, sharp) is fixed above them.
 * The defaults are the ladder's first rungs, which is what counted before there was a setting.
 */
@Slf4j
@Service
public class WindChangeThresholds {

    static final String SWING_KEY = "wind_change_swing_deg", SPEED_KEY = "wind_change_speed_kmh";

    public static final int MAX_SWING_DEG = 180;
    public static final double MAX_SPEED_KMH = 60;

    private final ConsoleSettings settings;
    private volatile int swingDeg = WindShift.SWING_SLIGHT_DEG;
    private volatile double speedKmh = WindShift.SPEED_SLIGHT_KMH;
    private volatile String by;
    private volatile Instant since;

    public WindChangeThresholds(ConsoleSettings settings) {
        this.settings = settings;
    }

    public void rehydrate() {
        ConsoleSettings.Setting swing = settings.read(SWING_KEY).orElse(null);
        ConsoleSettings.Setting speed = settings.read(SPEED_KEY).orElse(null);
        swingDeg = swing == null ? WindShift.SWING_SLIGHT_DEG : clampSwing(parse(swing.value(), WindShift.SWING_SLIGHT_DEG));
        speedKmh = speed == null ? WindShift.SPEED_SLIGHT_KMH : clampSpeed(parse(speed.value(), WindShift.SPEED_SLIGHT_KMH));
        ConsoleSettings.Setting latest = swing == null ? speed : speed == null || swing.at().isAfter(speed.at()) ? swing : speed;
        by = latest == null ? null : latest.by();
        since = latest == null ? null : latest.at();
        log.info("wind change counts from {}° or {} km/h{}", swingDeg, speedKmh, latest == null ? ", the defaults" : ", set on the console by " + by + " at " + since);
    }

    public int swingDeg() {
        return swingDeg;
    }

    public double speedKmh() {
        return speedKmh;
    }

    public String by() {
        return by;
    }

    public Instant since() {
        return since;
    }

    public void set(int newSwingDeg, double newSpeedKmh, String setBy) {
        Instant now = Instant.now();
        int s = clampSwing(newSwingDeg);
        double v = clampSpeed(newSpeedKmh);
        settings.write(SWING_KEY, String.valueOf(s), setBy, now);
        settings.write(SPEED_KEY, String.valueOf(v), setBy, now);
        log.info("wind change set to count from {}° or {} km/h (was {}°, {}) by {}", s, v, swingDeg, speedKmh, setBy);
        swingDeg = s;
        speedKmh = v;
        by = setBy;
        since = now;
    }

    public static int clampSwing(double v) {
        return (int) Math.max(5, Math.min(MAX_SWING_DEG, Math.round(v / 5.0) * 5));
    }

    public static double clampSpeed(double v) {
        return Math.max(2, Math.min(MAX_SPEED_KMH, Math.round(v)));
    }

    private static double parse(String s, double otherwise) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            log.warn("wind change: the setting '{}' is not a number; {} stands", s, otherwise);
            return otherwise;
        }
    }
}
