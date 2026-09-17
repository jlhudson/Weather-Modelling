package au.weather.core;

import lombok.experimental.UtilityClass;

import java.time.Duration;

/**
 * Confining a value to bounds, and moving it between them.
 *
 * <p>Beside {@link Numbers} rather than inside it, because the two make different claims. Rounding is a
 * statement about how far a number is worth reporting to; a bound is a statement about what the number is
 * allowed to be. Folding them together would dilute a class whose whole argument is that rounding is not
 * arithmetic.
 *
 * <p>{@link #clamp} exists because fifteen sites in this codebase write {@code Math.max(lo, Math.min(hi, v))}
 * inline, and that expression fails silently when the bounds arrive the wrong way round: it returns
 * {@code lo} for every input, which looks exactly like a working clamp against a very tight range. The
 * version here says so instead.
 *
 * <p>{@link #ramp} is the shape a governor needs and nothing here had: three points rather than two, so a
 * nominal that does not sit halfway between its floor and its ceiling stays where it was put.
 */
@UtilityClass
public class Ranges {

    /**
     * {@code v} confined to {@code [lo, hi]}.
     *
     * <p>Inverted bounds return {@code lo} and are a programming error rather than an input error, so this
     * throws rather than quietly collapsing the interval — the failure it is guarding against is a caller
     * that swapped two arguments, and a silent collapse is precisely how that survives review.
     */
    public static double clamp(double v, double lo, double hi) {
        require(lo <= hi, lo, hi);
        return v < lo ? lo : Math.min(v, hi);
    }

    /**
     * As above, for a count.
     */
    public static int clamp(int v, int lo, int hi) {
        require(lo <= hi, lo, hi);
        return v < lo ? lo : Math.min(v, hi);
    }

    /**
     * As above, for a duration. Present so a caller tuning a time-to-live does not have to convert to
     * seconds and back, which is the one place a unit mistake would be least visible.
     */
    public static Duration clamp(Duration v, Duration lo, Duration hi) {
        require(lo.compareTo(hi) <= 0, lo.toSeconds(), hi.toSeconds());
        return v.compareTo(lo) < 0 ? lo : v.compareTo(hi) > 0 ? hi : v;
    }

    /**
     * The point a fraction {@code f} of the way from {@code a} to {@code b}, with {@code f} clamped to {@code [0, 1]}.
     */
    public static double lerp(double a, double b, double f) {
        return a + (b - a) * clamp(f, 0.0, 1.0);
    }

    /**
     * A three-point ramp. {@code signal} is clamped to {@code [-1, +1]}: {@code -1} yields {@code floor},
     * {@code 0} yields {@code nominal} exactly, and {@code +1} yields {@code ceiling}.
     *
     * <p>The two halves are interpolated independently, which is the entire reason this is not two calls to
     * {@link #lerp}. A nominal deliberately placed near its floor — as a reuse radius is, because the
     * resting state is the tight one — would otherwise drift toward the midpoint the moment the signal moved.
     *
     * <p><strong>To run a value inversely, swap the ends at the call site:</strong>
     * {@code ramp(ceiling, nominal, floor, signal)}. A boolean parameter would read as
     * {@code ramp(3, 3, 7, s, true)} at the call site, where nobody can see which way it goes.
     */
    public static double ramp(double floor, double nominal, double ceiling, double signal) {
        double s = clamp(signal, -1.0, 1.0);
        return s < 0 ? nominal + (nominal - floor) * s : nominal + (ceiling - nominal) * s;
    }

    /**
     * As above, for a duration, so a tuned interval never round-trips through seconds at the call site.
     */
    public static Duration ramp(Duration floor, Duration nominal, Duration ceiling, double signal) {
        return Duration.ofSeconds(Math.round(
                ramp((double) floor.toSeconds(), nominal.toSeconds(), ceiling.toSeconds(), signal)));
    }

    private static void require(boolean ok, double lo, double hi) {
        if (!ok) {
            throw new IllegalArgumentException("inverted bounds: lo " + lo + " is above hi " + hi);
        }
    }
}
