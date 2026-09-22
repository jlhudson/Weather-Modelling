package au.gully.reach;

import au.gully.storage.ConsoleSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * The rule a reach is drawn by, as it stands: how far a station reaches, what a hundred metres of
 * climb costs of that, what share of it a hundred metres of descent costs (W-16), and how far a
 * coastal station reaches at most. The defaults until the
 * console sets others; a setting, kept in the {@code setting} table, so the values the map was
 * tuned to survive a restart. Turning the sliders on the map previews another rule without setting it.
 */
@Slf4j
@Service
public class ReachRule {

    static final String KEY_KM = "reach_km";
    static final String KEY_KM_PER_100M = "reach_km_per_100m";
    static final String KEY_COASTAL_KM = "reach_coastal_km";
    static final String KEY_DESCENT_SHARE = "reach_descent_share";

    public static final double DEFAULT_KM = 40;
    public static final double DEFAULT_KM_PER_100M = 10;
    /**
     * The sea breeze's penetration on a summer afternoon, about: the ground a coastal station's air
     * reaches on the days that matter.
     */
    public static final double DEFAULT_COASTAL_KM = 25;
    /**
     * What descending costs against climbing: half. The hills' air drains to the foothills, the
     * plain's air does not climb the scarp.
     */
    public static final double DEFAULT_DESCENT_SHARE = 0.5;
    /**
     * The furthest a reach can be drawn: the terrain is sampled that far.
     */
    public static final double MAX_KM = Terrain.MAX_KM;
    public static final double MIN_KM = 3;
    public static final double MAX_KM_PER_100M = 30;

    private final ConsoleSettings settings;
    private volatile Rule rule = new Rule(DEFAULT_KM, DEFAULT_KM_PER_100M, DEFAULT_COASTAL_KM, DEFAULT_DESCENT_SHARE);
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
        Optional<ConsoleSettings.Setting> coastal = settings.read(KEY_COASTAL_KM);
        Optional<ConsoleSettings.Setting> descent = settings.read(KEY_DESCENT_SHARE);
        rule = Rule.of(km.map(s -> parse(s.value(), DEFAULT_KM)).orElse(DEFAULT_KM),
                per.map(s -> parse(s.value(), DEFAULT_KM_PER_100M)).orElse(DEFAULT_KM_PER_100M),
                coastal.map(s -> parse(s.value(), DEFAULT_COASTAL_KM)).orElse(DEFAULT_COASTAL_KM),
                descent.map(s -> parse(s.value(), DEFAULT_DESCENT_SHARE)).orElse(DEFAULT_DESCENT_SHARE));
        by = km.map(ConsoleSettings.Setting::by).orElse(null);
        since = km.map(ConsoleSettings.Setting::at).orElse(null);
        log.info("reach rule: {} km, 100 m of climb costs {} km, descending {} of that, a coastal station at most {} km{}", rule.reachKm(), rule.kmPer100m(), rule.descentShare(), rule.coastalKm(),
                by == null ? " (default)" : " (set by " + by + " at " + since + ")");
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
    public Rule set(double reachKm, double kmPer100m, double coastalKm, double descentShare, String who, Instant at) {
        Rule r = Rule.of(reachKm, kmPer100m, coastalKm, descentShare);
        settings.write(KEY_KM, String.valueOf(r.reachKm()), who, at);
        settings.write(KEY_KM_PER_100M, String.valueOf(r.kmPer100m()), who, at);
        settings.write(KEY_COASTAL_KM, String.valueOf(r.coastalKm()), who, at);
        settings.write(KEY_DESCENT_SHARE, String.valueOf(r.descentShare()), who, at);
        rule = r;
        by = who;
        since = at;
        log.info("reach rule set by {}: {} km, 100 m of climb costs {} km, descending {} of that, a coastal station at most {} km", who, r.reachKm(), r.kmPer100m(), r.descentShare(), r.coastalKm());
        return r;
    }

    /**
     * One rule: a reach, what height costs of it, and the most a coastal station reaches. Clamped
     * to what the terrain can answer.
     *
     * @param reachKm      how far a station reaches over flat ground
     * @param kmPer100m    how much of that a hundred metres of sustained climb above the station costs
     * @param coastalKm    how far a station with the sea inside {@link Reach#COASTAL_WITHIN_KM} reaches at most
     * @param descentShare what a hundred metres of sustained descent costs against a hundred of climb, 0 to 1
     */
    public record Rule(double reachKm, double kmPer100m, double coastalKm, double descentShare) {

        public static Rule of(double reachKm, double kmPer100m, double coastalKm, double descentShare) {
            return new Rule(clamp(reachKm, MIN_KM, MAX_KM), clamp(kmPer100m, 0, MAX_KM_PER_100M), clamp(coastalKm, MIN_KM, MAX_KM),
                    Math.max(0, Math.min(1, Math.round(descentShare * 20) / 20.0)));
        }

        /**
         * The same, with the descent share as it stands.
         */
        public static Rule of(double reachKm, double kmPer100m, double coastalKm) {
            return of(reachKm, kmPer100m, coastalKm, DEFAULT_DESCENT_SHARE);
        }

        /**
         * The same reach and climb cost, the coastal limit and the descent share as they are.
         */
        public static Rule of(double reachKm, double kmPer100m) {
            return of(reachKm, kmPer100m, DEFAULT_COASTAL_KM);
        }

        private static double clamp(double v, double lo, double hi) {
            if (Double.isNaN(v)) {
                return lo;
            }
            return Math.max(lo, Math.min(hi, Math.round(v * 4) / 4.0));
        }
    }
}
