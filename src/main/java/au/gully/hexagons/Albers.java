package au.gully.hexagons;

import lombok.experimental.UtilityClass;

/**
 * The Australian Albers equal-area conic projection (the one EPSG:3577 is, in its spherical form): the
 * plane the hexagons are drawn on. Every national raster and most state ones are published in it,
 * and on it a 15 km hexagon is 15 km from Cape York to Hobart — the cos-latitude squeeze a plain
 * lat/lon grid would suffer (a degree of longitude is 82 per cent as long at Adelaide as at Darwin)
 * is what this exists to remove.
 * <p>
 * Standard parallels 18° S and 36° S, central meridian 132° E, origin at the equator. Spherical
 * rather than ellipsoidal (Snyder 1987, eqs. 14-1 to 14-11): the difference is a few hundred metres
 * in position, which matters to nobody keying a 15 km hexagon, and the arithmetic fits on one screen.
 */
@UtilityClass
public class Albers {

    public static final double R = 6_371_008.8;
    private static final double PHI1 = Math.toRadians(-18);
    private static final double PHI2 = Math.toRadians(-36);
    private static final double PHI0 = 0;
    private static final double LAMBDA0 = Math.toRadians(132);

    private static final double N = (Math.sin(PHI1) + Math.sin(PHI2)) / 2;
    private static final double C = Math.cos(PHI1) * Math.cos(PHI1) + 2 * N * Math.sin(PHI1);
    private static final double RHO0 = R * Math.sqrt(C - 2 * N * Math.sin(PHI0)) / N;

    /**
     * Forward: latitude and longitude in degrees to eastings and northings in metres.
     */
    public static double[] forward(double lat, double lon) {
        double phi = Math.toRadians(lat);
        double theta = N * (Math.toRadians(lon) - LAMBDA0);
        double rho = R * Math.sqrt(C - 2 * N * Math.sin(phi)) / N;
        return new double[]{rho * Math.sin(theta), RHO0 - rho * Math.cos(theta)};
    }

    /**
     * Inverse: eastings and northings in metres to latitude and longitude in degrees. With a negative
     * {@code n} (the southern hemisphere) the signs of {@code x}, {@code y} and {@code rho0} are
     * reversed before the arctangent, as Snyder prescribes, or every point lands mirrored.
     */
    public static double[] inverse(double x, double y) {
        double sign = Math.signum(N);
        double rho = sign * Math.sqrt(x * x + (RHO0 - y) * (RHO0 - y));
        double theta = Math.atan2(sign * x, sign * (RHO0 - y));
        double phi = Math.asin((C - (rho * N / R) * (rho * N / R)) / (2 * N));
        double lambda = LAMBDA0 + theta / N;
        return new double[]{Math.toDegrees(phi), Math.toDegrees(lambda)};
    }
}
