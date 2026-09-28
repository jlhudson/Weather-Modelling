package au.gully.reach;

import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reaches without their overlaps (W-46): every place held by one station, the nearest whose reach holds it, so the map
 * can draw every reach at once as patches that tile rather than polygons stacked on one another. A Bureau station's patch
 * is its reach less the places a nearer Bureau station's reach holds; a point of ours takes only what no Bureau station
 * reaches, less what a nearer point holds - as a reading takes its forecast from the nearest station in reach, and from a
 * point of ours only where none is. Nearer is by distance on the ground: each pair is split by the line midway between
 * them, in a flat projection at their mean latitude, the same line for both, so the patches meet without a gap. Where no
 * reach holds a place, no patch does. Nothing is stored: the patches follow whatever rule the reaches are drawn by.
 */
@Service
@RequiredArgsConstructor
public class Patches {

    /**
     * A part of a patch smaller than this, in square degrees - about a thousandth of a square kilometre - is the overlay's
     * rounding, not ground, and is left out.
     */
    static final double SLIVER_DEG2 = 1e-7;

    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    private final StationRegistry stations;
    private final TerrainStore terrain;

    /**
     * One station's reach, where it stands and what kind it is: what a patch is cut from.
     */
    public record Piece(String id, double lat, double lon, boolean point, Polygon reach) {
    }

    /**
     * Every station with terrain, its patch under the rule, as a FeatureCollection: its reach's figures beside the patch's own area.
     */
    public Map<String, Object> geojson(ReachRule.Rule r) {
        List<Piece> pieces = new ArrayList<>();
        Map<String, Station> byId = new LinkedHashMap<>();
        Map<String, Reach> reaches = new LinkedHashMap<>();
        for (Station s : stations.all()) {
            terrain.get(s.id()).ifPresent(t -> {
                Reach reach = Reach.of(t, r);
                byId.put(s.id(), s);
                reaches.put(s.id(), reach);
                pieces.add(new Piece(s.id(), s.lat(), s.lon(), s.isPoint(), Probe.polygon(reach)));
            });
        }
        List<Map<String, Object>> features = new ArrayList<>();
        tile(pieces).forEach((id, patch) -> {
            Map<String, Object> geometry = geometry(patch);
            if (geometry == null) {
                return;
            }
            Station s = byId.get(id);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", id);
            p.put("name", s.name());
            p.put("kind", s.kind());
            p.put("patchKm2", Math.round(areaKm2(patch)));
            p.putAll(Reaches.summary(reaches.get(id)));
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("type", "Feature");
            f.put("id", id);
            f.put("geometry", geometry);
            f.put("properties", p);
            features.add(f);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "FeatureCollection");
        out.put("rule", Reaches.rule(r));
        out.put("features", features);
        return out;
    }

    /**
     * Each piece's patch, by id, in the order given: its reach less every place another piece has the better claim to.
     */
    static Map<String, Geometry> tile(List<Piece> pieces) {
        Map<String, Geometry> out = new LinkedHashMap<>();
        for (Piece a : pieces) {
            Envelope box = a.reach().getEnvelopeInternal();
            List<Geometry> taken = new ArrayList<>();
            for (Piece b : pieces) {
                if (b == a || !box.intersects(b.reach().getEnvelopeInternal())) {
                    continue;
                }
                if (!a.point() && b.point()) {
                    // A point of ours never takes ground a Bureau station reaches.
                    continue;
                }
                if (a.point() && !b.point()) {
                    taken.add(b.reach());
                    continue;
                }
                Polygon nearerB = nearer(b, a, box);
                if (nearerB != null) {
                    taken.add(OverlayNGRobust.overlay(b.reach(), nearerB, OverlayNG.INTERSECTION));
                }
            }
            out.put(a.id(), taken.isEmpty() ? a.reach() : OverlayNGRobust.overlay(a.reach(), OverlayNGRobust.union(taken), OverlayNG.DIFFERENCE));
        }
        return out;
    }

    /**
     * The part of a box nearer {@code b} than {@code a}, as a polygon, or null when none of it is. Two stations on the same
     * spot are split by their ids, the lesser keeping the ground.
     */
    static Polygon nearer(Piece b, Piece a, Envelope box) {
        // Nearer b where f > 0: the perpendicular bisector in a projection flat at the pair's mean latitude.
        double k2 = Math.pow(Math.cos(Math.toRadians((a.lat() + b.lat()) / 2)), 2);
        double gx = (b.lon() - a.lon()) * k2, gy = b.lat() - a.lat();
        double c = (k2 * (b.lon() * b.lon() - a.lon() * a.lon()) + b.lat() * b.lat() - a.lat() * a.lat()) / 2;
        double[][] corners = {{box.getMinX(), box.getMinY()}, {box.getMaxX(), box.getMinY()}, {box.getMaxX(), box.getMaxY()}, {box.getMinX(), box.getMaxY()}};
        if (gx == 0 && gy == 0) {
            return b.id().compareTo(a.id()) < 0 ? rectangle(corners) : null;
        }
        List<Coordinate> kept = new ArrayList<>();
        for (int i = 0; i < corners.length; i++) {
            double[] p = corners[i], q = corners[(i + 1) % corners.length];
            double fp = gx * p[0] + gy * p[1] - c, fq = gx * q[0] + gy * q[1] - c;
            if (fp >= 0) {
                kept.add(new Coordinate(p[0], p[1]));
            }
            if ((fp < 0) != (fq < 0)) {
                double t = fp / (fp - fq);
                kept.add(new Coordinate(p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1])));
            }
        }
        if (kept.size() < 3) {
            return null;
        }
        kept.add(kept.getFirst().copy());
        return GEOMETRY.createPolygon(kept.toArray(Coordinate[]::new));
    }

    private static Polygon rectangle(double[][] corners) {
        Coordinate[] ring = new Coordinate[corners.length + 1];
        for (int i = 0; i < corners.length; i++) {
            ring[i] = new Coordinate(corners[i][0], corners[i][1]);
        }
        ring[corners.length] = ring[0].copy();
        return GEOMETRY.createPolygon(ring);
    }

    /**
     * A patch as a GeoJSON geometry - a Polygon, or a MultiPolygon where a nearer station's patch cuts it in two - its
     * slivers dropped; null when nothing is left.
     */
    static Map<String, Object> geometry(Geometry g) {
        List<List<List<List<Double>>>> polygons = new ArrayList<>();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            if (g.getGeometryN(i) instanceof Polygon p && !p.isEmpty() && p.getArea() >= SLIVER_DEG2) {
                List<List<List<Double>>> rings = new ArrayList<>();
                rings.add(ring(p.getExteriorRing()));
                for (int h = 0; h < p.getNumInteriorRing(); h++) {
                    if (GEOMETRY.createPolygon(p.getInteriorRingN(h)).getArea() >= SLIVER_DEG2) {
                        rings.add(ring(p.getInteriorRingN(h)));
                    }
                }
                polygons.add(rings);
            }
        }
        if (polygons.isEmpty()) {
            return null;
        }
        return polygons.size() == 1 ? Map.of("type", "Polygon", "coordinates", polygons.getFirst())
                : Map.of("type", "MultiPolygon", "coordinates", polygons);
    }

    private static List<List<Double>> ring(LinearRing r) {
        List<List<Double>> out = new ArrayList<>(r.getNumPoints());
        for (Coordinate c : r.getCoordinates()) {
            out.add(List.of(Math.round(c.x * 1e5) / 1e5, Math.round(c.y * 1e5) / 1e5));
        }
        return out;
    }

    /**
     * A patch's area in square kilometres: its square degrees at the scale of its own latitude, near enough for a shape a
     * hundred kilometres across.
     */
    static double areaKm2(Geometry g) {
        if (g.isEmpty()) {
            return 0;
        }
        double kmPerDeg = Math.toRadians(1) * Geo.EARTH_RADIUS_KM;
        return g.getArea() * kmPerDeg * kmPerDeg * Math.cos(Math.toRadians(g.getCentroid().getY()));
    }
}
