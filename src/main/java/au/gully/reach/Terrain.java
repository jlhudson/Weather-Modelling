package au.gully.reach;

import java.nio.ByteBuffer;
import java.time.Instant;

/**
 * The ground around one station, sampled once: the station's own height from the digital elevation
 * model, and the height every kilometre out to {@link #MAX_KM} along {@link #BEARINGS} bearings. A
 * reach is drawn from this by arithmetic, so the sliders cost nothing upstream; the samples cost
 * {@link #POINTS} points, taken a hundred to a call, once per station.
 *
 * @param elevationM the model's height at the station itself, which the differences are taken from
 *                   (the Bureau's own figure is kept beside it, but the samples and the reference
 *                   should come from the same model)
 * @param elevations {@code BEARINGS × STEPS} values, bearing-major, step 1 first; NaN where the
 *                   model had nothing
 */
public record Terrain(String stationId, double lat, double lon, double elevationM, double[] elevations,
                      Instant sampledAt, int calls) {

    /**
     * Every 7.5°: fine enough that the boundary between two rays forty kilometres out is five
     * kilometres of straight line.
     */
    public static final int BEARINGS = 48;
    public static final double STEP_KM = 1.0;
    public static final int STEPS = 50;
    public static final double MAX_KM = STEPS * STEP_KM;
    /**
     * The station itself, then every step on every bearing.
     */
    public static final int POINTS = 1 + BEARINGS * STEPS;

    public Terrain {
        if (elevations.length != BEARINGS * STEPS) {
            throw new IllegalArgumentException("terrain wants " + (BEARINGS * STEPS) + " samples, got " + elevations.length);
        }
    }

    public static double bearingDeg(int bearing) {
        return bearing * 360.0 / BEARINGS;
    }

    /**
     * The height at a step out along a bearing; NaN where the model had nothing.
     *
     * @param step 1 to {@link #STEPS}
     */
    public double at(int bearing, int step) {
        return elevations[bearing * STEPS + (step - 1)];
    }

    /**
     * The sample points for a station, in the order the samples are kept: the station, then every
     * bearing's steps. Each is {@code {lat, lon}}.
     */
    public static double[][] points(double lat, double lon) {
        double[][] out = new double[POINTS][];
        out[0] = new double[]{lat, lon};
        int i = 1;
        for (int b = 0; b < BEARINGS; b++) {
            for (int s = 1; s <= STEPS; s++) {
                out[i++] = Geo.destination(lat, lon, bearingDeg(b), s * STEP_KM);
            }
        }
        return out;
    }

    /**
     * The samples as they are kept: eight bytes a value, the station's own first.
     */
    public byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(8 * (1 + elevations.length));
        buf.putDouble(elevationM);
        for (double e : elevations) {
            buf.putDouble(e);
        }
        return buf.array();
    }

    public static Terrain fromBytes(String stationId, double lat, double lon, byte[] bytes, Instant sampledAt, int calls) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        double own = buf.getDouble();
        double[] e = new double[BEARINGS * STEPS];
        for (int i = 0; i < e.length; i++) {
            e[i] = buf.getDouble();
        }
        return new Terrain(stationId, lat, lon, own, e, sampledAt, calls);
    }
}
