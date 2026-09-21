package au.gully.reach;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A station's reach: the ground it speaks for, as a polygon drawn from its terrain by a rule. Along
 * each bearing a ray walks out a kilometre at a time and stops when its cost — the distance, plus
 * what the greatest height difference it has crossed so far costs — exceeds the reach. The
 * <em>greatest</em> difference, not the current one, so a ridge is a barrier: the far side of a
 * hill is another climate even where it is the station's own height again. A ray cut by height
 * still reaches {@link ReachRule#MIN_KM}, so every station has some ground.
 *
 * @param km    how far the reach goes on each bearing, in bearing order
 * @param cut   why each ray stopped: {@code distance} at the reach itself, {@code height} at the
 *              cost of the ground, {@code unknown} where the model had nothing
 * @param ring  the polygon, one vertex per bearing, closed (the first vertex again at the end), as
 *              {@code {lat, lon}}
 */
public record Reach(String stationId, ReachRule.Rule rule, double[] km, Cut[] cut, double[][] ring, double areaKm2) {

    public enum Cut { DISTANCE, HEIGHT, UNKNOWN }

    /**
     * The reach of a station under a rule, from its terrain.
     */
    public static Reach of(Terrain t, ReachRule.Rule rule) {
        double[] km = new double[Terrain.BEARINGS];
        Cut[] cut = new Cut[Terrain.BEARINGS];
        for (int b = 0; b < Terrain.BEARINGS; b++) {
            double maxDiff = 0;
            double reached = 0;
            Cut why = Cut.DISTANCE;
            for (int s = 1; s <= Terrain.STEPS; s++) {
                double e = t.at(b, s);
                if (Double.isNaN(e)) {
                    why = Cut.UNKNOWN;
                    break;
                }
                double d = s * Terrain.STEP_KM;
                maxDiff = Math.max(maxDiff, Math.abs(e - t.elevationM()));
                double cost = d + rule.kmPer100m() * maxDiff / 100.0;
                if (cost > rule.reachKm()) {
                    why = d > rule.reachKm() ? Cut.DISTANCE : Cut.HEIGHT;
                    break;
                }
                reached = d;
            }
            if (why == Cut.HEIGHT) {
                reached = Math.max(reached, ReachRule.MIN_KM);
            }
            km[b] = reached;
            cut[b] = why;
        }
        double[][] ring = new double[Terrain.BEARINGS + 1][];
        for (int b = 0; b < Terrain.BEARINGS; b++) {
            ring[b] = Geo.destination(t.lat(), t.lon(), Terrain.bearingDeg(b), km[b]);
        }
        ring[Terrain.BEARINGS] = ring[0];
        double[][] open = new double[Terrain.BEARINGS][];
        System.arraycopy(ring, 0, open, 0, Terrain.BEARINGS);
        return new Reach(t.stationId(), rule, km, cut, ring, Math.round(Geo.areaKm2(open) * 10) / 10.0);
    }

    public double minKm() {
        double m = Double.MAX_VALUE;
        for (double k : km) {
            m = Math.min(m, k);
        }
        return m;
    }

    public double maxKm() {
        double m = 0;
        for (double k : km) {
            m = Math.max(m, k);
        }
        return m;
    }

    public double meanKm() {
        double sum = 0;
        for (double k : km) {
            sum += k;
        }
        return Math.round(sum / km.length * 10) / 10.0;
    }

    /**
     * How many rays each reason stopped.
     */
    public Map<Cut, Integer> cuts() {
        Map<Cut, Integer> out = new java.util.EnumMap<>(Cut.class);
        for (Cut c : Cut.values()) {
            out.put(c, 0);
        }
        for (Cut c : cut) {
            out.merge(c, 1, Integer::sum);
        }
        return out;
    }

    /**
     * The ring as GeoJSON wants it: {@code [lon, lat]} pairs, closed.
     */
    public List<List<Double>> coordinates() {
        List<List<Double>> out = new ArrayList<>(ring.length);
        for (double[] p : ring) {
            out.add(List.of(Math.round(p[1] * 1e5) / 1e5, Math.round(p[0] * 1e5) / 1e5));
        }
        return out;
    }
}
