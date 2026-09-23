package au.gully.reach;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A station's reach: the ground it speaks for, as a polygon drawn from its terrain by a rule. Along
 * each bearing a ray walks out a kilometre at a time and stops when its cost — the distance, plus
 * what the ground it has crossed costs — exceeds the reach. A ray cut by height still reaches
 * {@link ReachRule#MIN_KM}, so every station has some ground.
 * <p>
 * What the ground costs (W-16). Two things, which the one height cost used to confuse:
 * <ul>
 *   <li><b>A barrier has to hold.</b> Ground counts as a barrier only where it stays at its height
 *   for {@link #BARRIER_ACROSS_KM} — three samples in a row. A gully a kilometre or two across is
 *   crossed for nothing, so a ray along a dissected ridge keeps going; the scarp, four hundred
 *   metres up and staying up, is a wall as it was. What a ray has crossed is the greatest
 *   <em>sustained</em> difference so far, and a barrier costs from the step it begins at, not from
 *   the step the ray has seen three of.</li>
 *   <li><b>Climbing costs more than descending.</b> The rule's cost is what a hundred metres of
 *   <em>climb</em> costs; a hundred metres of descent costs {@link ReachRule.Rule#descentShare} of
 *   it. The plain's air does not climb the scarp, but the hills' air drains to the foothills — the
 *   gully wind this service is named for. The two are counted apart and added, so a ray that climbs
 *   a range and drops beyond it pays for both.</li>
 * </ul>
 * A ray ends at the water (W-3, W-12): the first sample of it stops the ray half a step short, so
 * the shore is inside the reach and the sea is not. Water is water where it is at least
 * {@link #WATER_ACROSS_KM} across along the ray — a river is a line and never is, the sea and the
 * big lakes are areas and always are.
 * <p>
 * Unless the station is an <em>island</em> (W-12): where the water would end at least
 * {@link #ISLAND_SHARE} of its rays — a station on a small island, or a few hundred metres out to sea
 * on a jetty — the water ends none of them. The sea is then ground at sea level for the height cost
 * and no more, and the station reaches across it to the shore beyond as it would across a plain.
 * <p>
 * How far the reach goes grows with the station's distance from the sea (W-19): the rule's reach, and
 * its inland share of that again for every hundred kilometres ({@link ReachRule.Rule#reachKmAt}), so the
 * outback's few stations speak for the wide country between them. The coastal limit that held a
 * station with water inside ten kilometres to a shorter reach is gone.
 *
 * @param km        how far the reach goes on each bearing, in bearing order
 * @param cut       why each ray stopped: {@code distance} at the reach itself, {@code height} at the
 *                  cost of the ground, {@code water} at the water's edge, {@code unknown} where the model
 *                  had nothing
 * @param reachKm   how far this station reaches over flat ground: the rule's reach grown by its distance from the sea
 * @param inlandKm  how far the station is from the sea, or null where not known
 * @param waterKm   how near the water is, on the bearing it is nearest, or null with none inside the terrain
 * @param island    whether the water would end at least {@link #ISLAND_SHARE} of the rays, so ends none
 * @param waterRays how many rays the water ends, or for an island would have ended
 * @param ring      the polygon, one vertex per bearing, closed (the first vertex again at the end), as
 *                  {@code {lat, lon}}
 */
public record Reach(String stationId, ReachRule.Rule rule, double[] km, Cut[] cut, double reachKm, Double inlandKm, Double waterKm,
                    boolean island, int waterRays, double[][] ring, double areaKm2) {

    public enum Cut { DISTANCE, HEIGHT, WATER, UNKNOWN }

    /**
     * The share of a station's rays the water would have to end for the station to be an island,
     * and the water to end none: three quarters.
     */
    public static final double ISLAND_SHARE = 0.75;

    /**
     * How far ground has to hold its height to be a barrier (W-16): three samples in a row. Narrower
     * than the gullies that dissect the Mount Lofty Ranges, wide enough that the scarp is a wall.
     */
    public static final int BARRIER_ACROSS_SAMPLES = 3;
    public static final double BARRIER_ACROSS_KM = BARRIER_ACROSS_SAMPLES * Terrain.STEP_KM;

    /**
     * How wide water has to be along a ray to be water: three samples in a row at or below sea
     * level. The Murray is a kilometre across at its widest; Lake Alexandrina and the gulfs are tens.
     */
    public static final int WATER_ACROSS_SAMPLES = 3;
    public static final double WATER_ACROSS_KM = WATER_ACROSS_SAMPLES * Terrain.STEP_KM;

    /**
     * What a ray has crossed: the greatest sustained climb above the station, and the greatest
     * sustained descent below it, in metres.
     */
    public record Crossed(double upM, double downM) {
        public static final Crossed NONE = new Crossed(0, 0);
    }

    /**
     * The step at which the water begins on a bearing — the first of {@link #WATER_ACROSS_SAMPLES}
     * in a row at or below sea level — or 0 when the ray meets none.
     */
    static int waterAt(Terrain t, int b) {
        int run = 0;
        for (int s = 1; s <= Terrain.STEPS; s++) {
            double e = t.at(b, s);
            if (!Double.isNaN(e) && e <= 0) {
                run++;
                if (run == WATER_ACROSS_SAMPLES) {
                    return s - WATER_ACROSS_SAMPLES + 1;
                }
            } else {
                run = 0;
            }
        }
        return 0;
    }

    /**
     * How far above and below the station each sample on a bearing is, sea taken as sea level
     * (W-10, W-12) whatever depth the tiles give it. NaN where the model had nothing.
     */
    static double[][] difference(Terrain t, int b) {
        double[] up = new double[Terrain.STEPS + 1];
        double[] down = new double[Terrain.STEPS + 1];
        for (int s = 1; s <= Terrain.STEPS; s++) {
            double e = t.at(b, s);
            if (Double.isNaN(e)) {
                up[s] = Double.NaN;
                down[s] = Double.NaN;
            } else {
                double d = Math.max(0, e) - t.elevationM();
                up[s] = Math.max(0, d);
                down[s] = Math.max(0, -d);
            }
        }
        return new double[][]{up, down};
    }

    /**
     * The barrier that begins at each step: the least difference the next
     * {@link #BARRIER_ACROSS_SAMPLES} samples all hold. A dip or a rise narrower than that has a
     * sample at the station's own height inside its window, so it is nothing.
     */
    static double[] sustained(double[] difference) {
        double[] out = new double[difference.length];
        for (int j = 1; j < difference.length; j++) {
            double least = Double.MAX_VALUE;
            int seen = 0;
            for (int k = j; k < Math.min(difference.length, j + BARRIER_ACROSS_SAMPLES); k++) {
                if (!Double.isNaN(difference[k])) {
                    least = Math.min(least, difference[k]);
                    seen++;
                }
            }
            out[j] = seen == 0 ? 0 : least;
        }
        return out;
    }

    /**
     * What the ray on a bearing has crossed by a step: the greatest sustained climb and descent.
     */
    public static Crossed crossed(Terrain t, int bearing, int steps) {
        double[][] d = difference(t, bearing);
        double[] up = sustained(d[0]), down = sustained(d[1]);
        double maxUp = 0, maxDown = 0;
        for (int s = 1; s <= Math.min(steps, Terrain.STEPS); s++) {
            maxUp = Math.max(maxUp, up[s]);
            maxDown = Math.max(maxDown, down[s]);
        }
        return new Crossed(maxUp, maxDown);
    }

    /**
     * What a distance costs under a rule, having crossed what it has: the distance itself, plus the
     * climb at the rule's cost and the descent at its share of it.
     */
    public static double costKm(double km, Crossed c, ReachRule.Rule rule) {
        return km + rule.kmPer100m() * (c.upM() + rule.descentShare() * c.downM()) / 100.0;
    }

    /**
     * The reach of a station under a rule, from its terrain.
     */
    public static Reach of(Terrain t, ReachRule.Rule rule) {
        double reachKm = rule.reachKmAt(t.inlandKm());
        double[] km = new double[Terrain.BEARINGS];
        Cut[] cut = new Cut[Terrain.BEARINGS];
        int[] water = new int[Terrain.BEARINGS];
        Double nearestWater = null;
        // First every ray as if the water were ground at sea level: the distance and the ground end it.
        for (int b = 0; b < Terrain.BEARINGS; b++) {
            double[][] d = difference(t, b);
            double[] up = sustained(d[0]), down = sustained(d[1]);
            double maxUp = 0, maxDown = 0;
            double reached = 0;
            Cut why = Cut.DISTANCE;
            water[b] = waterAt(t, b);
            for (int s = 1; s <= Terrain.STEPS; s++) {
                if (Double.isNaN(d[0][s])) {
                    why = Cut.UNKNOWN;
                    break;
                }
                double dist = s * Terrain.STEP_KM;
                if (s == water[b] && (nearestWater == null || dist < nearestWater)) {
                    nearestWater = dist;
                }
                maxUp = Math.max(maxUp, up[s]);
                maxDown = Math.max(maxDown, down[s]);
                if (costKm(dist, new Crossed(maxUp, maxDown), rule) > reachKm) {
                    why = dist > reachKm ? Cut.DISTANCE : Cut.HEIGHT;
                    break;
                }
                reached = dist;
            }
            if (why == Cut.HEIGHT) {
                reached = Math.max(reached, ReachRule.MIN_KM);
            }
            km[b] = reached;
            cut[b] = why;
        }
        // Then the water: the rays it lies across would end at its edge, half a step short of the first sample.
        double[] edge = new double[Terrain.BEARINGS];
        int waterRays = 0;
        for (int b = 0; b < Terrain.BEARINGS; b++) {
            edge[b] = water[b] > 0 ? water[b] * Terrain.STEP_KM - Terrain.STEP_KM / 2 : Double.NaN;
            if (edge[b] < km[b]) {
                waterRays++;
            }
        }
        boolean island = waterRays >= ISLAND_SHARE * Terrain.BEARINGS;
        if (!island) {
            for (int b = 0; b < Terrain.BEARINGS; b++) {
                if (edge[b] < km[b]) {
                    km[b] = edge[b];
                    cut[b] = Cut.WATER;
                }
            }
        }
        double[][] ring = new double[Terrain.BEARINGS + 1][];
        for (int b = 0; b < Terrain.BEARINGS; b++) {
            ring[b] = Geo.destination(t.lat(), t.lon(), Terrain.bearingDeg(b), km[b]);
        }
        ring[Terrain.BEARINGS] = ring[0];
        double[][] open = new double[Terrain.BEARINGS][];
        System.arraycopy(ring, 0, open, 0, Terrain.BEARINGS);
        return new Reach(t.stationId(), rule, km, cut, reachKm, t.inlandKm(), nearestWater, island, waterRays, ring, Math.round(Geo.areaKm2(open) * 10) / 10.0);
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
