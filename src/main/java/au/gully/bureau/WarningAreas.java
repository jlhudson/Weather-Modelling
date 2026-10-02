package au.gully.bureau;

import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;
import au.gully.platform.Nodes;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The areas the Bureau's warnings name, as shapes (W-50): South Australia's and Tasmania's public forecast districts,
 * fire weather districts and marine zones, from the Bureau's own shapefiles - public weather forecast districts
 * ({@code IDM00001}), fire weather districts ({@code IDM00007}) and marine zones ({@code IDM00003}) - simplified once by
 * {@code tools/WarningAreasGen.java} into {@value #RESOURCE} and read at the start. A warning's shape is the union of its
 * areas' shapes; an area with none - a river basin - adds nothing, and a warning none of whose areas has one has none.
 */
@Component
public class WarningAreas {

    public static final String RESOURCE = "/bureau/warning-areas.geojson";
    /**
     * A part of a union smaller than this, in square degrees - about a thousandth of a square kilometre - is where two
     * kinds of district's simplified edges disagree, not ground, and is left out.
     */
    static final double SLIVER_DEG2 = 1e-7;
    /**
     * Unions kept by the codes they were made from, at most this many before they are made again.
     */
    private static final int KEPT = 256;

    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    private final Map<String, Shape> shapes;
    private final Map<String, Optional<Map<String, Object>>> unions = new ConcurrentHashMap<>();

    /**
     * One area: its code, name, state ({@code sa}, {@code tas}), what kind of area it is in the Bureau's words, and its shape
     * in longitude and latitude.
     */
    public record Shape(String aac, String name, String state, String type, Geometry geometry) {
    }

    public WarningAreas() {
        this(read());
    }

    WarningAreas(Map<String, Shape> shapes) {
        this.shapes = Collections.unmodifiableMap(shapes);
    }

    /**
     * Every area held, by code.
     */
    public Map<String, Shape> all() {
        return shapes;
    }

    public Optional<Shape> of(String aac) {
        return Optional.ofNullable(shapes.get(aac));
    }

    /**
     * A warning's shape: the union of those of its areas that have one; null when none has.
     */
    public Geometry shape(Warnings.Warning w) {
        List<Geometry> parts = w.areas().stream().map(a -> shapes.get(a.aac())).filter(Objects::nonNull).map(Shape::geometry).toList();
        if (parts.isEmpty()) {
            return null;
        }
        return parts.size() == 1 ? parts.getFirst() : OverlayNGRobust.union(parts);
    }

    /**
     * Every warning as a Feature, in the order given: its id, its properties exactly {@link Warnings#view}, its geometry
     * the union of its areas' shapes as a Polygon or MultiPolygon, or null when none of its areas has one.
     */
    public Map<String, Object> geojson(List<Warnings.Warning> warnings, Instant readAt) {
        List<Map<String, Object>> features = new ArrayList<>();
        for (Warnings.Warning w : warnings) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("type", "Feature");
            f.put("id", w.id());
            f.put("properties", Warnings.view(w));
            f.put("geometry", geometry(w));
            features.add(f);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "FeatureCollection");
        out.put("features", features);
        out.put("readAt", readAt == null ? null : readAt.toString());
        return out;
    }

    /**
     * A warning's shape as GeoJSON, made once for each set of codes.
     */
    Map<String, Object> geometry(Warnings.Warning w) {
        String key = String.join(",", w.areas().stream().map(Warnings.Area::aac).filter(shapes::containsKey).sorted().distinct().toList());
        if (key.isEmpty()) {
            return null;
        }
        if (unions.size() >= KEPT) {
            unions.clear();
        }
        return unions.computeIfAbsent(key, k -> Optional.ofNullable(geometry(shape(w)))).orElse(null);
    }

    /**
     * A shape as a GeoJSON Polygon, or MultiPolygon where it comes in pieces: outer rings anticlockwise and holes clockwise,
     * as RFC 7946 asks, slivers left out; null when nothing is left.
     */
    static Map<String, Object> geometry(Geometry g) {
        if (g == null) {
            return null;
        }
        List<List<List<List<Double>>>> polygons = new ArrayList<>();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            if (g.getGeometryN(i) instanceof Polygon p && !p.isEmpty() && p.getArea() >= SLIVER_DEG2) {
                List<List<List<Double>>> rings = new ArrayList<>();
                rings.add(ring(p.getExteriorRing().getCoordinates(), true));
                for (int h = 0; h < p.getNumInteriorRing(); h++) {
                    if (GEOMETRY.createPolygon(p.getInteriorRingN(h)).getArea() >= SLIVER_DEG2) {
                        rings.add(ring(p.getInteriorRingN(h).getCoordinates(), false));
                    }
                }
                polygons.add(rings);
            }
        }
        if (polygons.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", polygons.size() == 1 ? "Polygon" : "MultiPolygon");
        out.put("coordinates", polygons.size() == 1 ? polygons.getFirst() : polygons);
        return out;
    }

    private static List<List<Double>> ring(Coordinate[] cs, boolean anticlockwise) {
        List<List<Double>> out = new ArrayList<>(cs.length);
        boolean flip = Orientation.isCCW(cs) != anticlockwise;
        for (int i = 0; i < cs.length; i++) {
            Coordinate c = cs[flip ? cs.length - 1 - i : i];
            out.add(List.of(Math.round(c.x * 1e5) / 1e5, Math.round(c.y * 1e5) / 1e5));
        }
        return out;
    }

    // ---------------------------------------------------------------- the resource

    static Map<String, Shape> read() {
        try (InputStream in = WarningAreas.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing");
            }
            return parse(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Map<String, Shape> parse(byte[] json) {
        Map<String, Shape> out = new LinkedHashMap<>();
        for (JsonNode f : JsonMapper.builder().build().readTree(json).path("features")) {
            JsonNode p = f.path("properties"), g = f.path("geometry");
            String aac = Nodes.str(p, "aac");
            Geometry shape = switch (String.valueOf(Nodes.str(g, "type"))) {
                case "Polygon" -> polygon(g.path("coordinates"));
                case "MultiPolygon" -> {
                    List<Polygon> parts = new ArrayList<>();
                    for (JsonNode polygon : g.path("coordinates")) {
                        parts.add(polygon(polygon));
                    }
                    yield GEOMETRY.createMultiPolygon(parts.toArray(Polygon[]::new));
                }
                default -> null;
            };
            if (aac == null || shape == null) {
                continue;
            }
            out.put(aac, new Shape(aac, Nodes.str(p, "name"), Nodes.str(p, "state"), Nodes.str(p, "type"),
                    shape.isValid() ? shape : GeometryFixer.fix(shape)));
        }
        return out;
    }

    private static Polygon polygon(JsonNode rings) {
        LinearRing shell = null;
        List<LinearRing> holes = new ArrayList<>();
        for (JsonNode ring : rings) {
            List<Coordinate> cs = new ArrayList<>();
            for (JsonNode xy : ring) {
                cs.add(new Coordinate(xy.get(0).asDouble(), xy.get(1).asDouble()));
            }
            LinearRing r = GEOMETRY.createLinearRing(cs.toArray(Coordinate[]::new));
            if (shell == null) {
                shell = r;
            } else {
                holes.add(r);
            }
        }
        return GEOMETRY.createPolygon(shell, holes.toArray(LinearRing[]::new));
    }
}
