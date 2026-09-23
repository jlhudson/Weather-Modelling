package au.gully.reach;

import au.gully.storage.ConsoleSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * The rule a reach is drawn by, as it stands: how far a station reaches, what a hundred metres of
 * climb costs of that, what share of it a hundred metres of descent costs (W-16), and how much the
 * reach grows for every hundred kilometres a station is from the sea (W-19). The defaults until the
 * console sets others; a setting, kept in the {@code setting} table, so the values the map was
 * tuned to survive a restart. Turning the sliders on the map previews another rule without setting it.
 */
@Slf4j
@Service
public class ReachRule {

    static final String KEY_KM = "reach_km";
    static final String KEY_KM_PER_100M = "reach_km_per_100m";
    static final String KEY_INLAND_PCT = "reach_inland_pct";
    static final String KEY_DESCENT_SHARE = "reach_descent_share";

    public static final double DEFAULT_KM = 40;
    public static final double DEFAULT_KM_PER_100M = 10;
    /**
     * How much a reach grows for every hundred kilometres from the sea (W-19), in per cent: a fifth. At
     * 40 km, a station on the coast reaches 40, Renmark about 56, Coober Pedy about 80, Oodnadatta about 90.
     */
    public static final double DEFAULT_INLAND_PCT = 20;
    public static final double MAX_INLAND_PCT = 50;
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
    private volatile Rule rule = new Rule(DEFAULT_KM, DEFAULT_KM_PER_100M, DEFAULT_INLAND_PCT, DEFAULT_DESCENT_SHARE);
    private volatile String by;
    private volatile Instant since;

    public ReachRule(ConsoleSettings settings) {
        this.settings = settings;
    }

    /**
     * The console's values, if ever set, else the defaults.
     */
    public void rehydrate() {
        Optional<ConsoleSettings.Setting> km = settings.read(KEY_KM);
        Optional<ConsoleSettings.Setting> per = settings.read(KEY_KM_PER_100M);
        Optional<ConsoleSettings.Setting> inland = settings.read(KEY_INLAND_PCT);
        Optional<ConsoleSettings.Setting> descent = settings.read(KEY_DESCENT_SHARE);
        rule = Rule.of(km.map(s -> parse(s.value(), DEFAULT_KM)).orElse(DEFAULT_KM),
                per.map(s -> parse(s.value(), DEFAULT_KM_PER_100M)).orElse(DEFAULT_KM_PER_100M),
                inland.map(s -> parse(s.value(), DEFAULT_INLAND_PCT)).orElse(DEFAULT_INLAND_PCT),
                descent.map(s -> parse(s.value(), DEFAULT_DESCENT_SHARE)).orElse(DEFAULT_DESCENT_SHARE));
        by = km.map(ConsoleSettings.Setting::by).orElse(null);
        since = km.map(ConsoleSettings.Setting::at).orElse(null);
        log.info("reach rule: {} km, 100 m of climb costs {} km, descending {} of that, {} % more for every 100 km from the sea{}", rule.reachKm(), rule.kmPer100m(), rule.descentShare(), rule.inlandPct(),
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
    public Rule set(double reachKm, double kmPer100m, double inlandPct, double descentShare, String who, Instant at) {
        Rule r = Rule.of(reachKm, kmPer100m, inlandPct, descentShare);
        settings.write(KEY_KM, String.valueOf(r.reachKm()), who, at);
        settings.write(KEY_KM_PER_100M, String.valueOf(r.kmPer100m()), who, at);
        settings.write(KEY_INLAND_PCT, String.valueOf(r.inlandPct()), who, at);
        settings.write(KEY_DESCENT_SHARE, String.valueOf(r.descentShare()), who, at);
        rule = r;
        by = who;
        since = at;
        log.info("reach rule set by {}: {} km, 100 m of climb costs {} km, descending {} of that, {} % more for every 100 km from the sea", who, r.reachKm(), r.kmPer100m(), r.descentShare(), r.inlandPct());
        return r;
    }

    /**
     * One rule: a reach, what height costs of it, and how much it grows with the distance from the sea.
     * Clamped to what the terrain can answer.
     *
     * @param reachKm      how far a station reaches over flat ground at the coast
     * @param kmPer100m    how much of that a hundred metres of sustained climb above the station costs
     * @param inlandPct    how much the reach grows for every hundred kilometres the station is from the sea, in per cent (W-19)
     * @param descentShare what a hundred metres of sustained descent costs against a hundred of climb, 0 to 1
     */
    public record Rule(double reachKm, double kmPer100m, double inlandPct, double descentShare) {

        public static Rule of(double reachKm, double kmPer100m, double inlandPct, double descentShare) {
            return new Rule(clamp(reachKm, MIN_KM, MAX_KM), clamp(kmPer100m, 0, MAX_KM_PER_100M),
                    Double.isNaN(inlandPct) ? 0 : Math.max(0, Math.min(MAX_INLAND_PCT, Math.round(inlandPct))),
                    Math.max(0, Math.min(1, Math.round(descentShare * 20) / 20.0)));
        }

        /**
         * The same reach and climb cost, the inland share and the descent share as they are.
         */
        public static Rule of(double reachKm, double kmPer100m) {
            return of(reachKm, kmPer100m, DEFAULT_INLAND_PCT, DEFAULT_DESCENT_SHARE);
        }

        /**
         * The reach of a station so far from the sea (W-19): the reach, grown by the inland share for
         * every hundred kilometres, to the tenth, and never past what the terrain was sampled to. A
         * station whose distance is not known reaches the reach itself.
         */
        public double reachKmAt(Double inlandKm) {
            if (inlandKm == null || inlandKm <= 0) {
                return reachKm;
            }
            return Math.min(MAX_KM, Math.round(reachKm * (1 + inlandPct / 100 * inlandKm / 100) * 10) / 10.0);
        }

        private static double clamp(double v, double lo, double hi) {
            if (Double.isNaN(v)) {
                return lo;
            }
            return Math.max(lo, Math.min(hi, Math.round(v * 4) / 4.0));
        }
    }
}
