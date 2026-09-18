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
        // Never further than the circumradius, 15 km / √3.
        assertThat(d).isLessThan(15_000 / Math.sqrt(3) + 5);
        for (Cell n : GRID.ring(c)) {
            assertThat(Geo.haversineMetres(-35.02, 138.73, n.lat(), n.lon())).isGreaterThanOrEqualTo(d - 1);
        }
    }

    @Test
    void theCellIsFifteenKilometresAcrossTheFlats() {
        Cell c = GRID.cellOf(-34.93, 138.60);
        List<Cell> ring = GRID.ring(c);
        assertThat(ring).hasSize(6);
        for (Cell n : ring) {
            // Centre to centre across a shared flat is the width across flats.
            assertThat(Geo.haversineMetres(c.lat(), c.lon(), n.lat(), n.lon())).isCloseTo(15_000, offset(150.0));
        }
        assertThat(GRID.areaKm2()).isCloseTo(194.9, offset(0.1));
    }

    @Test
    void theOutlineIsSixVerticesClosedAndHoldsTheCentre() {
        Cell c = GRID.cellOf(-34.93, 138.60);
        List<double[]> outline = GRID.outline(c);
        assertThat(outline).hasSize(7);
        assertThat(outline.getFirst()).containsExactly(outline.getLast());
        for (int i = 0; i < 6; i++) {
            assertThat(Geo.haversineMetres(c.lat(), c.lon(), outline.get(i)[0], outline.get(i)[1]))
                    .isCloseTo(15_000 / Math.sqrt(3), offset(150.0));
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
        assertThat(cells.size()).isBetween(20, 60);
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
        assertThat(some.size()).isBetween(20, 70);
        assertThat(some.stream().map(Cell::id).distinct().count()).isEqualTo(some.size());
        assertThat(GRID.within(-44, 112, -10, 154, 3000)).isEmpty();
    }

    @Test
    void squaresTileTooAndHaveEightNeighbours() {
        Grid squares = new Grid(15, 4);
        Cell c = squares.cellOf(-34.93, 138.60);
        assertThat(squares.ring(c)).hasSize(8);
        assertThat(squares.outline(c)).hasSize(5);
        assertThat(squares.areaKm2()).isEqualTo(225.0);
        assertThat(squares.cellOf(c.lat(), c.lon())).isEqualTo(c);
    }

    @Test
    void onlyFourAndSixSidesTile() {
        assertThatThrownBy(() -> new Grid(15, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Grid(0, 6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFinerGridForRiversNestsIndependently() {
        Grid rivers = new Grid(5, 6);
        Cell r = rivers.cellOf(-34.93, 138.60);
        assertThat(rivers.areaKm2()).isCloseTo(21.65, offset(0.01));
        assertThat(Geo.haversineMetres(-34.93, 138.60, r.lat(), r.lon())).isLessThan(5_000 / Math.sqrt(3) + 5);
    }

    /**
     * The drought areas tile the plane (W-11): over a large block of cells, every cell is within the
     * radius of exactly one fixed centre, a centre is its own centre, the area drawn around a centre
     * is exactly the set of cells that map to it, and it has {@code 3r² + 3r + 1} cells. For the
     * seven-cell flower and the nineteen-cell one, and for the square grid's blocks.
     */
    @Test
    void theDroughtAreasTileThePlaneWithoutOverlapOrGaps() {
        for (int radius = 1; radius <= 2; radius++) {
            tiles(GRID, radius, 3 * radius * radius + 3 * radius + 1);
        }
        tiles(new Grid(15, 4), 1, 9);
    }

    private static void tiles(Grid grid, int radius, int expectedSize) {
        java.util.Map<String, java.util.List<Cell>> members = new java.util.HashMap<>();
        for (int q = -40; q <= 40; q++) {
            for (int r = -40; r <= 40; r++) {
                Cell c = grid.cell(q, r);
                Cell centre = grid.areaCentre(c, radius);
                assertThat(grid.distance(c, centre)).as("%s is within %d of its centre %s", c.id(), radius, centre.id()).isLessThanOrEqualTo(radius);
                assertThat(grid.areaCentre(centre, radius).id()).as("a centre is its own centre").isEqualTo(centre.id());
                members.computeIfAbsent(centre.id(), k -> new java.util.ArrayList<>()).add(c);
            }
        }
        // Centres well inside the block have every member inside the block, so their count is the area's size.
        int checked = 0;
        for (java.util.Map.Entry<String, java.util.List<Cell>> e : members.entrySet()) {
            Cell centre = grid.parse(e.getKey());
            if (Math.abs(centre.q()) > 30 || Math.abs(centre.r()) > 30) {
                continue;
            }
            assertThat(e.getValue()).as("members of " + e.getKey()).hasSize(expectedSize);
            List<Cell> drawn = grid.area(centre, radius);
            assertThat(drawn).hasSize(expectedSize);
            assertThat(drawn.stream().map(Cell::id).collect(java.util.stream.Collectors.toSet()))
                    .isEqualTo(e.getValue().stream().map(Cell::id).collect(java.util.stream.Collectors.toSet()));
            checked++;
        }
        assertThat(checked).isGreaterThan(50);
    }
}
