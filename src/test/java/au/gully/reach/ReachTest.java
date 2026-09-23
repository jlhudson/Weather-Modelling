package au.gully.reach;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.function.IntBinaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ReachTest {

    static final double LAT = -34.9257, LON = 138.5832;

    /**
     * Terrain from a function of (bearing, step) to height.
     */
    static Terrain terrain(double own, IntBinaryOperator height) {
        double[] e = new double[Terrain.BEARINGS * Terrain.STEPS];
        for (int b = 0; b < Terrain.BEARINGS; b++) {
            for (int s = 1; s <= Terrain.STEPS; s++) {
                e[b * Terrain.STEPS + s - 1] = height.applyAsInt(b, s);
            }
        }
        return new Terrain("023000", LAT, LON, own, e, Instant.parse("2026-09-21T10:00:00Z"), 25);
    }

    @Test
    void flatGroundReachesTheRuleInEveryDirection() {
        Reach r = Reach.of(terrain(30, (b, s) -> 30), ReachRule.Rule.of(40, 10));
        assertThat(r.km()).containsOnly(40.0);
        assertThat(r.cuts()).containsEntry(Reach.Cut.DISTANCE, Terrain.BEARINGS).containsEntry(Reach.Cut.HEIGHT, 0);
        assertThat(r.minKm()).isEqualTo(40);
        assertThat(r.maxKm()).isEqualTo(40);
        // A disc of 40 km: pi r squared, within the polygon's shortfall on the circle.
        assertThat(r.areaKm2()).isCloseTo(Math.PI * 40 * 40, within(60.0));
        // The ring is closed and its vertices are where the rays end.
        assertThat(r.ring().length).isEqualTo(Terrain.BEARINGS + 1);
        assertThat(r.ring()[0]).containsExactly(r.ring()[Terrain.BEARINGS]);
        assertThat(Geo.distanceKm(LAT, LON, r.ring()[0][0], r.ring()[0][1])).isCloseTo(40, within(0.01));
        assertThat(r.ring()[0][0]).as("north first").isGreaterThan(LAT);
    }

    @Test
    void aRidgeIsABarrierAndTheFarSideStaysOut() {
        // Bearing 0 (north): flat. Bearing 24 (south): a 400 m ridge from 10 to 12 km, then the station's own height again.
        Terrain t = terrain(30, (b, s) -> b == 24 ? (s >= 10 && s <= 12 ? 430 : 30) : 30);
        Reach r = Reach.of(t, ReachRule.Rule.of(40, 10));
        assertThat(r.km()[0]).isEqualTo(40);
        // At 10 km the difference is 400 m, costing 40 km: 10 + 40 > 40, so the ray stops at 9 km - and stays
        // stopped, because the greatest difference crossed is what counts, not the height at the point.
        assertThat(r.km()[24]).isEqualTo(9);
        assertThat(r.cut()[24]).isEqualTo(Reach.Cut.HEIGHT);
        assertThat(r.cut()[0]).isEqualTo(Reach.Cut.DISTANCE);
        // Without a height cost the ridge is nothing.
        assertThat(Reach.of(t, ReachRule.Rule.of(40, 0)).km()[24]).isEqualTo(40);
    }

    @Test
    void aStationOnAHillKeepsTheHillAndNotThePlain() {
        // Mount Lofty: 700 m, the ground falling 100 m a kilometre on every bearing to the plain at 50 m.
        Terrain t = terrain(700, (b, s) -> Math.max(50, 700 - 100 * s));
        Reach r = Reach.of(t, ReachRule.Rule.of(40, 10));
        // Descending costs half (W-16): at 7 km the sustained drop is 650 m, costing 32.5, and 7 + 32.5 fits; 8 does not.
        assertThat(r.km()).containsOnly(7.0);
        assertThat(r.cuts()).containsEntry(Reach.Cut.HEIGHT, Terrain.BEARINGS);
        // The same fall above the station - a station in a hole - costs twice as much, so the reach is half.
        Reach hole = Reach.of(terrain(50, (b, s) -> Math.min(700, 50 + 100 * s)), ReachRule.Rule.of(40, 10));
        assertThat(hole.km()).containsOnly(3.0);
        // Share zero: a descent is nothing, and the hill station reaches the rule.
        assertThat(Reach.of(t, ReachRule.Rule.of(40, 10, 25, 0)).km()).containsOnly(40.0);
        // Share one: climbing and descending cost alike, as they did before W-16.
        assertThat(Reach.of(t, ReachRule.Rule.of(40, 10, 25, 1)).km()).containsOnly(3.0);
        // A steeper hill still gets the floor.
        Reach steep = Reach.of(terrain(700, (b, s) -> 50), ReachRule.Rule.of(40, 10, 25, 1));
        assertThat(steep.km()).containsOnly(ReachRule.MIN_KM);
    }

    @Test
    void aGullyIsCrossedAndAWallThatHoldsIsABarrier() {
        // Bearing 0: a 400 m gorge one kilometre across at 6 km. Bearing 12: the same, two kilometres across.
        // Bearing 24: three kilometres across, and it holds. Bearing 36: a 400 m ridge three kilometres across.
        Terrain t = terrain(430, (b, s) -> switch (b) {
            case 0 -> s == 6 ? 30 : 430;
            case 12 -> (s == 6 || s == 7) ? 30 : 430;
            case 24 -> (s >= 6 && s <= 8) ? 30 : 430;
            case 36 -> (s >= 6 && s <= 8) ? 830 : 430;
            default -> 430;
        });
        Reach r = Reach.of(t, ReachRule.Rule.of(40, 10));
        // One and two samples are not a barrier: the ray goes the whole way.
        assertThat(r.km()[0]).isEqualTo(40);
        assertThat(r.km()[12]).isEqualTo(40);
        // Three samples hold, so the 400 m drop is a barrier - but descending costs half, 20 km of the 40, so the ray
        // crosses the gorge and runs to 20 km on what is left.
        assertThat(r.km()[24]).isEqualTo(20);
        assertThat(r.cut()[24]).isEqualTo(Reach.Cut.HEIGHT);
        // The same barrier above the station costs the whole 40 km, and the ray ends at the foot of it: 5 km, the step
        // before its first sample. A wall is a wall; a drop is a discount.
        assertThat(r.km()[36]).isEqualTo(5);
        assertThat(r.cut()[36]).isEqualTo(Reach.Cut.HEIGHT);
        // The barrier costs from where it begins, not from where its third sample is: the ray does not walk into it.
        assertThat(Reach.of(t, ReachRule.Rule.of(40, 10, 25, 1)).km()[24]).as("the step before the first sample of the gorge").isEqualTo(5);
    }

    @Test
    void aGentleSlopeShortensTheReachALittleRatherThanCuttingIt() {
        // 10 m a kilometre: at 30 km the difference is 300 m, costing 30, so 30 + 30 = 60 > 40; at 20 km 20 + 20 = 40 fits.
        Reach r = Reach.of(terrain(0, (b, s) -> 10 * s), ReachRule.Rule.of(40, 10));
        assertThat(r.km()).containsOnly(20.0);
        assertThat(r.cut()[0]).isEqualTo(Reach.Cut.HEIGHT);
    }

    @Test
    void aRayEndsAtTheWaterAndNoLimitHoldsTheRest() {
        // The sea from 4 km out on bearings 12 to 36 (east round through south to west), read at minus five; land at 10 m elsewhere.
        Terrain t = terrain(10, (b, s) -> b >= 12 && b <= 36 && s >= 4 ? -5 : 10);
        Reach r = Reach.of(t, ReachRule.Rule.of(40, 10));
        // The ray stops half a step short of the first sample of water: the beach is inside, the sea is not.
        assertThat(r.km()[24]).isEqualTo(3.5);
        assertThat(r.cut()[24]).isEqualTo(Reach.Cut.WATER);
        assertThat(r.waterKm()).isEqualTo(4);
        // Twenty-five of forty-eight rays at the water: a shore, not an island.
        assertThat(r.waterRays()).isEqualTo(25);
        assertThat(r.island()).isFalse();
        // No coastal limit (W-19): the landward rays run the whole reach.
        assertThat(r.km()[0]).isEqualTo(40);
        assertThat(r.cut()[0]).isEqualTo(Reach.Cut.DISTANCE);
        assertThat(r.cuts()).containsEntry(Reach.Cut.WATER, 25).containsEntry(Reach.Cut.DISTANCE, 23);
        // Water further out: the ray still ends at it.
        Reach inland = Reach.of(terrain(10, (b, s) -> b == 6 && s >= 30 ? 0 : 10), ReachRule.Rule.of(40, 10));
        assertThat(inland.waterKm()).isEqualTo(30);
        assertThat(inland.km()[6]).isEqualTo(29.5);
        assertThat(inland.cut()[6]).isEqualTo(Reach.Cut.WATER);
        assertThat(inland.km()[0]).isEqualTo(40);
        // Water beyond the reach is not a border: the ray ends at the reach first.
        Reach far = Reach.of(terrain(10, (b, s) -> b == 6 && s >= 41 ? 0 : 10), ReachRule.Rule.of(40, 10));
        assertThat(far.km()[6]).isEqualTo(40);
        assertThat(far.cut()[6]).isEqualTo(Reach.Cut.DISTANCE);
        assertThat(far.waterRays()).isZero();
        // A ridge crossed before the water: three samples of it, so it holds; the ray stops at it and the water beyond is not seen.
        Reach ridge = Reach.of(terrain(10, (b, s) -> b == 0 ? ((s >= 4 && s <= 6) ? 600 : s >= 9 ? -5 : 10) : 10), ReachRule.Rule.of(40, 10));
        assertThat(ridge.cut()[0]).isEqualTo(Reach.Cut.HEIGHT);
        // One sample of ridge is no barrier (W-16): the ray crosses it and ends at the water.
        Reach nick = Reach.of(terrain(10, (b, s) -> b == 0 ? (s == 5 ? 600 : s >= 9 ? -5 : 10) : 10), ReachRule.Rule.of(40, 10));
        assertThat(nick.cut()[0]).isEqualTo(Reach.Cut.WATER);
    }

    @Test
    void anIslandIgnoresTheWater() {
        // A station a kilometre out on a jetty: the sea from 2 km on every bearing but the six back along the shore (bearings 21 to 26).
        Terrain t = terrain(5, (b, s) -> b >= 21 && b <= 26 ? 5 : s >= 2 ? -20 : 5);
        Reach r = Reach.of(t, ReachRule.Rule.of(40, 10));
        // The water would end 42 of 48 rays: an island, so it ends none, and the sea costs only its five metres below the station.
        assertThat(r.waterRays()).isEqualTo(42);
        assertThat(r.island()).isTrue();
        assertThat(r.cuts()).containsEntry(Reach.Cut.WATER, 0);
        assertThat(r.waterKm()).isEqualTo(2);
        // The sea costs its depth as ground at sea level - not the tiles' minus twenty - and the shore runs the whole reach.
        assertThat(r.km()[0]).as("40 + 0.5 exceeds the reach; 39 + 0.5 does not").isEqualTo(39);
        assertThat(r.cut()[0]).isEqualTo(Reach.Cut.HEIGHT);
        assertThat(r.km()[24]).isEqualTo(40);
        // Just under the share - 35 of 48 - and the water is a border again.
        Reach shore = Reach.of(terrain(5, (b, s) -> b >= 21 && b <= 33 ? 5 : s >= 2 ? -20 : 5), ReachRule.Rule.of(40, 10));
        assertThat(shore.waterRays()).isEqualTo(35);
        assertThat(shore.island()).isFalse();
        assertThat(shore.km()[0]).isEqualTo(1.5);
        assertThat(shore.cut()[0]).isEqualTo(Reach.Cut.WATER);
        // Exactly the share - 36 of 48 - is an island.
        Reach edge = Reach.of(terrain(5, (b, s) -> b >= 21 && b <= 32 ? 5 : s >= 2 ? -20 : 5), ReachRule.Rule.of(40, 10));
        assertThat(edge.waterRays()).isEqualTo(36);
        assertThat(edge.island()).isTrue();
    }

    @Test
    void aRiverIsNotWaterAndALakeIsABorder() {
        // Bearing 0: a river a sample wide at 6 km. Bearing 12: two samples of water at 6 and 7 km. Bearing 24: a lake from 12 km on.
        Terrain t = terrain(39, (b, s) -> switch (b) {
            case 0 -> s == 6 ? -1 : 39;
            case 12 -> (s == 6 || s == 7) ? 0 : 39;
            case 24 -> s >= 12 ? 0 : 39;
            default -> 39;
        });
        Reach r = Reach.of(t, ReachRule.Rule.of(40, 10));
        // The river and the two-sample strip are crossed, and since W-16 a bed that narrow costs nothing at all.
        assertThat(r.km()[0]).isEqualTo(40);
        assertThat(r.km()[12]).isEqualTo(40);
        assertThat(r.cut()[0]).isEqualTo(Reach.Cut.DISTANCE);
        // The lake, 12 km away and three samples across, is water: the ray ends at its edge.
        assertThat(r.km()[24]).isEqualTo(11.5);
        assertThat(r.cut()[24]).isEqualTo(Reach.Cut.WATER);
        assertThat(r.waterKm()).isEqualTo(12);
        // With the lake 6 km away the ray towards it ends there, and nothing else is held back (W-19).
        Reach lakeside = Reach.of(terrain(39, (b, s) -> b == 24 ? (s >= 6 ? 0 : 39) : (s == 2 ? -1 : 39)), ReachRule.Rule.of(40, 10));
        assertThat(lakeside.waterKm()).isEqualTo(6);
        assertThat(lakeside.km()[24]).isEqualTo(5.5);
        assertThat(lakeside.km()[0]).isEqualTo(40);
    }

    @Test
    void theReachGrowsWithTheDistanceFromTheSea() {
        Terrain coast = terrain(30, (b, s) -> 30);
        Terrain inland = new Terrain("017031", LAT, LON, 30, coast.elevations(), coast.sampledAt(), 25, 300.0);
        ReachRule.Rule rule = ReachRule.Rule.of(40, 10, 20, .5);
        // 300 km from the sea at a fifth per hundred: 40 × 1.6 = 64 km on every bearing of flat ground.
        Reach r = Reach.of(inland, rule);
        assertThat(r.reachKm()).isEqualTo(64);
        assertThat(r.inlandKm()).isEqualTo(300);
        assertThat(r.km()).containsOnly(64.0);
        assertThat(r.cuts()).containsEntry(Reach.Cut.DISTANCE, Terrain.BEARINGS);
        // Not known, or no share: the reach itself.
        assertThat(Reach.of(coast, rule).km()).containsOnly(40.0);
        assertThat(Reach.of(inland, ReachRule.Rule.of(40, 10, 0, .5)).km()).containsOnly(40.0);
        // Never past what the terrain was sampled to.
        assertThat(rule.reachKmAt(2000.0)).isEqualTo(Terrain.MAX_KM);
        assertThat(rule.reachKmAt(55.0)).isEqualTo(44.4);
    }

    @Test
    void whereTheModelHasNothingTheRayStops() {
        double[] e = new double[Terrain.BEARINGS * Terrain.STEPS];
        Arrays.fill(e, 30);
        e[5 * Terrain.STEPS + 14] = Double.NaN; // bearing 5, step 15
        Reach r = Reach.of(new Terrain("x", LAT, LON, 30, e, Instant.now(), 25), ReachRule.Rule.of(40, 10));
        assertThat(r.km()[5]).isEqualTo(14);
        assertThat(r.cut()[5]).isEqualTo(Reach.Cut.UNKNOWN);
    }

    @Test
    void theRuleIsClampedToWhatTheTerrainCanAnswer() {
        assertThat(ReachRule.Rule.of(200, 10).reachKm()).isEqualTo(Terrain.MAX_KM);
        assertThat(ReachRule.Rule.of(1, 10).reachKm()).isEqualTo(ReachRule.MIN_KM);
        assertThat(ReachRule.Rule.of(40, -5).kmPer100m()).isEqualTo(0);
        assertThat(ReachRule.Rule.of(40, 99).kmPer100m()).isEqualTo(ReachRule.MAX_KM_PER_100M);
        assertThat(ReachRule.Rule.of(40.3, 10.1)).as("to the quarter").isEqualTo(new ReachRule.Rule(40.25, 10, ReachRule.DEFAULT_INLAND_PCT, ReachRule.DEFAULT_DESCENT_SHARE));
        // The inland share is a whole per cent, 0 to the most.
        assertThat(ReachRule.Rule.of(40, 10, 99, .5).inlandPct()).isEqualTo(ReachRule.MAX_INLAND_PCT);
        assertThat(ReachRule.Rule.of(40, 10, -3, .5).inlandPct()).isZero();
        assertThat(ReachRule.Rule.of(40, 10, 12.4, .5).inlandPct()).isEqualTo(12);
        // The descent share is a share, to the twentieth.
        assertThat(ReachRule.Rule.of(40, 10, 25, 1.4).descentShare()).isEqualTo(1);
        assertThat(ReachRule.Rule.of(40, 10, 25, -1).descentShare()).isZero();
        assertThat(ReachRule.Rule.of(40, 10, 25, 0.37).descentShare()).isEqualTo(0.35);
    }

    @Test
    void theTerrainRoundTripsThroughItsBytes() {
        Terrain t = terrain(29, (b, s) -> b * 100 + s);
        Terrain back = Terrain.fromBytes(t.stationId(), t.lat(), t.lon(), t.toBytes(), t.sampledAt(), t.calls(), 120.5);
        assertThat(back.inlandKm()).isEqualTo(120.5);
        // Sampled to another extent - the 50 km of before W-19 - and it is read as absent, to be sampled again.
        assertThat(Terrain.fromBytes("x", LAT, LON, new byte[8 * 2401], Instant.now(), 1, null)).isNull();
        assertThat(back.elevationM()).isEqualTo(29);
        assertThat(back.at(7, 3)).isEqualTo(703);
        assertThat(back.elevations()).containsExactly(t.elevations());
    }

    @Test
    void theSamplePointsAreTheStationThenEveryStepOnEveryBearing() {
        double[][] pts = Terrain.points(LAT, LON);
        assertThat(pts.length).isEqualTo(Terrain.POINTS).isEqualTo(7201);
        assertThat(pts[0]).containsExactly(LAT, LON);
        // Bearing 0, step 1: a kilometre north. Bearing 12 (90°), step 50: fifty kilometres east.
        assertThat(Geo.distanceKm(LAT, LON, pts[1][0], pts[1][1])).isCloseTo(1, within(0.001));
        assertThat(pts[1][0]).isGreaterThan(LAT);
        double[] east = pts[1 + 12 * Terrain.STEPS + 49];
        assertThat(Geo.distanceKm(LAT, LON, east[0], east[1])).isCloseTo(50, within(0.01));
        assertThat(east[1]).isGreaterThan(LON);
        assertThat(east[0]).isCloseTo(LAT, within(0.2));
    }
    @Test
    void aPointIsInsideTheReachWhereTheMapDrawsIt() {
        // Flat ground: a 40 km disc. Twenty kilometres north is inside, fifty is not, and the ring is what decides.
        Reach r = Reach.of(terrain(30, (b, s) -> 30), ReachRule.Rule.of(40, 10));
        org.locationtech.jts.geom.GeometryFactory gf = new org.locationtech.jts.geom.GeometryFactory();
        double[] near = Geo.destination(LAT, LON, 0, 20), far = Geo.destination(LAT, LON, 0, 50), edge = Geo.destination(LAT, LON, 3.75, 39.95);
        assertThat(Probe.polygon(r).contains(gf.createPoint(new org.locationtech.jts.geom.Coordinate(near[1], near[0])))).isTrue();
        assertThat(Probe.polygon(r).contains(gf.createPoint(new org.locationtech.jts.geom.Coordinate(far[1], far[0])))).isFalse();
        // Between two ray ends the edge is a chord, 86 m inside the arc: 39.95 km out halfway between bearings is outside.
        assertThat(Probe.polygon(r).contains(gf.createPoint(new org.locationtech.jts.geom.Coordinate(edge[1], edge[0])))).isFalse();
        // The ray a point lies on: bearings are 7.5° apart, the nearest counts, and 359° is the first.
        assertThat(Probe.bearingIndex(0)).isEqualTo(0);
        assertThat(Probe.bearingIndex(3.7)).isEqualTo(0);
        assertThat(Probe.bearingIndex(3.8)).isEqualTo(1);
        assertThat(Probe.bearingIndex(90)).isEqualTo(12);
        assertThat(Probe.bearingIndex(359)).isEqualTo(0);
        assertThat(Geo.bearingDeg(LAT, LON, near[0], near[1])).isCloseTo(0, within(0.01));
        assertThat(Geo.bearingDeg(LAT, LON, LAT, LON + 1)).isCloseTo(90, within(0.5));
    }
}
