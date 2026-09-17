package au.weather.geojson;

import au.weather.geo.Geo;
import lombok.experimental.UtilityClass;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Where a set of radius-carrying points actually reaches, as one boundary instead of one circle each.
 * <p>
 * A layer that draws a circle per point stops being readable somewhere around the fiftieth: the
 * question an operator has - <em>which ground is covered and which is not</em> - is answered by the
 * outline of the union, and the hundreds of interior arcs are only in the way of reading it. The
 * union also says something the circles cannot: a hole in it is a gap in coverage, and a gap is the
 * thing worth seeing.
 * <p>
 * Discs are built as polygons in lat/lon rather than by buffering in degrees. A buffer of
 * {@code metres / metresPerDegreeLat} is the mistake this avoids: at 35S a degree of longitude is 82%
 * of a degree of latitude, so that circle is a fifth too wide east to west - invisible at the sigma
 * buffers {@code IncidentStore} uses, and about five kilometres of lie on a 25 km cache radius. Each
 * vertex here is offset by the metres-per-degree of its own axis, so the shape is right without any
 * reprojection. The union itself is topological and needs no projection at all.
 */
@UtilityClass
public class Coverage {

    /**
     * Vertices per disc. An inscribed 64-gon sits within {@code 1 − cos(π/64)} of the circle, which is
     * 0.12% - thirty metres on a 25 km radius, and less than the line is wide on screen.
     */
    public static final int SEGMENTS = 64;

    /**
     * The empty geometry: what a band with no members covers, which is nothing rather than null.
     */
    public static Geometry empty() {
        return Geo.FACTORY.createPolygon();
    }

    /**
     * The union of the discs. Cascaded rather than folded one at a time, because unioning n polygons
     * by repeated pairwise union is quadratic in the vertices it copies and this runs on a layer that
     * refreshes every sixty seconds.
     */
    public static Geometry union(Collection<Disc> discs) {
        if (discs == null || discs.isEmpty()) {
            return empty();
        }
        List<Geometry> circles = new ArrayList<>(discs.size());
        for (Disc d : discs) {
            circles.add(disc(d));
        }
        Geometry union = UnaryUnionOp.union(circles);
        return union == null ? empty() : union;
    }

    /**
     * {@code g} with everything in {@code other} taken out of it.
     * <p>
     * This is what turns three overlapping blobs into three bands that tile: stale minus fresh,
     * expired minus both. Every point of the map then belongs to exactly one, and the one it belongs
     * to is the best coverage available there - which is the question, and is not answerable from
     * three stacked translucent fills.
     */
    public static Geometry without(Geometry g, Geometry other) {
        if (g == null || g.isEmpty()) {
            return empty();
        }
        if (other == null || other.isEmpty()) {
            return g;
        }
        return g.difference(other);
    }

    /**
     * How many separate pieces the coverage is in. One is a continuous footprint; twelve is islands.
     */
    public static int pieces(Geometry g) {
        return g == null || g.isEmpty() ? 0 : g.getNumGeometries();
    }

    /**
     * The boundary as GeoJSON, simplified first.
     * <p>
     * Five hundred discs of sixty-four vertices is thirty-two thousand of them; the union drops the
     * interior ones but a ragged rim of arcs survives, and it is all payload. Simplifying at a
     * fortieth of the radius is well under what the union's own arcs mean and takes the typical
     * boundary down by an order of magnitude. Topology-preserving, so a simplified band cannot invert
     * a ring or swallow a coverage gap.
     */
    public static Map<String, Object> geoJson(Geometry g, double toleranceMetres) {
        Geometry simplified = simplify(g, toleranceMetres);
        return simplified.isEmpty() ? null : GeoJson.polygonal(simplified);
    }

    static Geometry simplify(Geometry g, double toleranceMetres) {
        if (g == null || g.isEmpty() || toleranceMetres <= 0) {
            return g == null ? empty() : g;
        }
        // Degrees of latitude: the tighter of the two axes, so the tolerance is never larger than asked.
        double degrees = toleranceMetres / Geo.metresPerDegreeLat(g.getCentroid().getY());
        return TopologyPreservingSimplifier.simplify(g, degrees);
    }

    /**
     * One disc as a closed polygon, each vertex offset in the metres-per-degree of its own axis.
     */
    static Polygon disc(Disc d) {
        double perLat = Geo.metresPerDegreeLat(d.lat());
        double perLon = Geo.metresPerDegreeLon(d.lat());
        Coordinate[] ring = new Coordinate[SEGMENTS + 1];
        for (int i = 0; i < SEGMENTS; i++) {
            double t = 2 * Math.PI * i / SEGMENTS;
            ring[i] = new Coordinate(d.lon() + d.radiusMetres() * Math.cos(t) / perLon,
                    d.lat() + d.radiusMetres() * Math.sin(t) / perLat);
        }
        ring[SEGMENTS] = ring[0];
        return Geo.FACTORY.createPolygon(ring);
    }

    /**
     * One point and how far it is being stretched.
     */
    public record Disc(double lat, double lon, double radiusMetres) {
    }
}
