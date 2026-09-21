package au.gully.reach;

import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.platform.UpstreamException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Samples the terrain around a station from the elevation tiles ({@link TerrainTiles}):
 * {@link Terrain#POINTS} points read off some fifteen tiles. Done once per station, in the
 * background, one station a tick, so a new station in the file is picked up on its own; and on
 * request from the console for one station now.
 * <p>
 * A station is sampled whole or not at all: a tile that cannot be fetched abandons the station for
 * this tick, and it is tried again on a later one.
 */
@Slf4j
@Component
public class TerrainSampler {

    /**
     * How often the background job looks for a station without terrain.
     */
    public static final Duration EVERY = Duration.ofSeconds(15);

    private final StationRegistry stations;
    private final TerrainStore store;
    private final TerrainTiles tiles;
    private volatile String lastFailure;
    private volatile Instant lastFailedAt;

    public TerrainSampler(StationRegistry stations, TerrainStore store, TerrainTiles tiles) {
        this.stations = stations;
        this.store = store;
        this.tiles = tiles;
    }

    /**
     * The stations without terrain, or whose terrain was sampled somewhere else.
     */
    public List<Station> pending() {
        List<Station> out = new ArrayList<>();
        for (Station s : stations.all()) {
            if (!store.current(s.id(), s.lat(), s.lon())) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * One tick of the background job: the first pending station, sampled.
     *
     * @return whether a station was sampled
     */
    public boolean tick() {
        List<Station> pending = pending();
        if (pending.isEmpty()) {
            return false;
        }
        return sample(pending.getFirst()).isPresent();
    }

    /**
     * One station, sampled now and kept.
     */
    public Optional<Terrain> sample(Station s) {
        double[][] points = Terrain.points(s.lat(), s.lon());
        TerrainTiles.Sampled sampled;
        try {
            sampled = tiles.elevations(Arrays.asList(points));
        } catch (UpstreamException | RuntimeException e) {
            lastFailure = e.getMessage();
            lastFailedAt = Instant.now();
            log.warn("terrain {}: {}; the station is tried again later", s.id(), lastFailure);
            return Optional.empty();
        }
        double[] values = new double[points.length];
        for (int i = 0; i < values.length; i++) {
            Double v = sampled.elevations().get(i);
            values[i] = v == null ? Double.NaN : v;
        }
        double own = values[0];
        if (Double.isNaN(own)) {
            // The model has nothing at the station itself: the Bureau's height stands in.
            own = s.heightM() == null ? 0 : s.heightM();
        }
        Terrain t = new Terrain(s.id(), s.lat(), s.lon(), own, Arrays.copyOfRange(values, 1, values.length), Instant.now(), sampled.tilesFetched());
        store.put(t);
        lastFailure = null;
        log.info("terrain {} ({}): sampled, {} m at the station, {} tiles fetched", s.id(), s.name(), Math.round(own), sampled.tilesFetched());
        return Optional.of(t);
    }

    public String lastFailure() {
        return lastFailure;
    }

    public Instant lastFailedAt() {
        return lastFailedAt;
    }
}
