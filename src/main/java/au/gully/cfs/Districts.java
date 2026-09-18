package au.gully.cfs;

import au.gully.platform.Fetched;
import au.gully.platform.HttpFetcher;
import au.gully.platform.Nodes;
import au.gully.platform.UpstreamException;
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
import java.util.*;

/**
 * The fifteen fire ban districts as shapes, from the CFS's published file, so a hexagon's district is
 * a point-in-polygon test here rather than a query to the GeoHub for every hexagon (docs/06 item 4).
 * Read once a day: the boundaries have not moved in years. The file is in Web Mercator, which is one
 * formula away from latitude and longitude.
 */
@Slf4j
@Component
public class Districts {

    public static final Duration EVERY = Duration.ofDays(1);
    public static final String URL = "https://cfs-feeds.geohub.sa.gov.au/FL/CFS_Custodial_Read/CFS_Fire_Ban_Districts/FeatureServer/0/query";

    private static final double MERCATOR_RADIUS = 6_378_137.0;
    private static final GeometryFactory FACTORY = new GeometryFactory();

    private final HttpFetcher http;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private volatile List<District> districts = List.of();
    private volatile Instant readAt;
    private volatile String failure;

    public Districts(HttpFetcher http) {
        this.http = http;
    }

    public int poll() {
        try {
            Fetched f = http.get(URI.create(URL));
            List<District> fresh = parse(f.body());
            if (fresh.isEmpty()) {
                throw new UpstreamException("the districts file carried no shapes");
            }
            districts = fresh;
            readAt = Instant.now();
            failure = null;
            log.info("cfs districts: {} shapes read", fresh.size());
        } catch (UpstreamException | RuntimeException e) {
            if (failure == null) {
                log.warn("cfs districts: {}", e.getMessage());
            }
            failure = e.getMessage();
        }
        return districts.size();
    }

    List<District> parse(byte[] json) {
        JsonNode root = mapper.readTree(json);
        JsonNode features = Nodes.at(root, "features");
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
            List<PreparedGeometry> shapes = new ArrayList<>();
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
                    LinearRing shell = FACTORY.createLinearRing(coords.toArray(new Coordinate[0]));
                    Polygon polygon = FACTORY.createPolygon(shell);
                    shapes.add(PreparedGeometryFactory.prepare(polygon));
                } catch (IllegalArgumentException e) {
                    log.debug("cfs districts: a ring of {} did not close: {}", name, e.getMessage());
                }
            }
            if (!shapes.isEmpty()) {
                out.add(new District(Ratings.normalise(name), shapes));
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
     * The fire ban district a point is in, or empty outside South Australia (or before the file is read).
     */
    public Optional<String> districtOf(double lat, double lon) {
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

    public List<String> names() {
        return districts.stream().map(District::name).sorted().toList();
    }

    public boolean loaded() {
        return !districts.isEmpty();
    }

    public Instant readAt() {
        return readAt;
    }

    public String failure() {
        return failure;
    }

    record District(String name, List<PreparedGeometry> shapes) {
    }
}
