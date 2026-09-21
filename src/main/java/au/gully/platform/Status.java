package au.gully.platform;

import au.gully.bureau.StationReader;
import au.gully.bureau.StationRegistry;
import au.gully.reach.TerrainSampler;
import au.gully.reach.TerrainStore;
import au.gully.record.Backfill;
import au.gully.record.Record;
import au.gully.upstreams.Upstreams;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The service's own state in one shape: the upstreams with their allowance and breaker, the
 * Bureau's file, and what is held. The diagnostics read carries it as its {@code gully} block and
 * the console pages draw from it.
 */
@Component
@RequiredArgsConstructor
public class Status {

    /**
     * An observation older than this is not "now": the file is ten-minutely, and an hour of silence
     * is a station that has stopped.
     */
    public static final Duration STALE = Duration.ofMinutes(70);

    private final Upstreams upstreams;
    private final StationReader reader;
    private final StationRegistry stations;
    private final TerrainStore terrain;
    private final TerrainSampler sampler;
    private final Record record;
    private final Backfill backfill;

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", Instant.now());
        out.put("upstreams", upstreams.status().stream().map(Status::upstream).toList());
        out.put("bureau", bureau());
        out.put("held", held());
        return out;
    }

    private static Map<String, Object> upstream(Upstreams.Status s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("host", s.host());
        m.put("model", s.model());
        m.put("configured", s.configured());
        m.put("unavailableReason", s.unavailableReason());
        m.put("usable", s.usable());
        m.put("budgetReason", s.budgetReason());
        m.put("bills", s.bills());
        m.put("unitsPerFetch", s.unitsPerFetch());
        m.put("limits", s.limits());
        m.put("spent", s.spent());
        m.put("callsInLastMinute", s.callsInLastMinute());
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("open", s.breaker().openUntil() != null);
        b.put("openUntil", s.breaker().openUntil());
        b.put("consecutiveFailures", s.breaker().consecutiveFailures());
        b.put("lastFailure", s.breaker().lastFailure());
        b.put("lastFailureAt", s.breaker().lastFailureAt());
        b.put("openings", s.breaker().openings());
        m.put("breaker", b);
        return m;
    }

    /**
     * The Bureau's file: when it was checked, read and last failed, and how many stations it names.
     */
    public Map<String, Object> bureau() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", StationReader.STATE);
        m.put("every", StationReader.EVERY.toString());
        m.put("checkedAt", reader.checkedAt());
        m.put("readAt", reader.readAt());
        m.put("failedAt", reader.failedAt());
        m.put("failure", reader.failure());
        m.put("stationsInFile", reader.stationsInFile());
        m.put("lastUpdateAt", stations.lastUpdateAt());
        return m;
    }

    /**
     * What is held: the stations, how many of them have reported inside {@link #STALE}, and how many
     * have their terrain.
     */
    public Map<String, Object> held() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stations", stations.bureau().size());
        m.put("points", stations.points().size());
        m.put("reporting", stations.reporting(Instant.now().minus(STALE)));
        m.put("terrainSampled", terrain.size());
        m.put("terrainPending", sampler.pending().size());
        m.put("terrainFailure", sampler.lastFailure());
        m.put("terrainFailedAt", sampler.lastFailedAt());
        m.put("recordDays", record.dayRows());
        m.put("recordWindows", record.windowRows());
        m.put("backfillPending", backfill.pending().size());
        m.put("backfillFailure", backfill.lastFailure());
        m.put("backfillFailedAt", backfill.lastFailedAt());
        return m;
    }

    public List<Upstreams.Status> upstreams() {
        return upstreams.status();
    }
}
