package au.gully.drought;

import au.gully.storage.ConsoleSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The rule that picks a drought's stations (W-22): how many rings out the search goes when no
 * station counts for the hexagon itself, and what a metre of height costs in distance. Two values
 * turned on the console map and kept in the {@code setting} table, so the map they were tuned on
 * survives a restart.
 * <p>
 * A station feeds a hexagon by <em>effective distance</em>: the ground distance plus
 * {@link #kmPer100m()} kilometres for every hundred metres between the station's height and the
 * hexagon's mean elevation. At ten kilometres a hundred metres, Mount Lofty (700 m) sits seventy
 * kilometres "further" from a plains hexagon than the map says, and the plains' own station wins;
 * the Hills hexagons still take the Hills' stations. Rain is taken as measured — orographic rain
 * is real, which is why height should choose the station — and the day's maximum is brought to the
 * hexagon's elevation by the lapse rate. The same distance ranks the spun-up hexagons a hexagon
 * without a drought of its own is interpolated from.
 */
@Slf4j
@Service
public class DroughtRule {

    static final String RINGS_KEY = "drought_rings", HEIGHT_KEY = "drought_km_per_100m";

    /**
     * The defaults: three rings — about 51 km at 17 km hexagons — and ten kilometres a hundred metres.
     */
    public static final int DEFAULT_RINGS = 3;
    public static final double DEFAULT_KM_PER_100M = 10;
    public static final int MAX_RINGS = 6;
    public static final double MAX_KM_PER_100M = 30;

    private final ConsoleSettings settings;
    private final List<Runnable> listeners = new ArrayList<>();
    private volatile int rings = DEFAULT_RINGS;
    private volatile double kmPer100m = DEFAULT_KM_PER_100M;
    private volatile String by;
    private volatile Instant since;

    public DroughtRule(ConsoleSettings settings) {
        this.settings = settings;
    }

    /**
     * A rule with given values and no table behind it: a test's.
     */
    static DroughtRule fixed(int rings, double kmPer100m) {
        DroughtRule r = new DroughtRule(null);
        r.rings = clampRings(rings);
        r.kmPer100m = clampHeight(kmPer100m);
        return r;
    }

    /**
     * Told when either value changes, after it is written.
     */
    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    /**
     * Phase 2: the console's values, if ever set, else the defaults.
     */
    public void rehydrate() {
        rings = settings.read(RINGS_KEY).map(s -> clampRings(parse(s.value(), DEFAULT_RINGS))).orElse(DEFAULT_RINGS);
        kmPer100m = settings.read(HEIGHT_KEY).map(s -> clampHeight(parse(s.value(), DEFAULT_KM_PER_100M))).orElse(DEFAULT_KM_PER_100M);
        ConsoleSettings.Setting latest = settings.read(RINGS_KEY).orElse(null);
        ConsoleSettings.Setting height = settings.read(HEIGHT_KEY).orElse(null);
        if (height != null && (latest == null || height.at().isAfter(latest.at()))) {
            latest = height;
        }
        by = latest == null ? null : latest.by();
        since = latest == null ? null : latest.at();
        log.info("drought rule: {} rings, {} km per 100 m{}", rings, kmPer100m, latest == null ? ", the defaults" : ", set on the console by " + by + " at " + since);
    }

    public int rings() {
        return rings;
    }

    public double kmPer100m() {
        return kmPer100m;
    }

    public String by() {
        return by;
    }

    public Instant since() {
        return since;
    }

    public boolean isDefault() {
        return since == null;
    }

    /**
     * The effective distance a station or a hexagon is from a hexagon: the ground distance plus what the
     * height between them costs. No height on either side, no cost.
     */
    public double effectiveKm(double groundKm, Double fromM, Double toM) {
        if (fromM == null || toM == null) {
            return groundKm;
        }
        return groundKm + kmPer100m * Math.abs(toM - fromM) / 100.0;
    }

    /**
     * Both values set, written and in force, and the listeners told.
     */
    public void set(int newRings, double newKmPer100m, String setBy) {
        Instant now = Instant.now();
        int r = clampRings(newRings);
        double k = clampHeight(newKmPer100m);
        settings.write(RINGS_KEY, String.valueOf(r), setBy, now);
        settings.write(HEIGHT_KEY, String.valueOf(k), setBy, now);
        log.info("drought rule set to {} rings, {} km per 100 m (was {}, {}) by {}", r, k, rings, kmPer100m, setBy);
        rings = r;
        kmPer100m = k;
        by = setBy;
        since = now;
        for (Runnable l : listeners) {
            l.run();
        }
    }

    public static int clampRings(double v) {
        return (int) Math.max(0, Math.min(MAX_RINGS, Math.round(v)));
    }

    public static double clampHeight(double v) {
        return Math.round(Math.max(0, Math.min(MAX_KM_PER_100M, v)) * 2) / 2.0;
    }

    private static double parse(String s, double otherwise) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            log.warn("drought rule: the setting '{}' is not a number; {} stands", s, otherwise);
            return otherwise;
        }
    }
}
