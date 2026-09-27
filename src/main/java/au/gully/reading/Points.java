package au.gully.reading;

import au.gully.bureau.Observation;
import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.fuel.LandCover;
import au.gully.platform.GullyProperties;
import au.gully.reach.Probe;
import au.gully.reach.Reach;
import au.gully.reach.ReachRule;
import au.gully.reach.Terrain;
import au.gully.reach.TerrainSampler;
import au.gully.reach.TerrainStore;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The points of our own (W-7): a place nobody's reach contained is dropped as a station - its
 * terrain sampled and its reach drawn by the same rule, its current from the model, its year of
 * record from the archive - and from then on it is in the register like any station. A later ask
 * inside its reach reuses it: its current is fetched again when it is older than
 * {@link #CURRENT_LIFE}, its record filled for the days missing, nothing else touched. A point no
 * ask has used for {@link #KEEP_UNASKED} is dropped again, record and all; one dropped in the wrong place is deleted
 * by hand from the map (W-40).
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
    private final ReachRule rule;
    private final Forecasts forecasts;
    private final Backfill backfill;
    private final Record record;
    private final GullyProperties properties;
    private final LandCover landCover;
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
     * A point dropped here, now, at the height the ask found: in the register, its terrain sampled, its current fetched and its
     * record filled - each as far as the upstreams allow; what did not come is tried again on the
     * next ask.
     */
    public Station drop(double lat, double lon, Double height, Instant now) {
        String id = idOf(lat, lon);
        Station p = new Station(id, null, String.format(Locale.ROOT, "Point %.4f, %.4f", lat, lon), lat, lon, height,
                properties.zone(), null, "sa", Station.POINT);
        stations.addPoint(p, now);
        log.info("point {} dropped at {}, {} ({} m)", id, lat, lon, height == null ? "?" : Math.round(height));
        sampler.sample(p);
        refresh(p, now, false);
        fill(p, now, false);
        return p;
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
                forget(p);
                n++;
                log.info("point {} ({}) expired: last asked {}", p.id(), p.name(), last);
            }
        }
        return n;
    }

    /**
     * Every point of ours as the map's list gives it (W-42), newest first: where, how high, what the land cover says is
     * there (only if held - the list never waits on the upstream), when it was dropped and last asked, and how many days
     * of record it holds; {@code water} when it is at or below sea level or the land cover is water, the likeliest sign of
     * a click that landed in the sea.
     */
    public List<Map<String, Object>> list() {
        Map<String, Instant> dropped = stations.droppedAt();
        List<Station> all = new ArrayList<>(stations.points());
        all.sort(Comparator.comparing((Station p) -> dropped.get(p.id()), Comparator.nullsLast(Comparator.reverseOrder())));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Station p : all) {
            LandCover.Cover c = landCover.held(p.lat(), p.lon()).orElse(null);
            boolean below = p.heightM() != null && p.heightM() <= 0, wet = c != null && c.water();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("name", p.name());
            m.put("lat", p.lat());
            m.put("lon", p.lon());
            m.put("heightM", p.heightM());
            m.put("landCover", c == null ? null : c.label());
            m.put("fuel", c == null ? null : c.fuel().word);
            m.put("water", below || wet);
            m.put("waterWhy", below && wet ? "below sea level, and the land cover is water" : below ? "at or below sea level" : wet ? "the land cover is water" : null);
            Instant at = dropped.get(p.id()), asked = stations.lastAsked(p.id());
            m.put("droppedAt", at == null ? null : at.toString());
            m.put("lastAskedAt", asked == null ? null : asked.toString());
            m.put("recordDays", record.days(p.id()).size());
            out.add(m);
        }
        return out;
    }

    /**
     * A point deleted by hand (W-40) - a click in the wrong place, the sea say, undone: gone as an expired point goes.
     * A Bureau station is not ours to delete: it is left alone, and false said. The readings kept for a reference (W-27) stay.
     */
    public boolean delete(Station p, String by) {
        if (!p.isPoint()) {
            return false;
        }
        forget(p);
        log.info("point {} ({}) deleted by {}", p.id(), p.name(), by);
        return true;
    }

    /**
     * Everything held of a point: the register and its readings, the terrain, the record, the forecast and the backfill's rest.
     */
    private void forget(Station p) {
        stations.remove(p.id());
        terrain.remove(p.id());
        record.forget(p.id());
        forecasts.forget(p.id());
        backfill.forget(p.id());
    }
}
