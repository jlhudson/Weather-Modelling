package au.gully.reading;

import java.util.List;

/**
 * Height-corrected inverse-distance weighting (W-8), as arithmetic. Each station in reach weighs
 * {@code 1 / cost²}, the cost being the distance to the point plus what the greatest height
 * difference along the ray towards it costs - the same cost the reach itself is drawn by, so a
 * station across rising ground counts for less than one across the flat at the same distance.
 * Temperature and dew point are brought to the point's height by a lapse rate before they are
 * blended; wind, humidity, rain and the drought's numbers are blended as they are; the wind's
 * direction as a vector. A station without a value stays out of that value's blend, so every
 * value names its own contributors.
 */
public final class Blend {

    /**
     * The environmental lapse rate: temperature falls this much per kilometre of height.
     */
    public static final double LAPSE_C_PER_KM = 6.5;
    /**
     * The dew point falls more slowly with height than the temperature does.
     */
    public static final double DEW_POINT_LAPSE_C_PER_KM = 2.0;
    /**
     * The power the cost is raised to, and the least cost a station is given so one at the point
     * itself does not weigh infinitely.
     */
    public static final double POWER = 2;
    public static final double LEAST_COST_KM = 0.5;

    private Blend() {
    }

    /**
     * One station's part in a blend: its weight, and the value it brings.
     */
    public record Part(String id, double weight, double value) {
    }

    /**
     * The weight for a cost.
     */
    public static double weight(double costKm) {
        return 1.0 / Math.pow(Math.max(LEAST_COST_KM, costKm), POWER);
    }

    /**
     * A value at a station brought to the point's height, or as it is when either height is unknown:
     * a station 400 m above the point at 10 °C says 12.6 at the point, the air warming as it descends.
     */
    public static Double toHeight(Double value, Double stationM, Double pointM, double lapsePerKm) {
        if (value == null || stationM == null || pointM == null) {
            return value;
        }
        return value + lapsePerKm * (stationM - pointM) / 1000.0;
    }

    /**
     * The weighted mean of the parts, or null with none.
     */
    public static Double mean(List<Part> parts) {
        if (parts.isEmpty()) {
            return null;
        }
        double sum = 0, weights = 0;
        for (Part p : parts) {
            sum += p.weight() * p.value();
            weights += p.weight();
        }
        return weights == 0 ? null : sum / weights;
    }

    /**
     * The weighted mean of bearings, as a bearing: the parts added as unit vectors.
     */
    public static Integer meanBearing(List<Part> parts) {
        if (parts.isEmpty()) {
            return null;
        }
        double x = 0, y = 0;
        for (Part p : parts) {
            double a = Math.toRadians(p.value());
            x += p.weight() * Math.sin(a);
            y += p.weight() * Math.cos(a);
        }
        if (Math.abs(x) < 1e-9 && Math.abs(y) < 1e-9) {
            return null;
        }
        return (int) Math.round((Math.toDegrees(Math.atan2(x, y)) + 360) % 360);
    }
}
