package au.gully.hexagons;

import au.gully.storage.ConsoleSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleConsumer;

/**
 * The station reach as it stands (W-18): how far outside a hexagon a Bureau station still counts
 * as the hexagon's own, in kilometres from the hexagon's edge. {@link Grid#DEFAULT_STATION_REACH_KM}
 * until the console sets another; a setting, kept in the {@code setting} table, so the value the
 * map was tuned to survives a restart. Turning it is what the map's coverage layer is for: every
 * hexagon within one station's reach has a "now" from the ground for free, within two or more has
 * them blended, and the count says which before anything is asked for.
 * <p>
 * A change drops the register's cached reaches ({@link #onChange}) and the console then re-links
 * every hexagon to its stations and gives each station its hexagons at the new distance
 * ({@code HexagonStore.stationsChanged}); it changes no hexagon's id, so nothing is reset. The
 * hexagons a wider reach created stay when it narrows again.
 */
@Slf4j
@Service
public class Reach {

    /**
     * The key the value is held under in {@code setting}.
     */
    static final String KEY = "station_reach_km";

    /**
     * The furthest the console may set: beyond two rings of 17 km hexagons a station is not this
     * hexagon's weather, it is the region's, and that is what the neighbours' blend is for.
     */
    public static final double MAX_KM = 30;

    private final ConsoleSettings settings;
    private final List<DoubleConsumer> listeners = new ArrayList<>();
    private volatile double km = Grid.DEFAULT_STATION_REACH_KM;
    private volatile String by;
    private volatile Instant since;

    public Reach(ConsoleSettings settings) {
        this.settings = settings;
    }

    /**
     * Told when the reach changes, with the new value, after it is written.
     */
    public void onChange(DoubleConsumer listener) {
        listeners.add(listener);
    }

    /**
     * Phase 2: the console's value, if one was ever set, else the default.
     */
    public void rehydrate() {
        Optional<ConsoleSettings.Setting> row = settings.read(KEY);
        if (row.isEmpty()) {
            km = Grid.DEFAULT_STATION_REACH_KM;
            by = null;
            since = null;
            log.info("station reach: {} km, the default", km);
            return;
        }
        try {
            km = clamp(Double.parseDouble(row.get().value().trim()));
        } catch (NumberFormatException e) {
            log.warn("station reach: the setting '{}' is not a number; the default {} km stands", row.get().value(), Grid.DEFAULT_STATION_REACH_KM);
            km = Grid.DEFAULT_STATION_REACH_KM;
        }
        by = row.get().by();
        since = row.get().at();
        log.info("station reach: {} km, set on the console by {} at {}", km, by, since);
    }

    /**
     * The reach in force, in kilometres from a hexagon's edge.
     */
    public double km() {
        return km;
    }

    /**
     * Who set it and when; both null while the default stands.
     */
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
     * A new reach, written and in force, and the listeners told. Held to {@code 0..}{@link #MAX_KM}
     * and to a quarter of a kilometre, which is the step the console's slider moves in.
     *
     * @return the value as set
     */
    public double set(double reachKm, String setBy) {
        double value = clamp(reachKm);
        Instant now = Instant.now();
        settings.write(KEY, String.valueOf(value), setBy, now);
        double before = km;
        km = value;
        by = setBy;
        since = now;
        log.info("station reach set to {} km (was {}) by {}", value, before, setBy);
        for (DoubleConsumer l : listeners) {
            l.accept(value);
        }
        return value;
    }

    public static double clamp(double reachKm) {
        if (Double.isNaN(reachKm)) {
            throw new IllegalArgumentException("the reach must be a number of kilometres");
        }
        return Math.round(Math.max(0, Math.min(MAX_KM, reachKm)) * 4) / 4.0;
    }
}
