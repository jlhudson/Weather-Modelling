package au.gully.reach;

import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.upstreams.OpenMeteo;
import au.gully.upstreams.Upstreams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Samples the terrain around a station from Open-Meteo's elevation model: {@link Terrain#POINTS}
 * points, a hundred to a call, on Open-Meteo's budget. Done once per station, in the background,
 * one station a tick, so the day's allowance is touched lightly and a new station in the file is
 * picked up on its own; and on request from the console for one station now.
 * <p>
 * A station is sampled whole or not at all: a call that fails abandons the station for this tick,
 * and the breaker decides when Open-Meteo is asked again.
 */
@Slf4j
@Component
public class TerrainSampler {

    /**
     * How often the background job looks for a station without terrain.
     */
    public static final Duration EVERY = Duration.ofSeconds(15);

    /**
     * What one station costs: the calls its points take at a hundred each.
     */
    public static final int CALLS_PER_STATION = (Terrain.POINTS + OpenMeteo.ELEVATION_POINTS_PER_CALL - 1) / OpenMeteo.ELEVATION_POINTS_PER_CALL;

    private final StationRegistry stations;
    private final TerrainStore store;
    private final Upstreams upstreams;
    private volatile String lastFailure;
    private volatile Instant lastFailedAt;

    public TerrainSampler(StationRegistry stations, TerrainStore store, Upstreams upstreams) {
        this.stations = stations;
        this.store = store;
        this.upstreams = upstreams;
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
        double[] values = new double[points.length];
        int calls = 0;
        for (int from = 0; from < points.length; from += OpenMeteo.ELEVATION_POINTS_PER_CALL) {
            int to = Math.min(points.length, from + OpenMeteo.ELEVATION_POINTS_PER_CALL);
            List<double[]> chunk = Arrays.asList(points).subList(from, to);
            Optional<List<Double>> answer = upstreams.elevation(chunk, s.id() + " " + (calls + 1) + "/" + CALLS_PER_STATION);
            if (answer.isEmpty()) {
                lastFailure = "elevation call " + (calls + 1) + " of " + CALLS_PER_STATION + " for " + s.id() + " not answered";
                lastFailedAt = Instant.now();
                log.warn("terrain {}: {}; the station is tried again later", s.id(), lastFailure);
                return Optional.empty();
            }
            List<Double> got = answer.get();
            for (int i = 0; i < got.size(); i++) {
                Double v = got.get(i);
                values[from + i] = v == null ? Double.NaN : v;
            }
            calls++;
        }
        double own = values[0];
        if (Double.isNaN(own)) {
            // The model has nothing at the station itself: the Bureau's height stands in.
            own = s.heightM() == null ? 0 : s.heightM();
        }
        Terrain t = new Terrain(s.id(), s.lat(), s.lon(), own, Arrays.copyOfRange(values, 1, values.length), Instant.now(), calls);
        store.put(t);
        lastFailure = null;
        log.info("terrain {} ({}): sampled, {} m at the station, {} calls", s.id(), s.name(), Math.round(own), calls);
        return Optional.of(t);
    }

    public String lastFailure() {
        return lastFailure;
    }

    public Instant lastFailedAt() {
        return lastFailedAt;
    }
}
