package au.gully.reach;

import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.bureau.StationsFeed;
import au.gully.platform.UpstreamException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A point on the map, and which stations speak for it (W-5): every station whose reach contains
 * the point, nearest first, with what each last said and how far and how high it is from the
 * point; and the nearest few whose reach does not, with why their ray towards the point stopped
 * short. Nothing is blended - this is the ingredients, laid out - and it is what a reading at a
 * point will be made from.
 * <p>
 * The point's own height is one read of the elevation tiles, which are cached from the sampling;
 * a point far from every station may cost one tile.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Probe {

    /**
     * How many stations outside reach are shown, so an omission can be seen.
     */
    public static final int OUTSIDE = 3;

    private final StationRegistry stations;
    private final StationsFeed feed;
    private final TerrainStore terrain;
    private final ReachRule rule;
    private final TerrainTiles tiles;
    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    public Map<String, Object> at(double lat, double lon) {
        Instant now = Instant.now();
        ReachRule.Rule r = rule.current();
        Point here = GEOMETRY.createPoint(new Coordinate(lon, lat));
        Double height = null;
        try {
            height = tiles.elevations(List.of(new double[]{lat, lon})).elevations().getFirst();
        } catch (UpstreamException | RuntimeException e) {
            log.debug("probe {},{}: no height ({})", lat, lon, e.getMessage());
        }
        List<Map<String, Object>> in = new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Station s : stations.all()) {
            Terrain t = terrain.get(s.id()).orElse(null);
            double km = Geo.distanceKm(lat, lon, s.lat(), s.lon());
            if (t == null) {
                if (km <= Terrain.MAX_KM) {
                    Map<String, Object> m = station(s, km, lat, lon, height, now, t, null);
                    m.put("why", "its terrain is not sampled yet");
                    out.add(m);
                }
                continue;
            }
            Reach reach = Reach.of(t, r);
            int b = bearingIndex(Geo.bearingDeg(s.lat(), s.lon(), lat, lon));
            Map<String, Object> m = station(s, km, lat, lon, height, now, t, reach);
            m.put("rayKm", reach.km()[b]);
            m.put("rayCut", reach.cut()[b].name().toLowerCase());
            if (polygon(reach).contains(here)) {
                m.put("margin", Math.round((reach.km()[b] - km) * 10) / 10.0);
                in.add(m);
            } else if (km <= Terrain.MAX_KM) {
                m.put("why", "its ray towards here stops at " + reach.km()[b] + " km, " + why(reach.cut()[b]) + "; the point is " + Math.round(km * 10) / 10.0 + " km away");
                out.add(m);
            }
        }
        in.sort(Comparator.comparingDouble(m -> (Double) m.get("km")));
        out.sort(Comparator.comparingDouble(m -> (Double) m.get("km")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("at", now.toString());
        result.put("lat", lat);
        result.put("lon", lon);
        result.put("heightM", height);
        result.put("water", height != null && height <= 0);
        result.put("rule", Reaches.rule(r));
        result.put("inReach", in);
        result.put("outside", out.subList(0, Math.min(OUTSIDE, out.size())));
        return result;
    }

    private Map<String, Object> station(Station s, double km, double lat, double lon, Double height, Instant now, Terrain t, Reach reach) {
        Map<String, Object> m = new LinkedHashMap<>(feed.properties(s, now));
        m.put("lat", s.lat());
        m.put("lon", s.lon());
        m.put("km", Math.round(km * 10) / 10.0);
        m.put("bearingDeg", (int) Math.round(Geo.bearingDeg(lat, lon, s.lat(), s.lon())));
        Double stationHeight = t == null ? s.heightM() : t.elevationM();
        m.put("elevationM", stationHeight);
        m.put("aboveM", height == null || stationHeight == null ? null : Math.round(stationHeight - height));
        return m;
    }

    public static int bearingIndex(double bearingDeg) {
        return (int) Math.round(bearingDeg / (360.0 / Terrain.BEARINGS)) % Terrain.BEARINGS;
    }

    private static String why(Reach.Cut cut) {
        return switch (cut) {
            case DISTANCE -> "at its reach";
            case HEIGHT -> "cut by height";
            case WATER -> "at the water";
            case UNKNOWN -> "where the model had nothing";
        };
    }

    /**
     * The reach as a JTS polygon in longitude and latitude, for the containment test: the same
     * ring the map draws, so what the map shows inside is inside.
     */
    public static Polygon polygon(Reach r) {
        Coordinate[] coords = new Coordinate[r.ring().length];
        for (int i = 0; i < coords.length; i++) {
            coords[i] = new Coordinate(r.ring()[i][1], r.ring()[i][0]);
        }
        return GEOMETRY.createPolygon(coords);
    }
}
