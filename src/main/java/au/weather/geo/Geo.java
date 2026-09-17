package au.weather.geo;

import lombok.experimental.UtilityClass;
import org.locationtech.jts.geom.*;

import java.util.ArrayList;
import java.util.List;

/**
 * The spatial mathematics of docs/05-spatial-maths.md, small enough to hold in your head.
 * Degrees are not metres, and the two axes differ; everything is computed per point, never as a constant.
 */
@UtilityClass
public class Geo {

    /**
     * Storage SRID. Everything is reprojected to EPSG:4326 on ingest (D-064).
     */
    public static final int SRID = 4326;
    public static final double EARTH_RADIUS_M = 6_371_008.8;
    public static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), SRID);
    /**
     * The decimal places a stored fix keeps (James, 8 September 2026). Five, which is about a metre.
     *
     * <p><b>Not for storage.</b> PostGIS holds each ordinate as an IEEE-754 double, eight bytes whether
     * it carries {@code 138.5467} or {@code 138.54667744123456} - measured, thirty-two bytes for the
     * point either way - so rounding saves nothing on the geometry columns and a few kilobytes on the
     * JSON payloads that quote coordinates as text.
     *
     * <p><b>For honesty, and for agreement.</b> Storing fourteen decimal places on a fix known to ten
     * metres is the same false precision as a feed publishing a road as a {@code REPORTED_POINT} at
     * σ 10 m: it invites arithmetic to take a claim seriously that nothing supports. Rounding also makes
     * two sources that report the same place report the *same* place, so cache keys and dedupe stop
     * being defeated by noise in the last bits.
     *
     * <p><b>Five rather than four.</b> At 35S four decimal places is 11 m of latitude and 9 m of
     * longitude, so it would add up to 5.5 m of quantisation to fixes deliberately trusted at 10 m -
     * half the precision of the best ones, bought for nothing. Five is about a metre and is free.
     */
    public static final int STORED_DECIMALS = 5;
    /**
     * The vertex count of a footprint ring: enough to read as a circle, few enough to store and draw cheaply.
     */
    public static final int FOOTPRINT_VERTICES = 16;
    private static final double QUANTUM = Math.pow(10, STORED_DECIMALS);

    /**
     * Metres per degree of latitude at latitude {@code phiDeg} (WGS84 series).
     */
    public static double metresPerDegreeLat(double phiDeg) {
        double phi = Math.toRadians(phiDeg);
        return 111_132.92 - 559.82 * Math.cos(2 * phi) + 1.175 * Math.cos(4 * phi) - 0.0023 * Math.cos(6 * phi);
    }

    /**
     * Metres per degree of longitude at latitude {@code phiDeg}. At 35S this is only 82% of the latitude figure.
     */
    public static double metresPerDegreeLon(double phiDeg) {
        double phi = Math.toRadians(phiDeg);
        return 111_412.84 * Math.cos(phi) - 93.5 * Math.cos(3 * phi) + 0.118 * Math.cos(5 * phi);
    }

    /**
     * Great-circle distance in metres. Correct; use for the decision boundary.
     */
    public static double haversineMetres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Local planar (equirectangular) distance in metres. Agrees with haversine to well under a millimetre below ~5 km; for the hot loop.
     */
    public static double planarMetres(double lat1, double lon1, double lat2, double lon2) {
        double meanLat = Math.toRadians((lat1 + lat2) / 2);
        double x = EARTH_RADIUS_M * Math.toRadians(lon2 - lon1) * Math.cos(meanLat);
        double y = EARTH_RADIUS_M * Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y);
    }

    /**
     * Horizontal separation and a weighted vertical separation, combined as the hypotenuse of a right
     * triangle. One scalar that answers "how far away is this really", for a cache deciding whether one
     * reading may stand in for another.
     *
     * <p><strong>The weight is an exchange rate, not a physical constant.</strong> At 67, three hundred
     * metres of height costs the same as twenty kilometres of ground, because 20 000 / 300 = 66.7. Its
     * defensible effect is that it separates an escarpment from the plain below it and changes nothing at
     * all in flat country; the temperature argument would put it far lower and the wind argument far
     * higher, and neither is settled by anything this system holds. It is a policy dial, and the number
     * that produced it is written down so the next person can move it knowingly.
     *
     * <p>Note the rate is <em>not</em> scale-invariant: it is stated against a twenty-kilometre reach, so
     * a caller working at fifty kilometres forgives proportionally more terrain. That is arguably right —
     * a reading already stretched that far is a degraded answer either way — but it was a consequence
     * rather than a choice, and it is here rather than hidden.
     *
     * <p><strong>Symmetric on sign, deliberately.</strong> Three hundred metres above an anchor on a ridge
     * and three hundred below it in a valley are genuinely different situations: inversions and cold air
     * drainage do not care which way the arithmetic went. A signed treatment would be more correct and
     * completely unsupportable on the evidence available here, so the difference is dropped rather than
     * guessed at.
     *
     * <p>A {@code verticalWeight} of zero reduces this exactly to {@link #planarMetres}, which is the
     * switch that turns the whole vertical term off without removing it.
     *
     * @param horizontalMetres ground distance, from {@link #planarMetres} or {@link #haversineMetres}
     * @param verticalMetres   difference in ground height; the sign is discarded
     * @param verticalWeight   metres of horizontal cost per metre of height, or zero to disable
     */
    public static double reachMetres(double horizontalMetres, double verticalMetres, double verticalWeight) {
        double v = verticalWeight * verticalMetres;
        return Math.sqrt(horizontalMetres * horizontalMetres + v * v);
    }

    /**
     * The forward azimuth from the first point to the second, in degrees clockwise from true north,
     * {@code [0, 360)}. True north, not magnetic: a compass reading here differs by the local declination,
     * which is a property of the year and the place and is not applied.
     */
    public static double initialBearingDeg(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1), phi2 = Math.toRadians(lat2);
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /**
     * The point {@code distanceM} along the great circle leaving {@code (lat, lon)} on {@code bearingDeg}.
     */
    public static LatLon destination(double lat, double lon, double bearingDeg, double distanceM) {
        double delta = distanceM / EARTH_RADIUS_M;
        double theta = Math.toRadians(bearingDeg);
        double phi1 = Math.toRadians(lat), lambda1 = Math.toRadians(lon);
        double phi2 = Math.asin(Math.sin(phi1) * Math.cos(delta) + Math.cos(phi1) * Math.sin(delta) * Math.cos(theta));
        double lambda2 = lambda1 + Math.atan2(Math.sin(theta) * Math.sin(delta) * Math.cos(phi1),
                Math.cos(delta) - Math.sin(phi1) * Math.sin(phi2));
        return new LatLon(Math.toDegrees(phi2), normaliseLon(Math.toDegrees(lambda2)));
    }

    /**
     * The point a fraction {@code f} of the way along the great circle from the first point to the
     * second: {@code 0} is the first, {@code 1} the second. Spherical interpolation, so a long leg is
     * sampled along the path a radio wave actually takes rather than along a straight line in degrees.
     */
    public static LatLon intermediate(double lat1, double lon1, double lat2, double lon2, double f) {
        double delta = haversineMetres(lat1, lon1, lat2, lon2) / EARTH_RADIUS_M;
        if (delta == 0) {
            return new LatLon(lat1, lon1);
        }
        double phi1 = Math.toRadians(lat1), lambda1 = Math.toRadians(lon1);
        double phi2 = Math.toRadians(lat2), lambda2 = Math.toRadians(lon2);
        double a = Math.sin((1 - f) * delta) / Math.sin(delta);
        double b = Math.sin(f * delta) / Math.sin(delta);
        double x = a * Math.cos(phi1) * Math.cos(lambda1) + b * Math.cos(phi2) * Math.cos(lambda2);
        double y = a * Math.cos(phi1) * Math.sin(lambda1) + b * Math.cos(phi2) * Math.sin(lambda2);
        double z = a * Math.sin(phi1) + b * Math.sin(phi2);
        return new LatLon(Math.toDegrees(Math.atan2(z, Math.sqrt(x * x + y * y))), Math.toDegrees(Math.atan2(y, x)));
    }

    private static double normaliseLon(double lon) {
        return ((lon + 540) % 360) - 180;
    }

    /**
     * Normalised separation {@code s = d / sqrt(sa^2 + sb^2)}: how many combined standard deviations
     * apart two fixes are. Merge if {@code s <= k}; {@code k = sqrt(2)} reproduces the 50 m rule for two 25 m fixes.
     */
    public static double normalisedSeparation(double distanceMetres, double sigmaA, double sigmaB) {
        return distanceMetres / Math.sqrt(sigmaA * sigmaA + sigmaB * sigmaB);
    }

    /**
     * Gaussian spatial feature in normalised separation, decaying rather than stepping (5.4).
     */
    public static double spatialFeature(double normalisedSeparation) {
        return Math.exp(-0.5 * normalisedSeparation * normalisedSeparation);
    }

    public static double logit(double p) {
        return Math.log(p / (1 - p));
    }

    public static double sigmoid(double x) {
        return 1 / (1 + Math.exp(-x));
    }

    /**
     * The bounds of South Australia plus the 150 km ingest margin, generously rounded: the same box as the POI gate.
     */
    public static boolean insideSouthAustraliaMargin(double lat, double lon) {
        return lat >= -40.0 && lat <= -24.0 && lon >= 125.0 && lon <= 143.0;
    }

    /**
     * A coordinate at the precision worth keeping.
     */
    public static double round(double degrees) {
        return Math.round(degrees * QUANTUM) / QUANTUM;
    }

    /**
     * A stored fix, at {@link #STORED_DECIMALS} places.
     * <p>
     * Every geometry the system persists is built here - both geocoders, the feed rows, the pager
     * ladder, weather anchors, meshcore devices, operations and movements - so this is the one place the
     * precision has to be stated.
     */
    public static Point point(double lat, double lon) {
        return FACTORY.createPoint(new Coordinate(round(lon), round(lat)));
    }

    /**
     * The ground a fix could be standing on, as a closed ring around it (James, 8 September 2026).
     *
     * <p>Two of these overlapping is the matcher's spatial test, in place of comparing a distance with a
     * combined sigma. For two circles the two are the same statement — they intersect exactly when
     * {@code d <= r1 + r2} — so on its own this changes the parameters and not the algebra. What it buys
     * is that a footprint no longer has to be a circle: a map-grid cell is a rectangle, a burn scar and a
     * hotspot cluster are arbitrary polygons, and a street geocode is honestly a line. The predicate
     * "do these two pieces of ground touch" is the same for all of them, and it is what the map draws.
     *
     * <p>Built in degrees about the point rather than by projecting, because at these radii the error is
     * nothing: a metre of longitude at 35S is scaled by {@code 1/cos(phi)} and that is exactly what
     * {@link #metresPerDegreeLon} returns. Sixteen vertices puts the ring within 2% of a true circle,
     * which is far inside the uncertainty the radius is expressing in the first place.
     */
    public static Polygon footprint(double lat, double lon, double radiusMetres) {
        double r = Math.max(1, radiusMetres);
        double dLat = r / metresPerDegreeLat(lat);
        double dLon = r / metresPerDegreeLon(lat);
        Coordinate[] ring = new Coordinate[FOOTPRINT_VERTICES + 1];
        for (int i = 0; i < FOOTPRINT_VERTICES; i++) {
            double theta = 2 * Math.PI * i / FOOTPRINT_VERTICES;
            // Rounded like a stored fix. These rings are serialised on every layer request - seventeen
            // vertices, two ordinates each - and seventeen digits per ordinate is a lot of bytes spent
            // saying something the radius already said less precisely.
            ring[i] = new Coordinate(round(lon + dLon * Math.cos(theta)), round(lat + dLat * Math.sin(theta)));
        }
        ring[FOOTPRINT_VERTICES] = ring[0];
        return FACTORY.createPolygon(ring);
    }

    /**
     * The tightest convex ring around every shape given (James, 13 September 2026: a combined incident
     * is "a convex hull (tightly) for all contributing points").
     *
     * <p>Nulls and empty shapes are skipped, and the answer is null when what is left has no area to
     * draw - fewer than three distinct vertices, which no footprint ring or source polygon ever has.
     * Built in degrees rather than in a local metre frame, because a convex hull survives a scaling of
     * the axes: the hull of the scaled set is the scaled hull, so the frame {@code HotspotGeometry}
     * builds for its buffers buys nothing here and its two conversions would only add rounding.
     */
    public static Polygon hull(Geometry... shapes) {
        List<Geometry> parts = new ArrayList<>();
        for (Geometry g : shapes) {
            if (g != null && !g.isEmpty()) {
                parts.add(g);
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        Geometry hull = FACTORY.createGeometryCollection(parts.toArray(new Geometry[0])).convexHull();
        if (!(hull instanceof Polygon p)) {
            return null;
        }
        p.setSRID(SRID);
        return p;
    }

    /**
     * Whether two fixes could be the same piece of ground.
     * <p>
     * Answered on the radii rather than by building both rings and asking JTS, because for circles the
     * two agree exactly and this way costs one distance. The rings are for drawing and for the day a
     * footprint stops being circular; the arithmetic does not need them.
     */
    public static boolean footprintsOverlap(double latA, double lonA, double radiusA,
                                            double latB, double lonB, double radiusB) {
        return planarMetres(latA, lonA, latB, lonB) <= Math.max(1, radiusA) + Math.max(1, radiusB);
    }

    /**
     * A line in storage order, from points in lat/lon order. Two points at least, or JTS refuses.
     */
    public static LineString line(List<LatLon> points) {
        if (points == null || points.size() < 2) {
            throw new IllegalArgumentException("a line needs at least two points");
        }
        Coordinate[] coordinates = new Coordinate[points.size()];
        for (int i = 0; i < points.size(); i++) {
            coordinates[i] = new Coordinate(points.get(i).lon(), points.get(i).lat());
        }
        return FACTORY.createLineString(coordinates);
    }

    /**
     * The area of a lat/lon polygon in square metres, on the sphere.
     * <p>
     * {@code Geometry.getArea()} would answer in square degrees, which is not an area of anything: a
     * degree of longitude at 35S is 82% of a degree of latitude, so the number would be wrong by a
     * fifth before anyone converted it. This is the spherical-excess sum (Chamberlain and Duquette),
     * which needs no reprojection and is exact on the sphere - well inside the honesty this figure is
     * quoted with, which is "how much ground does the cache cover", not a cadastral measurement.
     * <p>
     * Rings are summed by magnitude, shells positive and holes negative, so winding order does not
     * matter and a coverage polygon with a gap in the middle does not count the gap.
     */
    public static double sphericalAreaM2(Geometry polygonal) {
        if (polygonal == null || polygonal.isEmpty()) {
            return 0;
        }
        double total = 0;
        for (int g = 0; g < polygonal.getNumGeometries(); g++) {
            if (polygonal.getGeometryN(g) instanceof Polygon p) {
                total += ringAreaM2(p.getExteriorRing().getCoordinates());
                for (int h = 0; h < p.getNumInteriorRing(); h++) {
                    total -= ringAreaM2(p.getInteriorRingN(h).getCoordinates());
                }
            }
        }
        return Math.max(total, 0);
    }

    /**
     * One closed ring, unsigned: {@code R²/2 · |Σ (λᵢ₊₁ − λᵢ)(sin φᵢ + sin φᵢ₊₁)|}.
     */
    private static double ringAreaM2(Coordinate[] ring) {
        if (ring.length < 4) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < ring.length - 1; i++) {
            double lon1 = Math.toRadians(ring[i].x), lat1 = Math.toRadians(ring[i].y);
            double lon2 = Math.toRadians(ring[i + 1].x), lat2 = Math.toRadians(ring[i + 1].y);
            sum += (lon2 - lon1) * (Math.sin(lat1) + Math.sin(lat2));
        }
        return Math.abs(sum) * EARTH_RADIUS_M * EARTH_RADIUS_M / 2;
    }
}
