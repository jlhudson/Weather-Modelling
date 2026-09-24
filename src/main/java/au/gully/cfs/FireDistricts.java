package au.gully.cfs;

import au.gully.platform.Fetched;
import au.gully.platform.HttpFetcher;
import au.gully.platform.Nodes;
import au.gully.platform.UpstreamException;
import au.gully.upstreams.Ledger;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * South Australia's fifteen fire ban districts as shapes (W-23), from the CFS's published file, so the
 * district of a station or a point is a point-in-polygon test here. Read when asked and held a day - the
 * boundaries have not moved in years - and never on a clock (W-15); a failed read is tried again after
 * {@link #RETRY}. The file is Web Mercator, one formula from latitude and longitude.
 */
@Slf4j
@Component
public class FireDistricts {

    public static final String ID = "cfs";
    public static final String URL = "https://cfs-feeds.geohub.sa.gov.au/FL/CFS_Custodial_Read/CFS_Fire_Ban_Districts/FeatureServer/0/query";
    public static final Duration LIFE = Duration.ofDays(1);
    public static final Duration RETRY = Duration.ofMinutes(5);

    private static final double MERCATOR_RADIUS = 6_378_137.0;
    private static final GeometryFactory FACTORY = new GeometryFactory();

    private final HttpFetcher http;
    private final Ledger ledger;
    private final boolean enabled;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private volatile List<District> districts = List.of();
    private volatile Instant readAt;
    private volatile Instant triedAt;
    private volatile String failure;

    public FireDistricts(HttpFetcher http, Ledger ledger, au.gully.platform.GullyProperties properties) {
        this.http = http;
        this.ledger = ledger;
        this.enabled = properties.enabled();
    }

    /**
     * One district: its name as the CFS writes it, its polygons in longitude and latitude with their holes, and the same prepared for the test.
     */
    public record District(String name, List<Polygon> polygons, List<PreparedGeometry> shapes) {
    }

    /**
     * The shapes, read now when none are held or they are older than {@link #LIFE}.
     */
    public synchronized List<District> ensure(Instant now) {
        boolean due = readAt == null || Duration.between(readAt, now).compareTo(LIFE) >= 0;
        boolean resting = triedAt != null && failure != null && Duration.between(triedAt, now).compareTo(RETRY) < 0;
        if (enabled && due && !resting) {
            triedAt = now;
            long started = System.nanoTime();
            try {
                Fetched f = http.get(URI.create(URL));
                List<District> fresh = parse(f.body());
                if (fresh.isEmpty()) {
                    throw new UpstreamException("the districts file carried no shapes");
                }
                districts = fresh;
                readAt = now;
                failure = null;
                ledger.record(ID, 0, true, Duration.ofNanos(System.nanoTime() - started), "fire ban districts, " + fresh.size() + " shapes");
                log.info("cfs: {} fire ban districts read", fresh.size());
            } catch (UpstreamException | RuntimeException e) {
                failure = e.getMessage();
                ledger.record(ID, 0, false, Duration.ofNanos(System.nanoTime() - started), "fire ban districts: " + e.getMessage());
                log.warn("cfs: the fire ban districts could not be read: {}", e.getMessage());
            }
        }
        return districts;
    }

    List<District> parse(byte[] json) {
        JsonNode features = Nodes.at(mapper.readTree(json), "features");
        List<District> out = new ArrayList<>();
        if (features == null || !features.isArray()) {
            return out;
        }
        for (JsonNode f : features) {
            String name = Nodes.str(Nodes.at(f, "attributes"), "firebandistrict");
            JsonNode rings = Nodes.at(Nodes.at(f, "geometry"), "rings");
            if (name == null || rings == null || !rings.isArray()) {
                continue;
            }
            // Esri draws an outer ring clockwise and a hole anticlockwise: the sign of a ring's area says which it is.
            List<LinearRing> outers = new ArrayList<>(), holes = new ArrayList<>();
            for (JsonNode ring : rings) {
                List<Coordinate> coords = new ArrayList<>();
                for (JsonNode xy : ring) {
                    if (xy.isArray() && xy.size() >= 2) {
                        double[] ll = fromMercator(xy.get(0).asDouble(), xy.get(1).asDouble());
                        coords.add(new Coordinate(ll[1], ll[0]));
                    }
                }
                if (coords.size() < 4) {
                    continue;
                }
                if (!coords.getFirst().equals2D(coords.getLast())) {
                    coords.add(coords.getFirst());
                }
                try {
                    LinearRing r = FACTORY.createLinearRing(coords.toArray(new Coordinate[0]));
                    (org.locationtech.jts.algorithm.Orientation.isCCW(r.getCoordinates()) ? holes : outers).add(r);
                } catch (IllegalArgumentException e) {
                    log.debug("cfs: a ring of {} did not close: {}", name, e.getMessage());
                }
            }
            List<Polygon> polygons = new ArrayList<>();
            for (LinearRing shell : outers) {
                Polygon plain = FACTORY.createPolygon(shell);
                LinearRing[] inside = holes.stream().filter(h -> plain.covers(FACTORY.createPoint(h.getCoordinateN(0)))).toArray(LinearRing[]::new);
                polygons.add(FACTORY.createPolygon(shell, inside));
            }
            if (!polygons.isEmpty()) {
                out.add(new District(name.trim(), polygons, polygons.stream().map(PreparedGeometryFactory::prepare).toList()));
            }
        }
        return out;
    }

    /**
     * Web Mercator metres to degrees.
     */
    static double[] fromMercator(double x, double y) {
        double lon = Math.toDegrees(x / MERCATOR_RADIUS);
        double lat = Math.toDegrees(Math.atan(Math.sinh(y / MERCATOR_RADIUS)));
        return new double[]{lat, lon};
    }

    /**
     * The district a place is in, reading the shapes if due; empty outside South Australia, or while none can be read.
     */
    public Optional<String> of(double lat, double lon) {
        return within(ensure(Instant.now()), lat, lon);
    }

    static Optional<String> within(List<District> districts, double lat, double lon) {
        Point p = FACTORY.createPoint(new Coordinate(lon, lat));
        for (District d : districts) {
            for (PreparedGeometry g : d.shapes()) {
                if (g.covers(p)) {
                    return Optional.of(d.name());
                }
            }
        }
        return Optional.empty();
    }

    public Instant readAt() {
        return readAt;
    }

    public String failure() {
        return failure;
    }
}
