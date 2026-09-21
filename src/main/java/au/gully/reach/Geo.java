package au.gully.reach;

/**
 * Spherical geometry, on a 6,371 km earth: good to a few metres over the fifty kilometres a reach is
 * drawn across, which is finer than the 90 m the terrain is sampled at.
 */
public final class Geo {

    public static final double EARTH_RADIUS_KM = 6371.0088;

    private Geo() {
    }

    /**
     * The point a distance along a bearing from a start, as {@code {lat, lon}} in degrees.
     */
    public static double[] destination(double lat, double lon, double bearingDeg, double km) {
        double d = km / EARTH_RADIUS_KM;
        double b = Math.toRadians(bearingDeg);
        double f1 = Math.toRadians(lat), l1 = Math.toRadians(lon);
        double f2 = Math.asin(Math.sin(f1) * Math.cos(d) + Math.cos(f1) * Math.sin(d) * Math.cos(b));
        double l2 = l1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(f1), Math.cos(d) - Math.sin(f1) * Math.sin(f2));
        double lonOut = Math.toDegrees(l2);
        lonOut = ((lonOut + 540) % 360) - 180;
        return new double[]{Math.toDegrees(f2), lonOut};
    }

    /**
     * The initial bearing from one point to another, degrees clockwise from north, 0 to 360.
     */
    public static double bearingDeg(double lat1, double lon1, double lat2, double lon2) {
        double f1 = Math.toRadians(lat1), f2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(f2);
        double x = Math.cos(f1) * Math.sin(f2) - Math.sin(f1) * Math.cos(f2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /**
     * The great-circle distance between two points, kilometres.
     */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double f1 = Math.toRadians(lat1), f2 = Math.toRadians(lat2);
        double df = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(df / 2) * Math.sin(df / 2) + Math.cos(f1) * Math.cos(f2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_KM * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * The area of a small polygon on the sphere, square kilometres: the vertices projected onto a
     * plane tangent at their mean, then the shoelace. Exact enough for a shape fifty kilometres across.
     */
    public static double areaKm2(double[][] latLon) {
        int n = latLon.length;
        if (n < 3) {
            return 0;
        }
        double lat0 = 0, lon0 = 0;
        for (double[] p : latLon) {
            lat0 += p[0];
            lon0 += p[1];
        }
        lat0 /= n;
        lon0 /= n;
        double kmPerDegLat = Math.toRadians(1) * EARTH_RADIUS_KM;
        double kmPerDegLon = kmPerDegLat * Math.cos(Math.toRadians(lat0));
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double[] a = latLon[i], b = latLon[(i + 1) % n];
            double ax = (a[1] - lon0) * kmPerDegLon, ay = (a[0] - lat0) * kmPerDegLat;
            double bx = (b[1] - lon0) * kmPerDegLon, by = (b[0] - lat0) * kmPerDegLat;
            sum += ax * by - bx * ay;
        }
        return Math.abs(sum) / 2;
    }
}
