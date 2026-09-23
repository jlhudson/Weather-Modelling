package au.gully.reading;

import au.gully.bureau.Observation;
import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.platform.GullyProperties;
import au.gully.platform.UpstreamException;
import au.gully.reach.Probe;
import au.gully.reach.Reach;
import au.gully.reach.ReachRule;
import au.gully.reach.Terrain;
import au.gully.reach.TerrainSampler;
import au.gully.reach.TerrainStore;
import au.gully.reach.TerrainTiles;
import au.gully.record.Backfill;
import au.gully.record.Record;
import au.gully.upstreams.Forecast;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The points of our own (W-7): a place nobody's reach contained is dropped as a station - its
 * terrain sampled and its reach drawn by the same rule, its current from the model, its year of
 * record from the archive - and from then on it is in the register like any station. A later ask
 * inside its reach reuses it: its current is fetched again when it is older than
 * {@link #CURRENT_LIFE}, its record filled for the days missing, nothing else touched. A point no
 * ask has used for {@link #KEEP_UNASKED} is dropped again, record and all.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Points {

    /**
     * How long the model's current at a point stands before an ask fetches it again.
     */
    public static final Duration CURRENT_LIFE = Duration.ofHours(1);
    public static final Duration KEEP_UNASKED = Record.KEEP;
    /**
     * How near an ask must be to a point whose terrain is not sampled yet for the point to answer it.
     */
    public static final double UNSAMPLED_WITHIN_KM = 1;

    private final StationRegistry stations;
    private final TerrainStore terrain;
    private final TerrainSampler sampler;
    private final TerrainTiles tiles;
    private final ReachRule rule;
    private final Forecasts forecasts;
    private final Backfill backfill;
    private final Record record;
    private final GullyProperties properties;
    private final GeometryFactory geometry = new GeometryFactory();

    /**
     * The id a point gets: its position to four decimals, hashed, so the same place is the same point.
     */
    public static String idOf(double lat, double lon) {
        String key = String.format(Locale.ROOT, "%.4f,%.4f", lat, lon);
        return "p-" + Integer.toHexString(key.hashCode() & 0x7fffffff);
    }

    /**
     * A point of ours whose reach contains the place, nearest first, if any.
     */
    public Optional<Station> within(double lat, double lon) {
        org.locationtech.jts.geom.Point here = geometry.createPoint(new Coordinate(lon, lat));
        Station best = null;
        double bestKm = Double.MAX_VALUE;
        for (Station p : stations.points()) {
            Terrain t = terrain.get(p.id()).orElse(null);
            double km = au.gully.reach.Geo.distanceKm(lat, lon, p.lat(), p.lon());
            // A point whose terrain never came still answers for its own place, until it does.
            boolean contains = t == null ? km <= UNSAMPLED_WITHIN_KM : Probe.polygon(Reach.of(t, rule.current())).contains(here);
            if (contains) {
                if (km < bestKm) {
                    bestKm = km;
                    best = p;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * A point dropped here, now: in the register, its terrain sampled, its current fetched and its
     * record filled - each as far as the upstreams allow; what did not come is tried again on the
     * next ask.
     */
    public Station drop(double lat, double lon, Instant now) {
        String id = idOf(lat, lon);
        Double height = null;
        try {
            height = tiles.elevations(List.of(new double[]{lat, lon})).elevations().getFirst();
        } catch (UpstreamException | RuntimeException e) {
            log.debug("point {}: no height ({})", id, e.getMessage());
        }
        Station p = new Station(id, null, String.format(Locale.ROOT, "Point %.4f, %.4f", lat, lon), lat, lon, height,
                properties.zone(), null, "sa", Station.POINT);
        stations.addPoint(p, now);
        log.info("point {} dropped at {}, {} ({} m)", id, lat, lon, height == null ? "?" : Math.round(height));
        sampler.sample(p);
        refresh(p, now);
        fill(p, now);
        return p;
    }

    /**
     * A point asked about: its current fetched again when it is older than its life, its record
     * filled for the days missing, and the ask remembered.
     */
    public void use(Station p, Instant now) {
        use(p, now, false);
    }

    /**
     * A point asked about; forced (W-13), its current is fetched again however young it is, and
     * every day its record is missing is asked for, whatever the backfill's rest.
     */
    public Used use(Station p, Instant now, boolean force) {
        stations.touch(p.id(), now);
        if (!terrain.current(p.id(), p.lat(), p.lon())) {
            sampler.sample(p);
        }
        boolean fetched = refresh(p, now, force);
        int days = fill(p, now, force);
        return new Used(fetched, days);
    }

    /**
     * What an ask brought a point: whether the model's current was fetched, and how many days of record.
     */
    public record Used(boolean currentFetched, int daysFilled) {
    }

    /**
     * Whether the model's current at a point is still good.
     */
    public boolean currentLives(Station p, Instant now) {
        Observation o = stations.latest(p.id()).orElse(null);
        return o != null && o.at() != null && Duration.between(o.at(), now).compareTo(CURRENT_LIFE) < 0;
    }

    private void refresh(Station p, Instant now) {
        refresh(p, now, false);
    }

    private boolean refresh(Station p, Instant now, boolean force) {
        if (!force && currentLives(p, now)) {
            return false;
        }
        // One fetch for the point's current and its forecast (W-20), kept as the point's.
        Instant before = forecasts.held(p.id()).map(Forecast::fetchedAt).orElse(null);
        Forecast f = forecasts.of(p, now, true).orElse(null);
        if (f == null || f.fetchedAt() == null || f.fetchedAt().equals(before)) {
            log.warn("point {}: no current", p.id());
            return false;
        }
        Observation o = PointCurrent.of(p.id(), f, Record.zoneOf(p));
        if (o != null) {
            stations.acceptModel(p, o);
            return true;
        }
        return false;
    }

    private void fill(Station p, Instant now) {
        fill(p, now, false);
    }

    private int fill(Station p, Instant now, boolean force) {
        Backfill.Range r = backfill.wants(p, now, force);
        return r == null ? 0 : backfill.fill(p, r, now);
    }

    /**
     * The points no ask has used for {@link #KEEP_UNASKED}, dropped: the register, the terrain, the
     * record.
     */
    public int expire(Instant now) {
        int n = 0;
        for (Station p : stations.points()) {
            Instant last = stations.lastAsked(p.id());
            if (last == null || Duration.between(last, now).compareTo(KEEP_UNASKED) > 0) {
                stations.remove(p.id());
                terrain.remove(p.id());
                record.forget(p.id());
                forecasts.forget(p.id());
                n++;
                log.info("point {} ({}) expired: last asked {}", p.id(), p.name(), last);
            }
        }
        return n;
    }
}
