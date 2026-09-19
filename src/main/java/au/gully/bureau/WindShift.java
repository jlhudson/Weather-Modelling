package au.gully.bureau;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A wind change a station has just measured (W-16): the latest reading against the readings before
 * it, over the last hour or so, in direction and in speed. On a fire a swing of direction turns a
 * flank into a head — the south-westerly behind a north wind is the classic — and a freshening
 * drives the head; each is graded on its own and the two together.
 * <p>
 * The swing is the angle between the latest direction and an earlier reading in the window, the
 * short way round; directions under {@link #CALM_KMH} are not compared, because a vane in a calm
 * points anywhere. The speed change is the latest speed minus the earlier one, signed, graded by its
 * size. Each grade is {@code slight}, {@code marked} or {@code sharp} from the thresholds below, and
 * the combined grade is the higher of the two. The pair of readings kept is the one with the highest
 * combined grade, then the widest swing. The reading, the map and the station point all carry it.
 *
 * @param fromDeg     the direction it was
 * @param toDeg       the direction it is
 * @param swingDeg    the angle between them, 0 to 180
 * @param fromKmh     the speed it was
 * @param toKmh       the speed it is
 * @param deltaKmh    the speed now minus then: positive is freshening
 * @param overMinutes how long the change took, between the two readings
 * @param swingGrade  the direction's grade, or null under {@link #SWING_SLIGHT_DEG}
 * @param speedGrade  the speed's grade, or null under {@link #SPEED_SLIGHT_KMH}
 * @param grade       the higher of the two
 */
public record WindShift(Instant at, Integer fromDeg, Integer toDeg, int swingDeg, Double fromKmh, Double toKmh,
                        double deltaKmh, long overMinutes, String swingGrade, String speedGrade, String grade) {

    public static final int SWING_SLIGHT_DEG = 30;
    public static final int SWING_MARKED_DEG = 60;
    public static final int SWING_SHARP_DEG = 90;
    public static final double SPEED_SLIGHT_KMH = 10;
    public static final double SPEED_MARKED_KMH = 20;
    public static final double SPEED_SHARP_KMH = 30;
    /** Below this the direction is not compared: a vane in a calm points anywhere. */
    public static final double CALM_KMH = 8;

    /**
     * The change in a station's recent readings, newest first, or empty when there is none worth a word.
     */
    public static Optional<WindShift> of(List<Observation> newestFirst) {
        if (newestFirst == null || newestFirst.size() < 2) {
            return Optional.empty();
        }
        Observation now = newestFirst.getFirst();
        if (now.at() == null) {
            return Optional.empty();
        }
        WindShift best = null;
        for (int i = 1; i < newestFirst.size(); i++) {
            Observation then = newestFirst.get(i);
            if (then.at() == null) {
                continue;
            }
            long minutes = Math.max(1, Duration.between(then.at(), now.at()).toMinutes());
            int swing = 0;
            boolean directionKnown = now.windDirectionDeg() != null && then.windDirectionDeg() != null
                    && now.windSpeedKmh() != null && then.windSpeedKmh() != null
                    && now.windSpeedKmh() >= CALM_KMH && then.windSpeedKmh() >= CALM_KMH;
            if (directionKnown) {
                swing = angle(then.windDirectionDeg(), now.windDirectionDeg());
            }
            double delta = now.windSpeedKmh() != null && then.windSpeedKmh() != null ? now.windSpeedKmh() - then.windSpeedKmh() : 0;
            String swingGrade = swing >= SWING_SHARP_DEG ? "sharp" : swing >= SWING_MARKED_DEG ? "marked" : swing >= SWING_SLIGHT_DEG ? "slight" : null;
            double size = Math.abs(delta);
            String speedGrade = size >= SPEED_SHARP_KMH ? "sharp" : size >= SPEED_MARKED_KMH ? "marked" : size >= SPEED_SLIGHT_KMH ? "slight" : null;
            if (swingGrade == null && speedGrade == null) {
                continue;
            }
            String grade = rank(swingGrade) >= rank(speedGrade) ? swingGrade : speedGrade;
            WindShift candidate = new WindShift(now.at(), then.windDirectionDeg(), now.windDirectionDeg(), swing,
                    then.windSpeedKmh(), now.windSpeedKmh(), Math.round(delta * 10) / 10.0, minutes, swingGrade, speedGrade, grade);
            if (best == null || rank(grade) > rank(best.grade()) || (rank(grade) == rank(best.grade()) && swing > best.swingDeg())) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The angle between two directions, the short way round.
     */
    public static int angle(int a, int b) {
        int d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    /**
     * A grade's rank: none 0, slight 1, marked 2, sharp 3.
     */
    public static int rank(String grade) {
        return grade == null ? 0 : switch (grade) {
            case "sharp" -> 3;
            case "marked" -> 2;
            case "slight" -> 1;
            default -> 0;
        };
    }

    /**
     * One line: {@code sharp change: 250° → 330° (80°), 12 → 28 km/h (+16), over 40 min}.
     */
    public String describe() {
        StringBuilder s = new StringBuilder(grade).append(" change:");
        if (swingGrade != null) {
            s.append(' ').append(fromDeg).append("° → ").append(toDeg).append("° (").append(swingDeg).append("°)");
        }
        if (speedGrade != null) {
            s.append(swingGrade != null ? ", " : " ").append(Math.round(fromKmh)).append(" → ").append(Math.round(toKmh))
                    .append(" km/h (").append(deltaKmh > 0 ? "+" : "").append(Math.round(deltaKmh)).append(")");
        }
        return s.append(", over ").append(overMinutes).append(" min").toString();
    }
}
