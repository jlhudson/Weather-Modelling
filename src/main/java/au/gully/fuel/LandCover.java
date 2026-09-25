package au.gully.fuel;

import au.gully.platform.Fetched;
import au.gully.platform.HttpFetcher;
import au.gully.platform.Nodes;
import au.gully.platform.UpstreamException;
import au.gully.storage.Db;
import au.gully.upstreams.Ledger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The land cover at a place (W-38): Digital Earth Australia's Landsat land cover, Collection 3 - the latest year it holds
 * - read off its public map service one pixel at a time (a WMS GetFeatureInfo; no key, no published limit). Looked up
 * once per cell of {@link #CELL_DEG} and kept in {@code land_cover} and in memory: the land cover is annual, and a
 * station's is fetched once in its life. A failed lookup is tried again on the next ask after {@link #RETRY}.
 */
@Slf4j
@Service
public class LandCover {

    public static final String ID = "dea-landcover";
    public static final String LAYER = "ga_ls_landcover_c3";
    public static final double CELL_DEG = 0.002;
    public static final Duration RETRY = Duration.ofMinutes(10);

    private final JdbcClient db;
    private final HttpFetcher http;
    private final Ledger ledger;
    private final boolean enabled;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<String, Cover> held = new ConcurrentHashMap<>();
    private final Map<String, Instant> failed = new ConcurrentHashMap<>();

    public LandCover(JdbcClient db, HttpFetcher http, Ledger ledger, au.gully.platform.GullyProperties properties) {
        this.db = db;
        this.http = http;
        this.ledger = ledger;
        this.enabled = properties.enabled();
    }

    /**
     * One place's land cover: the classes, the label, and the year it is for.
     */
    public record Cover(Integer level3, Integer level4, String label, Integer year) {

        public Fuel.Kind fuel() {
            return Fuel.of(level3, label);
        }
    }

    public void rehydrate() {
        held.clear();
        db.sql("select cell, level3, level4, label, year from land_cover").query().listOfRows().forEach(row -> held.put((String) row.get("cell"),
                new Cover(Db.integer(row.get("level3")), Db.integer(row.get("level4")), (String) row.get("label"), Db.integer(row.get("year")))));
        log.info("land cover rehydrated: {} cells", held.size());
    }

    static String cell(double lat, double lon) {
        return String.format(Locale.ROOT, "%.3f,%.3f", Math.round(lat / CELL_DEG) * CELL_DEG, Math.round(lon / CELL_DEG) * CELL_DEG);
    }

    /**
     * The land cover at a place, looked up if not held; empty where it cannot be.
     */
    public Optional<Cover> at(double lat, double lon) {
        String key = cell(lat, lon);
        Cover c = held.get(key);
        if (c != null) {
            return Optional.of(c);
        }
        Instant last = failed.get(key);
        if (!enabled || (last != null && Duration.between(last, Instant.now()).compareTo(RETRY) < 0)) {
            return Optional.empty();
        }
        double d = CELL_DEG / 4;
        String url = "https://ows.dea.ga.gov.au/?service=WMS&version=1.3.0&request=GetFeatureInfo&layers=" + LAYER + "&query_layers=" + LAYER
                + "&styles=&crs=EPSG:4326&bbox=" + fmt(lat - d) + "," + fmt(lon - d) + "," + fmt(lat + d) + "," + fmt(lon + d)
                + "&width=3&height=3&i=1&j=1&info_format=application/json";
        long started = System.nanoTime();
        try {
            Fetched f = http.get(URI.create(url));
            Cover cover = parse(mapper.readTree(f.body()));
            if (cover == null) {
                throw new UpstreamException(ID + ": no land cover at " + key);
            }
            held.put(key, cover);
            db.sql("insert into land_cover (cell, level3, level4, label, year, fetched_at) values (:c, :l3, :l4, :label, :y, :at) on conflict (cell) do nothing")
                    .param("c", key).param("l3", cover.level3()).param("l4", cover.level4()).param("label", cover.label()).param("y", cover.year())
                    .param("at", Db.ts(Instant.now())).update();
            ledger.record(ID, 0, true, Duration.ofNanos(System.nanoTime() - started), "land cover " + key + ": " + cover.label());
            failed.remove(key);
            return Optional.of(cover);
        } catch (UpstreamException | RuntimeException e) {
            failed.put(key, Instant.now());
            ledger.record(ID, 0, false, Duration.ofNanos(System.nanoTime() - started), "land cover " + key + ": " + e.getMessage());
            log.warn("land cover {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The newest year in a GetFeatureInfo answer; null where it carries none.
     */
    static Cover parse(JsonNode root) {
        JsonNode features = Nodes.at(root, "features");
        if (features == null || !features.isArray() || features.isEmpty()) {
            return null;
        }
        JsonNode data = Nodes.at(Nodes.at(features.get(0), "properties"), "data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            return null;
        }
        JsonNode newest = null;
        for (JsonNode d : data) {
            String t = Nodes.str(d, "time");
            if (newest == null || (t != null && t.compareTo(String.valueOf(Nodes.str(newest, "time"))) > 0)) {
                newest = d;
            }
        }
        JsonNode bands = Nodes.at(newest, "bands"), description = Nodes.at(newest, "description");
        String time = Nodes.str(newest, "time");
        return new Cover(Nodes.integer(bands, "level3"), Nodes.integer(bands, "level4"), Nodes.str(description, "level4_label"),
                time == null || time.length() < 4 ? null : Integer.valueOf(time.substring(0, 4)));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.5f", v);
    }
}
