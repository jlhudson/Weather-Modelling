package au.gully.reach;

import au.gully.storage.ConsoleSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * The rule a reach is drawn by, as it stands: how far a station reaches, and what a hundred metres
 * of height costs of that. The defaults until the console sets others; a setting, kept in the
 * {@code setting} table, so the values the map was tuned to survive a restart. Turning the sliders
 * on the map previews another rule without setting it.
 */
@Slf4j
@Service
public class ReachRule {

    static final String KEY_KM = "reach_km";
    static final String KEY_KM_PER_100M = "reach_km_per_100m";

    public static final double DEFAULT_KM = 40;
    public static final double DEFAULT_KM_PER_100M = 10;
    /**
     * The furthest a reach can be drawn: the terrain is sampled that far.
     */
    public static final double MAX_KM = Terrain.MAX_KM;
    public static final double MIN_KM = 3;
    public static final double MAX_KM_PER_100M = 30;

    private final ConsoleSettings settings;
    private volatile Rule rule = new Rule(DEFAULT_KM, DEFAULT_KM_PER_100M);
    private volatile String by;
    private volatile Instant since;

    public ReachRule(ConsoleSettings settings) {
        this.settings = settings;
    }

    /**
     * The console's values, if ever set, else the defaults.
     */
    public void rehydrate() {
        if (settings == null) {
            return;
        }
        Optional<ConsoleSettings.Setting> km = settings.read(KEY_KM);
        Optional<ConsoleSettings.Setting> per = settings.read(KEY_KM_PER_100M);
        double reachKm = km.map(s -> parse(s.value(), DEFAULT_KM)).orElse(DEFAULT_KM);
        double kmPer100m = per.map(s -> parse(s.value(), DEFAULT_KM_PER_100M)).orElse(DEFAULT_KM_PER_100M);
        rule = Rule.of(reachKm, kmPer100m);
        by = km.map(ConsoleSettings.Setting::by).orElse(null);
        since = km.map(ConsoleSettings.Setting::at).orElse(null);
        log.info("reach rule: {} km, 100 m costs {} km{}", rule.reachKm(), rule.kmPer100m(), by == null ? " (default)" : " (set by " + by + " at " + since + ")");
    }

    private static double parse(String s, double otherwise) {
        try {
            return Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return otherwise;
        }
    }

    public Rule current() {
        return rule;
    }

    public String by() {
        return by;
    }

    public Instant since() {
        return since;
    }

    /**
     * Make a rule the rule: written, so a restart keeps it.
     */
    public Rule set(double reachKm, double kmPer100m, String who, Instant at) {
        Rule r = Rule.of(reachKm, kmPer100m);
        settings.write(KEY_KM, String.valueOf(r.reachKm()), who, at);
        settings.write(KEY_KM_PER_100M, String.valueOf(r.kmPer100m()), who, at);
        rule = r;
        by = who;
        since = at;
        log.info("reach rule set by {}: {} km, 100 m costs {} km", who, r.reachKm(), r.kmPer100m());
        return r;
    }

    /**
     * One rule: a reach, and what height costs of it. Clamped to what the terrain can answer.
     *
     * @param reachKm    how far a station reaches over flat ground
     * @param kmPer100m  how much of that a hundred metres of height above or below the station costs
     */
    public record Rule(double reachKm, double kmPer100m) {

        public static Rule of(double reachKm, double kmPer100m) {
            return new Rule(clamp(reachKm, MIN_KM, MAX_KM), clamp(kmPer100m, 0, MAX_KM_PER_100M));
        }

        private static double clamp(double v, double lo, double hi) {
            if (Double.isNaN(v)) {
                return lo;
            }
            return Math.max(lo, Math.min(hi, Math.round(v * 4) / 4.0));
        }
    }
}
