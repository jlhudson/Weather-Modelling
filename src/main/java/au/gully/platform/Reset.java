package au.gully.platform;

import au.gully.bureau.StationFile;
import au.gully.bureau.StationReader;
import au.gully.bureau.StationRegistry;
import au.gully.reach.TerrainStore;
import au.gully.reach.TerrainTiles;
import au.gully.record.Record;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The start-again button, for testing (W-18): every station, reading, window, day and terrain gone -
 * the Bureau's stations and the points of ours alike - and the service set going again as if newly
 * deployed: the Bureau's file read whole at once, then the housekeeping in the background, which
 * samples every station's terrain and fills the year of record as far as the day's allowance allows.
 * <p>
 * What is kept is what is not the weather: the console login, the API keys, the reach rule on the
 * sliders, the upstream ledger - so the allowances still know what today has spent - and the
 * diagnostics and the access log.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Reset {

    /**
     * The tables emptied, children first: everything the weather is.
     */
    public static final List<String> TABLES = List.of("station_reading", "station_hour6", "station_day", "station_forecast", "terrain", "station");

    private final JdbcClient db;
    private final StationRegistry stations;
    private final StationReader reader;
    private final TerrainStore terrain;
    private final TerrainTiles tiles;
    private final Record record;
    private final au.gully.reading.Forecasts forecasts;
    private final Housekeeping housekeeping;
    private final HttpFetcher http;
    private final TaskScheduler scheduler;
    private final GullyProperties properties;

    private volatile Map<String, Object> last;

    /**
     * What each table holds now.
     */
    public Map<String, Long> rows() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String t : TABLES) {
            out.put(t, db.sql("select count(*) from " + t).query(Long.class).single());
        }
        return out;
    }

    /**
     * Everything the weather is, deleted, and the service started again. The Bureau's timer and the
     * housekeeping are held off while the tables are emptied and the memory read back from them.
     */
    public synchronized Map<String, Object> run(String by) {
        Instant began = Instant.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", began.toString());
        out.put("by", by);
        Map<String, Long> before = rows();
        synchronized (reader) {
            synchronized (housekeeping) {
                db.sql("truncate table " + String.join(", ", TABLES)).update();
                stations.rehydrate();
                terrain.rehydrate();
                record.rehydrate();
                forecasts.rehydrate();
                // The file downloaded whole on the next read, not answered by the Bureau's 304; the tiles fetched again.
                http.forget(URI.create(StationFile.url(StationReader.STATE)));
                tiles.clearCache();
            }
            out.put("deleted", before);
            if (properties.enabled()) {
                boolean read = reader.read();
                out.put("bureauRead", read);
                out.put("bureauStations", reader.stationsInFile());
                if (!read && reader.failure() != null) {
                    out.put("bureauFailure", reader.failure());
                }
            }
        }
        if (properties.enabled()) {
            // The terrain and the year, as a fresh deployment gets them: the housekeeping, now, in the background.
            scheduler.schedule(() -> {
                try {
                    housekeeping.run(Instant.now());
                } catch (RuntimeException e) {
                    log.warn("housekeeping after the reset failed: {}", e.toString());
                }
            }, Instant.now());
            out.put("housekeeping", "started");
        } else {
            out.put("housekeeping", "not run: gully.enabled is false");
        }
        out.put("took", Duration.between(began, Instant.now()).toString());
        last = out;
        log.warn("reset by {}: every station, reading, window, day and terrain deleted {}; {}", by, before, out);
        return out;
    }

    /**
     * What the last reset did, or null since the start.
     */
    public Map<String, Object> last() {
        return last;
    }
}
