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
        // At 1 km the difference is 100 m: 1 + 10 = 11 fine; at 3 km 300 m: 3 + 30 = 33 fine; at 4 km 400 m: 44 > 40.
        assertThat(r.km()).containsOnly(3.0);
        assertThat(r.cuts()).containsEntry(Reach.Cut.HEIGHT, Terrain.BEARINGS);
        // A steeper hill still gets the floor.
        Reach steep = Reach.of(terrain(700, (b, s) -> 50), ReachRule.Rule.of(40, 10));
        assertThat(steep.km()).containsOnly(ReachRule.MIN_KM);
    }

    @Test
    void aGentleSlopeShortensTheReachALittleRatherThanCuttingIt() {
        // 10 m a kilometre: at 30 km the difference is 300 m, costing 30, so 30 + 30 = 60 > 40; at 20 km 20 + 20 = 40 fits.
        Reach r = Reach.of(terrain(0, (b, s) -> 10 * s), ReachRule.Rule.of(40, 10));
        assertThat(r.km()).containsOnly(20.0);
        assertThat(r.cut()[0]).isEqualTo(Reach.Cut.HEIGHT);
    }

    @Test
    void aRayEndsAtTheWaterHalfAStepShortAndTheStationIsCoastal() {
        // The sea from 4 km out on bearings 12 to 36 (east round through south to west); land elsewhere.
        Terrain t = terrain(10, (b, s) -> b >= 12 && b <= 36 && s >= 4 ? -5 : 10);
        Reach r = Reach.of(t, ReachRule.Rule.of(40, 10, 25));
        assertThat(r.km()[24]).as("the first water sample at 4 km: the edge at 3.5").isEqualTo(3.5);
        assertThat(r.cut()[24]).isEqualTo(Reach.Cut.WATER);
        assertThat(r.coastal()).isTrue();
        assertThat(r.waterKm()).isEqualTo(4);
        // Coastal: the land rays are held to the coastal limit, and say so.
        assertThat(r.km()[0]).isEqualTo(25);
        assertThat(r.cut()[0]).isEqualTo(Reach.Cut.COASTAL);
        assertThat(r.cuts()).containsEntry(Reach.Cut.WATER, 25).containsEntry(Reach.Cut.COASTAL, 23).containsEntry(Reach.Cut.DISTANCE, 0);
        // A wider coastal limit than the reach changes nothing.
        assertThat(Reach.of(t, ReachRule.Rule.of(20, 10, 50)).km()[0]).isEqualTo(20);
        assertThat(Reach.of(t, ReachRule.Rule.of(20, 10, 50)).cut()[0]).isEqualTo(Reach.Cut.DISTANCE);
        // Water only beyond ten kilometres: the ray still ends there, but the station is not coastal.
        Reach inland = Reach.of(terrain(10, (b, s) -> b == 6 && s >= 30 ? 0 : 10), ReachRule.Rule.of(40, 10, 25));
        assertThat(inland.coastal()).isFalse();
        assertThat(inland.waterKm()).isEqualTo(30);
        assertThat(inland.km()[6]).isEqualTo(29.5);
        assertThat(inland.km()[0]).isEqualTo(40);
        // A ridge crossed before the water: the ray stops at the ridge, and the water beyond is not seen.
        Reach ridge = Reach.of(terrain(10, (b, s) -> b == 0 ? (s == 5 ? 600 : s >= 8 ? -5 : 10) : 10), ReachRule.Rule.of(40, 10, 25));
        assertThat(ridge.cut()[0]).isEqualTo(Reach.Cut.HEIGHT);
        assertThat(ridge.coastal()).isFalse();
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
        assertThat(ReachRule.Rule.of(80, 10).reachKm()).isEqualTo(Terrain.MAX_KM);
        assertThat(ReachRule.Rule.of(1, 10).reachKm()).isEqualTo(ReachRule.MIN_KM);
        assertThat(ReachRule.Rule.of(40, -5).kmPer100m()).isEqualTo(0);
        assertThat(ReachRule.Rule.of(40, 99).kmPer100m()).isEqualTo(ReachRule.MAX_KM_PER_100M);
        assertThat(ReachRule.Rule.of(40.3, 10.1, 25)).as("to the quarter").isEqualTo(new ReachRule.Rule(40.25, 10, 25));
        assertThat(ReachRule.Rule.of(40, 10, 99).coastalKm()).isEqualTo(Terrain.MAX_KM);
    }

    @Test
    void theTerrainRoundTripsThroughItsBytes() {
        Terrain t = terrain(29, (b, s) -> b * 100 + s);
        Terrain back = Terrain.fromBytes(t.stationId(), t.lat(), t.lon(), t.toBytes(), t.sampledAt(), t.calls());
        assertThat(back.elevationM()).isEqualTo(29);
        assertThat(back.at(7, 3)).isEqualTo(703);
        assertThat(back.elevations()).containsExactly(t.elevations());
    }

    @Test
    void theSamplePointsAreTheStationThenEveryStepOnEveryBearing() {
        double[][] pts = Terrain.points(LAT, LON);
        assertThat(pts.length).isEqualTo(Terrain.POINTS).isEqualTo(2401);
        assertThat(pts[0]).containsExactly(LAT, LON);
        // Bearing 0, step 1: a kilometre north. Bearing 12 (90°), step 50: fifty kilometres east.
        assertThat(Geo.distanceKm(LAT, LON, pts[1][0], pts[1][1])).isCloseTo(1, within(0.001));
        assertThat(pts[1][0]).isGreaterThan(LAT);
        double[] east = pts[1 + 12 * Terrain.STEPS + 49];
        assertThat(Geo.distanceKm(LAT, LON, east[0], east[1])).isCloseTo(50, within(0.01));
        assertThat(east[1]).isGreaterThan(LON);
        assertThat(east[0]).isCloseTo(LAT, within(0.2));
    }
}
