package au.gully.reach;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PatchesTest {

    private static final GeometryFactory G = new GeometryFactory();

    /**
     * A reach as a square of a half-width in degrees around a station, for the arithmetic; a real one is 48 rays.
     */
    private static Patches.Piece piece(String id, double lat, double lon, double half, boolean point) {
        Polygon square = G.createPolygon(new Coordinate[]{new Coordinate(lon - half, lat - half), new Coordinate(lon + half, lat - half),
                new Coordinate(lon + half, lat + half), new Coordinate(lon - half, lat + half), new Coordinate(lon - half, lat - half)});
        return new Patches.Piece(id, lat, lon, point, square);
    }

    private static boolean holds(Geometry patch, double lat, double lon) {
        return patch.covers(G.createPoint(new Coordinate(lon, lat)));
    }

    @Test
    void overlappingReachesTileWithoutOverlapOrGap() {
        Patches.Piece a = piece("a", -35, 138.0, 0.4, false), b = piece("b", -35, 138.5, 0.4, false);
        Map<String, Geometry> t = Patches.tile(List.of(a, b));
        // They split at the line midway, 138.25: nothing held twice, and together exactly the two reaches.
        assertThat(t.get("a").intersection(t.get("b")).getArea()).isCloseTo(0, within(1e-9));
        assertThat(t.get("a").union(t.get("b")).getArea()).isCloseTo(a.reach().union(b.reach()).getArea(), within(1e-9));
        assertThat(holds(t.get("a"), -35, 138.2)).isTrue();
        assertThat(holds(t.get("b"), -35, 138.3)).isTrue();
        assertThat(holds(t.get("a"), -35, 138.3)).isFalse();
    }

    @Test
    void aPlaceGoesToTheNearestStationWhoseReachHoldsItNotTheNearestAtAll() {
        // a is nearer to 137.8 but its reach, cut short, stops at 137.95; b reaches it, so b holds it.
        Patches.Piece a = piece("a", -35, 138.0, 0.05, false), b = piece("b", -35, 138.3, 0.6, false);
        Map<String, Geometry> t = Patches.tile(List.of(a, b));
        assertThat(holds(t.get("b"), -35, 137.8)).isTrue();
        assertThat(holds(t.get("a"), -35, 138.0)).isTrue();
        assertThat(holds(t.get("b"), -35, 138.0)).isFalse();
    }

    @Test
    void aPointOfOursTakesOnlyWhatNoBureauStationReaches() {
        // The point is nearer its own spot than the station is, but the station reaches it: the station keeps it.
        Patches.Piece station = piece("023000", -35, 138.0, 0.4, false), point = piece("p", -35, 138.35, 0.2, true);
        Map<String, Geometry> t = Patches.tile(List.of(station, point));
        assertThat(t.get("023000").getArea()).isCloseTo(station.reach().getArea(), within(1e-9));
        assertThat(holds(t.get("023000"), -35, 138.35)).isTrue();
        assertThat(holds(t.get("p"), -35, 138.5)).isTrue();
        assertThat(t.get("p").intersection(station.reach()).getArea()).isCloseTo(0, within(1e-9));
    }

    @Test
    void twoStationsOnOneSpotAreSplitByTheirIds() {
        Map<String, Geometry> t = Patches.tile(List.of(piece("b", -35, 138, 0.2, false), piece("a", -35, 138, 0.2, false)));
        assertThat(t.get("a").getArea()).isCloseTo(0.16, within(1e-9));
        assertThat(t.get("b").isEmpty()).isTrue();
        assertThat(Patches.geometry(t.get("b"))).isNull();
    }

    @Test
    void aPatchCutInTwoIsAMultiPolygon() {
        // A long thin reach from the same spot crosses the middle of a's; its station's id is the lesser, so it keeps the
        // ground they share, and a is left two halves. With the greater id it keeps nothing of a's, and a stays whole.
        Patches.Piece a = piece("a", -35, 138.0, 0.5, false);
        Polygon bar = G.createPolygon(new Coordinate[]{new Coordinate(137.4, -35.05), new Coordinate(138.6, -35.05),
                new Coordinate(138.6, -34.95), new Coordinate(137.4, -34.95), new Coordinate(137.4, -35.05)});
        Patches.Piece lesser = new Patches.Piece("0", -35, 138.0, false, bar), greater = new Patches.Piece("b", -35, 138.0, false, bar);
        assertThat(Patches.geometry(Patches.tile(List.of(a, lesser)).get("a"))).containsEntry("type", "MultiPolygon");
        assertThat(Patches.geometry(Patches.tile(List.of(a, greater)).get("a"))).containsEntry("type", "Polygon");
    }

    @Test
    void aPatchsAreaIsInSquareKilometres() {
        // A tenth of a degree square at 35° south: 11.1 km by 9.1 km.
        Patches.Piece a = piece("a", -35, 138.0, 0.05, false);
        assertThat(Patches.areaKm2(a.reach())).isCloseTo(101.2, within(0.5));
    }
}
