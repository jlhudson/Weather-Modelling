package au.weather.geojson;

import lombok.experimental.UtilityClass;
import org.locationtech.jts.geom.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * GeoJSON as plain maps, serialised by Jackson (D-113). A feature with a null geometry is valid GeoJSON:
 * a message with no location is still a message; it is just not on the map.
 *
 * <p>Everything here funnels through {@link #geometry} and {@link #coordinate}, so the one detail that
 * is easy to get wrong and hard to see — <strong>GeoJSON is longitude first</strong>, the opposite of
 * how every other part of this system says a point — is written down exactly once.
 */
@UtilityClass
public class GeoJson {

    public static Map<String, Object> featureCollection(List<Map<String, Object>> features, Map<String, Object> meta) {
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("type", "FeatureCollection");
        fc.put("features", features);
        if (meta != null) {
            fc.putAll(meta);
        }
        return fc;
    }

    /**
     * A point feature, or a feature with no geometry at all when either coordinate is missing.
     */
    public static Map<String, Object> feature(Object id, Double lat, Double lon, Map<String, Object> properties) {
        return feature(id, lat == null || lon == null ? null : point(lat, lon), properties);
    }

    /**
     * A feature with a geometry already built, for the shapes that are not points.
     *
     * <p>Every feature whose geometry is not a point leaves here carrying {@code centroid} and
     * {@code bbox} in its properties: where to hang one marker for the shape, and the box to test a
     * viewport against. Both are emitted <em>here</em>, at the one funnel every layer passes through,
     * because a consumer that needs them has otherwise to walk the coordinates itself — and the first
     * one that did averaged the vertices, which is not a centroid: it drifts towards whichever edge
     * the feed happened to sample densely, and a weather forecast was then fetched for the drifted
     * point. Phase Z §23.2.
     */
    public static Map<String, Object> feature(Object id, Map<String, Object> geometry, Map<String, Object> properties) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "Feature");
        f.put("id", id);
        f.put("geometry", geometry);
        f.put("properties", located(geometry, properties));
        return f;
    }

    /**
     * {@code properties} with {@code centroid} and {@code bbox} added for a non-point geometry. A point
     * gets neither: it is its own centroid and its own box, and saying so twice is payload for nothing.
     * A caller that has already worked out a better centroid than the geometry can give — a detection's
     * reported position against its drawn extent — keeps it; this never overwrites.
     *
     * <p>The map is copied rather than written into: several layers hand over {@code Map.of(...)}.
     */
    private static Map<String, Object> located(Map<String, Object> geometry, Map<String, Object> properties) {
        if (geometry == null || "Point".equals(geometry.get("type"))) {
            return properties;
        }
        double[] box = bbox(geometry);
        if (box == null) {
            return properties;
        }
        Map<String, Object> out = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
        out.putIfAbsent("bbox", List.of(box[0], box[1], box[2], box[3]));
        List<Double> c = centroid(geometry, box);
        if (c != null) {
            out.putIfAbsent("centroid", c);
        }
        return out;
    }

    /**
     * {@code [minLon, minLat, maxLon, maxLat]} over every coordinate of a built geometry, or null when
     * it holds none. Exact — a bounding box is the one figure here with no method to argue about.
     */
    static double[] bbox(Map<String, Object> geometry) {
        double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        boolean[] any = {false};
        walk(geometry, (lon, lat) -> {
            box[0] = Math.min(box[0], lon);
            box[1] = Math.min(box[1], lat);
            box[2] = Math.max(box[2], lon);
            box[3] = Math.max(box[3], lat);
            any[0] = true;
        });
        return any[0] ? box : null;
    }

    /**
     * Where to put one marker for a shape, as {@code [lon, lat]}.
     *
     * <p>For a polygon this is the <strong>area-weighted</strong> centroid over the exterior rings
     * (the shoelace formula), not the mean of the vertices: the two differ by however unevenly the
     * ring is sampled, and feeds sample unevenly. Holes are not subtracted — a hole moves the true
     * centroid, but a marker inside the hole still reads as "this shape, here", and subtracting them
     * can push the marker outside the shape entirely.
     *
     * <p>For a line it is the midpoint <em>along</em> the line. Degenerate cases — a zero-area ring, a
     * zero-length line, a bare multipoint — fall back to the centre of the box, which is always
     * defined. A concave shape can still place its centroid outside itself; that is a property of
     * centroids, and the bbox keeps the viewport test honest regardless.
     */
    static List<Double> centroid(Map<String, Object> geometry, double[] box) {
        String type = String.valueOf(geometry.get("type"));
        List<Double> c = switch (type) {
            case "Polygon", "MultiPolygon" -> areaCentroid(geometry);
            case "LineString", "MultiLineString" -> lineCentroid(geometry);
            default -> null;
        };
        return c != null ? c : List.of(round(box[0] + (box[2] - box[0]) / 2), round(box[1] + (box[3] - box[1]) / 2));
    }

    /** The area-weighted centroid of every exterior ring, weighted by each ring's signed area. */
    private static List<Double> areaCentroid(Map<String, Object> geometry) {
        double cx = 0, cy = 0, total = 0;
        for (List<List<Double>> ring : exteriorRings(geometry)) {
            int n = ring.size();
            if (n < 3) {
                continue;
            }
            double a = 0, rx = 0, ry = 0;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double x1 = ring.get(j).get(0), y1 = ring.get(j).get(1);
                double x2 = ring.get(i).get(0), y2 = ring.get(i).get(1);
                double cross = x1 * y2 - x2 * y1;
                a += cross;
                rx += (x1 + x2) * cross;
                ry += (y1 + y2) * cross;
            }
            if (a == 0) {
                continue; // a ring with no area contributes no position
            }
            double w = Math.abs(a / 2);
            cx += (rx / (3 * a)) * w;
            cy += (ry / (3 * a)) * w;
            total += w;
        }
        return total == 0 ? null : List.of(round(cx / total), round(cy / total));
    }

    /** The point half way along a line, by length. */
    private static List<Double> lineCentroid(Map<String, Object> geometry) {
        List<List<Double>> pts = new ArrayList<>();
        walk(geometry, (lon, lat) -> pts.add(List.of(lon, lat)));
        if (pts.size() < 2) {
            return pts.isEmpty() ? null : pts.getFirst();
        }
        double length = 0;
        for (int i = 1; i < pts.size(); i++) {
            length += segment(pts.get(i - 1), pts.get(i));
        }
        if (length == 0) {
            return pts.getFirst();
        }
        double target = length / 2, walked = 0;
        for (int i = 1; i < pts.size(); i++) {
            double d = segment(pts.get(i - 1), pts.get(i));
            if (walked + d >= target) {
                double f = d == 0 ? 0 : (target - walked) / d;
                List<Double> a = pts.get(i - 1), b = pts.get(i);
                return List.of(round(a.get(0) + f * (b.get(0) - a.get(0))), round(a.get(1) + f * (b.get(1) - a.get(1))));
            }
            walked += d;
        }
        return pts.getLast();
    }

    /**
     * Planar segment length in degrees, with longitude scaled by the latitude it sits at. Enough to
     * pick the midpoint of a line; {@code Geo} owns any distance that is quoted in metres.
     */
    private static double segment(List<Double> a, List<Double> b) {
        double dLat = b.get(1) - a.get(1);
        double dLon = (b.get(0) - a.get(0)) * Math.cos(Math.toRadians((a.get(1) + b.get(1)) / 2));
        return Math.sqrt(dLat * dLat + dLon * dLon);
    }

    /** The exterior ring of every polygon in a Polygon or MultiPolygon; holes are skipped. */
    @SuppressWarnings("unchecked")
    private static List<List<List<Double>>> exteriorRings(Map<String, Object> geometry) {
        Object coords = geometry.get("coordinates");
        List<List<List<Double>>> out = new ArrayList<>();
        if (!(coords instanceof List<?> list) || list.isEmpty()) {
            return out;
        }
        if ("Polygon".equals(geometry.get("type"))) {
            if (list.getFirst() instanceof List<?> shell) {
                out.add((List<List<Double>>) shell);
            }
            return out;
        }
        for (Object polygon : list) {
            if (polygon instanceof List<?> rings && !rings.isEmpty() && rings.getFirst() instanceof List<?> shell) {
                out.add((List<List<Double>>) shell);
            }
        }
        return out;
    }

    /** Every {@code [lon, lat]} pair in a built geometry, however deeply nested, in order. */
    private static void walk(Object node, LonLat visit) {
        if (node instanceof Map<?, ?> m) {
            Object members = m.get("geometries");
            walk(members != null ? members : m.get("coordinates"), visit);
            return;
        }
        if (!(node instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        if (list.getFirst() instanceof Number) {
            if (list.size() >= 2 && list.get(1) instanceof Number) {
                double lon = ((Number) list.getFirst()).doubleValue();
                double lat = ((Number) list.get(1)).doubleValue();
                if (Double.isFinite(lon) && Double.isFinite(lat)) {
                    visit.at(lon, lat);
                }
            }
            return;
        }
        for (Object child : list) {
            walk(child, visit);
        }
    }

    /** Six decimal places — about 0.1 m, past any precision a feed carries. */
    private static double round(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }

    @FunctionalInterface
    private interface LonLat {
        void at(double lon, double lat);
    }

    public static Map<String, Object> point(double lat, double lon) {
        return geometry("Point", coordinate(lat, lon));
    }

    /**
     * A single-ring polygon from a list of {@code [lat, lon]} pairs. The ring is closed here if the
     * caller did not close it, because a GeoJSON polygon whose last point is not its first is accepted
     * by some renderers and quietly dropped by others.
     */
    public static Map<String, Object> polygon(List<double[]> ring) {
        List<List<Double>> coords = coordinates(ring);
        if (!coords.isEmpty() && !coords.getFirst().equals(coords.getLast())) {
            coords.add(coords.getFirst());
        }
        return geometry("Polygon", List.of(coords));
    }

    /**
     * An open list of {@code [lat, lon]} pairs: what a two-station group's hull collapses to.
     */
    public static Map<String, Object> lineString(List<double[]> path) {
        return geometry("LineString", coordinates(path));
    }

    /**
     * A JTS polygon or multipolygon as GeoJSON, holes and all — what {@link Coverage} produces.
     * <p>
     * The holes are the reason this cannot go through {@link #polygon}: a single ring cannot say
     * "covered, except here", and on a coverage layer the except-here is the interesting part.
     */
    public static Map<String, Object> polygonal(Geometry g) {
        if (g == null || g.isEmpty()) {
            return null;
        }
        if (g instanceof Polygon p) {
            return geometry("Polygon", rings(p));
        }
        List<Object> polygons = new ArrayList<>();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            if (g.getGeometryN(i) instanceof Polygon p && !p.isEmpty()) {
                polygons.add(rings(p));
            }
        }
        return polygons.isEmpty() ? null : geometry("MultiPolygon", polygons);
    }

    /**
     * Any JTS geometry as GeoJSON: a point, a line, a polygon with its holes, the multi- form of each,
     * and a collection of whatever a KML placemark mixed together. Null for an empty geometry, which
     * is a feature with no place rather than an error.
     */
    public static Map<String, Object> of(Geometry g) {
        if (g == null || g.isEmpty()) {
            return null;
        }
        return switch (g) {
            case Point p -> point(p.getY(), p.getX());
            case LineString l -> geometry("LineString", ring(l.getCoordinates()));
            case Polygon p -> geometry("Polygon", rings(p));
            case MultiPoint m -> geometry("MultiPoint", parts(m, c -> coordinate(c.getCoordinate().y, c.getCoordinate().x)));
            case MultiLineString m -> geometry("MultiLineString", parts(m, l -> ring(l.getCoordinates())));
            case MultiPolygon m -> polygonal(m);
            default -> {
                List<Object> members = new ArrayList<>();
                for (int i = 0; i < g.getNumGeometries(); i++) {
                    Map<String, Object> member = of(g.getGeometryN(i));
                    if (member != null) {
                        members.add(member);
                    }
                }
                if (members.isEmpty()) {
                    yield null;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("type", "GeometryCollection");
                out.put("geometries", members);
                yield out;
            }
        };
    }

    private static List<Object> parts(Geometry multi, Function<Geometry, Object> part) {
        List<Object> out = new ArrayList<>(multi.getNumGeometries());
        for (int i = 0; i < multi.getNumGeometries(); i++) {
            Geometry member = multi.getGeometryN(i);
            if (!member.isEmpty()) {
                out.add(part.apply(member));
            }
        }
        return out;
    }

    /**
     * Shell first, then the holes: GeoJSON's ring order for a polygon.
     */
    private static List<List<List<Double>>> rings(Polygon p) {
        List<List<List<Double>>> rings = new ArrayList<>(1 + p.getNumInteriorRing());
        rings.add(ring(p.getExteriorRing().getCoordinates()));
        for (int i = 0; i < p.getNumInteriorRing(); i++) {
            rings.add(ring(p.getInteriorRingN(i).getCoordinates()));
        }
        return rings;
    }

    /**
     * JTS is (x = lon, y = lat), so this hands the pair over in this class's own lat-then-lon order.
     */
    private static List<List<Double>> ring(Coordinate[] coordinates) {
        List<List<Double>> out = new ArrayList<>(coordinates.length);
        for (Coordinate c : coordinates) {
            out.add(coordinate(c.y, c.x));
        }
        return out;
    }

    private static Map<String, Object> geometry(String type, Object coordinates) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("type", type);
        g.put("coordinates", coordinates);
        return g;
    }

    /**
     * Longitude first. This is the whole reason every builder here shares a floor.
     */
    private static List<Double> coordinate(double lat, double lon) {
        return List.of(lon, lat);
    }

    private static List<List<Double>> coordinates(List<double[]> latLonPairs) {
        List<List<Double>> out = new ArrayList<>(latLonPairs.size() + 1);
        for (double[] p : latLonPairs) {
            out.add(coordinate(p[0], p[1]));
        }
        return out;
    }
}
