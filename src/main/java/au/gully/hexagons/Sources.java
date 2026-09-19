package au.gully.hexagons;

import au.gully.bureau.StationReader;
import au.gully.bureau.States;
import au.gully.bureau.WarningsReader;
import au.gully.cfs.Districts;
import au.gully.cfs.Ratings;
import au.gully.platform.GullyProperties;
import au.gully.platform.ReadOutcome;
import au.gully.upstreams.Ledger;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sources, read on request and only on request (W-14): an ask for a hexagon reads the station
 * file of the state it is in when that file has not been checked for a quarter of an hour, the
 * state's warnings when they have not for five minutes, and for a South Australian hexagon the CFS
 * rating hourly and the district shapes daily. Nothing polls. A state nobody asks about for three
 * hours costs nothing for three hours, and its stations are as old as its last ask.
 * <p>
 * Every read is a conditional GET or a small file, so an ask that finds everything fresh costs a
 * few map lookups, and one that finds everything stale costs a few hundred milliseconds once. A
 * station file that is downloaded is processed whole: every station in the state, not the one
 * asked about. Each read that touched the network is written to the upstream ledger with the
 * hexagon that caused it, which is how the console shows that nothing is read but on request.
 */
@Service
public class Sources {

    private final StationReader stations;
    private final WarningsReader warnings;
    private final Ratings ratings;
    private final Districts districts;
    private final Ledger ledger;
    private final GullyProperties properties;
    /** Which hexagon last caused each source to be read, and when. */
    private final Map<String, Trigger> triggers = new ConcurrentHashMap<>();

    public Sources(StationReader stations, WarningsReader warnings, Ratings ratings, Districts districts, Ledger ledger, GullyProperties properties) {
        this.stations = stations;
        this.warnings = warnings;
        this.ratings = ratings;
        this.districts = districts;
        this.ledger = ledger;
        this.properties = properties;
    }

    /**
     * Everything an answer for this hexagon draws on, as fresh as its cadence asks.
     */
    public void ensureFor(Cell cell, Instant now) {
        if (!properties.enabled()) {
            return;
        }
        List<String> states = States.covering(cell.lat(), cell.lon());
        if (properties.sources().bureau()) {
            for (String state : states) {
                long started = System.nanoTime();
                ReadOutcome r = stations.ensure(state, now);
                StationReader.FileState fs = stations.files().get(state);
                note("bureau-" + state, r, started, now, cell, r == ReadOutcome.READ && fs != null
                        ? "stations file, whole: " + fs.stations + " stations" : "stations file");
                started = System.nanoTime();
                note("warnings-" + state, warnings.ensure(state, now), started, now, cell, "warnings feed");
            }
        }
        if (properties.sources().cfs() && states.contains("sa")) {
            long started = System.nanoTime();
            note("cfs-districts", districts.ensure(now), started, now, cell, "district shapes");
            started = System.nanoTime();
            note("cfs-ratings", ratings.ensure(now), started, now, cell, "fire danger ratings");
        }
    }

    /**
     * The station files and warnings of some states, read when due, for a console map that is
     * looking at them: the request is the operator's open map, and the ledger says so.
     *
     * @return the sources that were read whole
     */
    public List<String> ensureStates(java.util.Collection<String> states, Instant now, String why) {
        List<String> read = new ArrayList<>();
        if (!properties.enabled() || !properties.sources().bureau()) {
            return read;
        }
        for (String state : states) {
            long started = System.nanoTime();
            ReadOutcome r = stations.ensure(state, now);
            StationReader.FileState fs = stations.files().get(state);
            noteFor("bureau-" + state, r, started, now, why, r == ReadOutcome.READ && fs != null ? "stations file, whole: " + fs.stations + " stations" : "stations file");
            if (r == ReadOutcome.READ) read.add("bureau-" + state);
            started = System.nanoTime();
            ReadOutcome w = warnings.ensure(state, now);
            noteFor("warnings-" + state, w, started, now, why, "warnings feed");
            if (w == ReadOutcome.READ) read.add("warnings-" + state);
        }
        return read;
    }

    private void note(String source, ReadOutcome outcome, long started, Instant now, Cell cell, String what) {
        noteFor(source, outcome, started, now, "hexagon " + cell.id(), what);
    }

    private void noteFor(String source, ReadOutcome outcome, long started, Instant now, String who, String what) {
        if (!outcome.touched()) {
            return;
        }
        triggers.put(source, new Trigger(who, now, outcome));
        String detail = what + " for " + who + ": " + outcome.name().toLowerCase();
        ledger.record(source, 0, outcome != ReadOutcome.FAILED, Duration.ofNanos(System.nanoTime() - started), detail);
    }

    /**
     * Where each source stands: its cadence, when it was last checked and last read, how much it
     * holds, and which ask caused the last read. Only states that have been asked about appear.
     */
    public List<Status> status(Instant now) {
        List<Status> out = new ArrayList<>();
        for (Map.Entry<String, StationReader.FileState> e : stations.files().entrySet()) {
            StationReader.FileState fs = e.getValue();
            Trigger t = triggers.get("bureau-" + e.getKey());
            out.add(new Status("bureau-" + e.getKey(), "Bureau stations " + e.getKey().toUpperCase(), StationReader.EVERY,
                    fs.checkedAt, fs.readAt, fs.stations, fs.failure, t == null ? null : t.hexagon(), t == null ? null : t.at(),
                    fs.checkedAt == null ? null : fs.checkedAt.plus(StationReader.EVERY)));
        }
        for (String state : StationReader.STATES) {
            Trigger t = triggers.get("warnings-" + state);
            if (t == null) {
                continue;
            }
            out.add(new Status("warnings-" + state, "Bureau warnings " + state.toUpperCase(), WarningsReader.EVERY,
                    warnings.checkedAt(state), warnings.lastPollAt(), warnings.count(), warnings.failures().get(state),
                    t.hexagon(), t.at(), warnings.checkedAt(state) == null ? null : warnings.checkedAt(state).plus(WarningsReader.EVERY)));
        }
        Trigger td = triggers.get("cfs-districts");
        if (td != null || districts.readAt() != null) {
            out.add(new Status("cfs-districts", "CFS district shapes", Districts.EVERY, districts.checkedAt(), districts.readAt(),
                    districts.names().size(), districts.failure(), td == null ? null : td.hexagon(), td == null ? null : td.at(),
                    districts.checkedAt() == null ? null : districts.checkedAt().plus(Districts.EVERY)));
        }
        Trigger tr = triggers.get("cfs-ratings");
        if (tr != null || ratings.readAt() != null) {
            out.add(new Status("cfs-ratings", "CFS fire danger ratings", Ratings.EVERY, ratings.checkedAt(), ratings.readAt(),
                    ratings.all().size(), ratings.failure(), tr == null ? null : tr.hexagon(), tr == null ? null : tr.at(),
                    ratings.checkedAt() == null ? null : ratings.checkedAt().plus(Ratings.EVERY)));
        }
        return out;
    }

    private record Trigger(String hexagon, Instant at, ReadOutcome outcome) {
    }

    /**
     * One source as the console shows it.
     *
     * @param items       what it holds: stations, warnings, districts
     * @param triggeredBy the hexagon whose ask last touched it, and when
     * @param dueAt       when the next ask will check it again
     */
    public record Status(String id, String name, Duration cadence, Instant checkedAt, Instant readAt, Integer items,
                         String failure, String triggeredBy, Instant triggeredAt, Instant dueAt) {
    }
}
