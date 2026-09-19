package au.gully.hexagons;

import lombok.experimental.UtilityClass;

/**
 * The little spatial arithmetic left after the overhaul: distances, for the nearest station and the
 * drought maths, and the one validation of a point. The cells themselves are {@link Grid}'s.
 */
@UtilityClass
public class Geo {

    public static final double EARTH_RADIUS_M = 6_371_008.8;

    /**
     * Great-circle distance in metres.
     */
    public static double haversineMetres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Whether a pair of numbers is a place on the Earth. A swapped pair puts an Adelaide point in
     * Kazakhstan, and a NaN is not anywhere.
     */
    public static boolean plausible(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    /**
     * Whether a point is on or near the Australian continent and its islands — the only ground the
     * grid, the Bureau and the CFS have anything to say about. Generous: Lord Howe and Macquarie are in.
     */
    public static boolean inAustralia(double lat, double lon) {
        return lat >= -55 && lat <= -8 && lon >= 108 && lon <= 162;
    }
}
