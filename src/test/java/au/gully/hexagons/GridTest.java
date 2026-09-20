package au.gully.hexagons;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

/**
 * The cell generator: every point has exactly one cell, the cells are the size they say, the ring
 * is the six around, and the tessellation has no gaps.
 */
class GridTest {

    private static final Grid GRID = Grid.DEFAULT;

    @Test
    void theProjectionRoundTripsAcrossTheCountry() {
        double[][] points = {{-34.93, 138.60}, {-12.46, 130.84}, {-42.88, 147.33}, {-31.95, 115.86}, {-27.47, 153.03}, {-23.70, 133.88}};
        for (double[] p : points) {
            double[] xy = Albers.forward(p[0], p[1]);
            double[] back = Albers.inverse(xy[0], xy[1]);
            assertThat(back[0]).isCloseTo(p[0], offset(1e-6));
            assertThat(back[1]).isCloseTo(p[1], offset(1e-6));
        }
        // Adelaide sits east of the central meridian and south of the equator: positive x, negative y.
        double[] adelaide = Albers.forward(-34.93, 138.60);
        assertThat(adelaide[0]).isPositive();
        assertThat(adelaide[1]).isNegative();
    }

    @Test
    void aPointIsInTheCellWhoseCentreIsNearest() {
        Cell c = GRID.cellOf(-35.02, 138.73);
        assertThat(GRID.parse(c.id())).isEqualTo(c);
        double d = Geo.haversineMetres(-35.02, 138.73, c.lat(), c.lon());
        // Never further than the circumradius, the width / √3.
        assertThat(d).isLessThan(Grid.CELL_KM * 1000 / Math.sqrt(3) + 5);
        for (Cell n : GRID.ring(c)) {
            assertThat(Geo.haversineMetres(-35.02, 138.73, n.lat(), n.lon())).isGreaterThanOrEqualTo(d - 1);
        }
    }

    @Test
    void theHexagonIsTheWidthAcrossTheFlatsAndAnchoredOnTheGolfCourse() {
        // Hexagon 0_0 is centred on the anchor, and the anchor is in it.
        Cell origin = GRID.cell(0, 0);
        assertThat(origin.lat()).isCloseTo(Grid.ANCHOR_LAT, offset(1e-6));
        assertThat(origin.lon()).isCloseTo(Grid.ANCHOR_LON, offset(1e-6));
        assertThat(GRID.cellOf(Grid.ANCHOR_LAT, Grid.ANCHOR_LON).id()).isEqualTo("0_0");
        assertThat(GRID.spec()).contains("17.0 km").contains("-35.13133,139.26558");
        Cell c = GRID.cellOf(-34.93, 138.60);
        List<Cell> ring = GRID.ring(c);
        assertThat(ring).hasSize(6);
        for (Cell n : ring) {
            // Centre to centre across a shared flat is the width across flats.
            assertThat(Geo.haversineMetres(c.lat(), c.lon(), n.lat(), n.lon())).isCloseTo(Grid.CELL_KM * 1000, offset(200.0));
        }
        assertThat(GRID.areaKm2()).isCloseTo(250.3, offset(0.1));
    }

    @Test
    void theOutlineIsSixVerticesClosedAndHoldsTheCentre() {
        Cell c = GRID.cellOf(-34.93, 138.60);
        List<double[]> outline = GRID.outline(c);
        assertThat(outline).hasSize(7);
        assertThat(outline.getFirst()).containsExactly(outline.getLast());
        for (int i = 0; i < 6; i++) {
            assertThat(Geo.haversineMetres(c.lat(), c.lon(), outline.get(i)[0], outline.get(i)[1]))
                    .isCloseTo(Grid.CELL_KM * 1000 / Math.sqrt(3), offset(200.0));
        }
    }

    @Test
    void everyPointOnALatticeFallsInExactlyOneCellAndNeighboursShareEdges() {
        // Walk a fine lattice over a small area: the cells it lands in must all be one of a compact set,
        // and every cell touched must be a member of its own neighbours' rings (no orphans, no gaps).
        Set<String> cells = new HashSet<>();
        for (double lat = -35.3; lat <= -34.6; lat += 0.005) {
            for (double lon = 138.2; lon <= 139.0; lon += 0.005) {
                cells.add(GRID.cellOf(lat, lon).id());
            }
        }
        assertThat(cells.size()).isBetween(5, 60);
        for (String id : cells) {
            Cell c = GRID.parse(id);
            for (Cell n : GRID.ring(c)) {
                assertThat(GRID.ring(n).stream().map(Cell::id)).contains(id);
            }
        }
    }

    @Test
    void theLatticeInsideACellIsInsideIt() {
        Cell c = GRID.cellOf(-34.93, 138.60);
        List<double[]> lattice = GRID.lattice(c, 24);
        assertThat(lattice.size()).isBetween(380, 480);
        for (double[] p : lattice) {
            assertThat(GRID.cellOf(p[0], p[1]).id()).isEqualTo(c.id());
        }
    }

    @Test
    void withinABoxListsTheCellsAndRefusesAContinent() {
        List<Cell> some = GRID.within(-35.3, 138.2, -34.6, 139.0, 3000);
        assertThat(some.size()).isBetween(5, 70);
        assertThat(some.stream().map(Cell::id).distinct().count()).isEqualTo(some.size());
        assertThat(GRID.within(-44, 112, -10, 154, 3000)).isEmpty();
    }

    @Test
    void aHexagonNeedsAWidth() {
        assertThatThrownBy(() -> new Grid(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFinerGridForRiversNestsIndependently() {
        Grid rivers = new Grid(5);
        Cell r = rivers.cellOf(-34.93, 138.60);
        assertThat(rivers.areaKm2()).isCloseTo(21.65, offset(0.01));
        assertThat(Geo.haversineMetres(-34.93, 138.60, r.lat(), r.lon())).isLessThan(5_000 / Math.sqrt(3) + 5);
    }

    /**
     * The reach: a station counts for the hexagon it is in and for any neighbour whose edge is within
     * the reach - a quarter of the width, 4.25 km at 17 km, until the console sets another. Inside,
     * one; near an edge, two; near a corner, three.
     */
    @Test
    void aStationNearAnEdgeCountsForTheHexagonBeyondIt() {
        assertThat(Grid.DEFAULT_STATION_REACH_KM).isEqualTo(4.25);
        Cell centre = GRID.cell(0, 0);
        // The centre itself: inside, distance zero, one hexagon.
        assertThat(GRID.distanceKm(centre, centre.lat(), centre.lon())).isEqualTo(0);
        assertThat(GRID.cellsReaching(centre.lat(), centre.lon(), Grid.DEFAULT_STATION_REACH_KM)).extracting(Cell::id).containsExactly("0_0");
        // A point 1 km past the flat edge to the north (flat-topped: the flats are 8.5 km above and below the
        // centre, the corners 9.8 km east and west): in the northern neighbour, 1 km from 0_0, so it counts for both.
        double[] east = Albers.inverse(Albers.forward(centre.lat(), centre.lon())[0], Albers.forward(centre.lat(), centre.lon())[1] + 9500);
        assertThat(GRID.cellOf(east[0], east[1]).id()).isNotEqualTo("0_0");
        assertThat(GRID.distanceKm(centre, east[0], east[1])).isCloseTo(1.0, offset(0.05));
        List<Cell> reached = GRID.cellsReaching(east[0], east[1], Grid.DEFAULT_STATION_REACH_KM);
        assertThat(reached).hasSize(2);
        assertThat(reached).extracting(Cell::id).contains("0_0");
        // A point 6 km past the edge is beyond the reach: its own hexagon only.
        double[] far = Albers.inverse(Albers.forward(centre.lat(), centre.lon())[0], Albers.forward(centre.lat(), centre.lon())[1] + 14500);
        assertThat(GRID.distanceKm(centre, far[0], far[1])).isCloseTo(6.0, offset(0.05));
        assertThat(GRID.cellsReaching(far[0], far[1], Grid.DEFAULT_STATION_REACH_KM)).hasSize(1);
        // A point 1 km beyond a corner (the corners are 9.8 km out, at 60° steps) reaches three.
        double a = Math.toRadians(60);
        double[] c0 = Albers.forward(centre.lat(), centre.lon());
        double[] corner = Albers.inverse(c0[0] + 10815 * Math.cos(a), c0[1] + 10815 * Math.sin(a));
        assertThat(GRID.cellsReaching(corner[0], corner[1], Grid.DEFAULT_STATION_REACH_KM)).hasSize(3);
    }

    /**
     * The reach the console sets walks outward as far as it goes (W-18): from a hexagon's centre the six
     * neighbours' edges are 8.5 km away and the second ring's nearest edges 19.6 km (the nearest second-ring
     * centre is √3 widths out, less a circumradius), so 8 km reaches nothing, 9 km the ring of six, and
     * 20 km some of the twelve beyond. Every hexagon reached is within the reach and none is missed.
     */
    @Test
    void aWiderReachWalksPastTheFirstRing() {
        Cell centre = GRID.cell(0, 0);
        assertThat(GRID.cellsReaching(centre.lat(), centre.lon(), 8)).hasSize(1);
        assertThat(GRID.cellsReaching(centre.lat(), centre.lon(), 9)).hasSize(7);
        List<Cell> wide = GRID.cellsReaching(centre.lat(), centre.lon(), 20);
        assertThat(wide.size()).isGreaterThan(7).isLessThanOrEqualTo(19);
        assertThat(wide.stream().map(Cell::id).distinct().count()).isEqualTo(wide.size());
        for (Cell c : wide) {
            assertThat(GRID.distanceKm(c, centre.lat(), centre.lon())).isLessThanOrEqualTo(20);
        }
        for (Cell c : GRID.ring(centre, 2)) {
            boolean in = GRID.distanceKm(c, centre.lat(), centre.lon()) <= 20;
            assertThat(wide.stream().anyMatch(w -> w.id().equals(c.id()))).as(c.id()).isEqualTo(in);
        }
        // From a corner, the second ring's nearest corner is one edge's length away - 9.8 km - and nothing more.
        double[] c0 = Albers.forward(centre.lat(), centre.lon());
        double[] corner = Albers.inverse(c0[0] + 9815 * Math.cos(Math.toRadians(60)), c0[1] + 9815 * Math.sin(Math.toRadians(60)));
        assertThat(GRID.cellsReaching(corner[0], corner[1], 9.5)).hasSize(3);
        assertThat(GRID.cellsReaching(corner[0], corner[1], 10.5).size()).isGreaterThan(3);
        // The reach the console sets is held to its range and its step.
        assertThat(Reach.clamp(-1)).isEqualTo(0);
        assertThat(Reach.clamp(4.3)).isEqualTo(4.25);
        assertThat(Reach.clamp(99)).isEqualTo(Reach.MAX_KM);
    }
}
