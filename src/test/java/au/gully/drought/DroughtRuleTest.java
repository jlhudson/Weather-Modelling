package au.gully.drought;

import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.hexagons.Cell;
import au.gully.hexagons.DroughtState;
import au.gully.hexagons.Grid;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * The rule that picks a drought's stations (W-22): the stations counting for the hexagon first, else
 * the nearest ring with any, ranked by ground distance plus what the height between costs; the
 * maximum brought to the hexagon's elevation by the lapse rate; and a hexagon with no drought of its
 * own made from the spun-up hexagons around it, nearest by the same distance weighing most.
 */
class DroughtRuleTest {

    private static final Grid GRID = Grid.DEFAULT;

    /**
     * A register where a station counts for the hexagon it sits in and nothing more.
     */
    private static StationRegistry registry(List<Station> stations) {
        return new StationRegistry(null, null, null) {
            @Override
            public List<Station> inCells(Grid grid, java.util.Collection<Cell> cells) {
                Set<String> ids = cells.stream().map(Cell::id).collect(Collectors.toSet());
                return stations.stream().filter(s -> ids.contains(grid.cellOf(s.lat(), s.lon()).id())).toList();
            }
        };
    }

    private static Station station(String id, String name, double lat, double lon, double heightM) {
        return new Station(id, null, name, lat, lon, heightM, "Australia/Adelaide", "SA_PW001", "sa");
    }

    private static Drought drought(List<Station> stations, DroughtRule rule) {
        return new Drought(GRID, registry(stations), null, null, null, rule);
    }

    @Test
    void heightChoosesTheStationWhenNoneCountsForTheHexagon() {
        // A hexagon at Lobethal in the Hills (about 500 m), no station in it. In the ring around it, Mount Lofty
        // (700 m) and a plains station (30 m) sit at ground distances of 11 and 8 km, so by the ground the plains
        // station wins; at ten kilometres a hundred metres the Hills station does, being 200 m off rather than 470.
        Cell hills = GRID.cellOf(-34.905, 138.875);
        Cell east = GRID.ring(hills).get(0), west = GRID.ring(hills).get(3);
        Station lofty = station("023842", "MOUNT LOFTY", east.lat(), east.lon(), 700);
        Station plains = station("023090", "KENT TOWN", west.lat(), west.lon(), 30);
        // The centres of the neighbours are one width away; nudge the plains station closer to the hexagon's centre.
        plains = station("023090", "KENT TOWN", (west.lat() + hills.lat()) / 2 + (west.lat() - hills.lat()) * 0.05, (west.lon() + hills.lon()) / 2 + (west.lon() - hills.lon()) * 0.05, 30);
        DroughtRule byGround = DroughtRule.fixed(3, 0);
        assertThat(byGround.kmPer100m()).isZero();
        List<Drought.Fed> flat = drought(List.of(lofty, plains), byGround).feed(hills, 500.0);
        assertThat(flat).hasSize(1);
        assertThat(flat.getFirst().station().id()).as("by the ground alone the nearer plains station feeds the Hills").isEqualTo("023090");
        assertThat(flat.getFirst().ring()).isEqualTo(1);
        assertThat(flat.getFirst().maxOffsetC()).as("470 m below the hexagon: its maximum is brought up by the lapse rate, cooler").isCloseTo(-3.1, offset(0.05));

        DroughtRule byHeight = DroughtRule.fixed(3, 10);
        List<Drought.Fed> ranked = drought(List.of(lofty, plains), byHeight).feed(hills, 500.0);
        assertThat(ranked.getFirst().station().id()).as("at ten kilometres a hundred metres, Mount Lofty is the Hills' station").isEqualTo("023842");
        assertThat(ranked.getFirst().effectiveKm()).isCloseTo(ranked.getFirst().km() + 20, offset(0.1));
        assertThat(ranked.getFirst().maxOffsetC()).as("200 m above: its maximum is brought down, warmer").isCloseTo(1.3, offset(0.05));

        // A station counting for the hexagon itself is ring 0 and beats every ring, whatever its height.
        Station inside = station("023000", "IN IT", hills.lat(), hills.lon(), 100);
        List<Drought.Fed> own = drought(List.of(lofty, plains, inside), byHeight).feed(hills, 500.0);
        assertThat(own).extracting(f -> f.station().id()).containsExactly("023000");
        assertThat(own.getFirst().ring()).isZero();

        // No ring within the rule has a station: nothing feeds it, and the archive alone will.
        assertThat(drought(List.of(), byHeight).feed(hills, 500.0)).isEmpty();
        DroughtRule none = DroughtRule.fixed(0, 10);
        assertThat(drought(List.of(lofty), none).feed(hills, 500.0)).as("zero rings: the hexagon's own stations only").isEmpty();
    }

    @Test
    void aHexagonWithoutADroughtTakesTheSpunUpOnesAroundIt() {
        Cell here = GRID.cellOf(-34.905, 138.875);
        List<Cell> ring = GRID.ring(here);
        Cell near = ring.get(0), far = GRID.ring(here, 2).get(0);
        LocalDate day = LocalDate.of(2026, 9, 19);
        DroughtState wet = new DroughtState(20, 600, day.minusDays(365), day, 366, List.of(1.0, 2.0, 3.0), "stations");
        DroughtState dry = new DroughtState(80, 400, day.minusDays(365), day.minusDays(1), 365, List.of(0.0, 0.0, 0.0), "archive");
        DroughtState borrowed = new DroughtState(50, 500, day.minusDays(365), day, 366, List.of(0.0, 0.0, 0.0), "interpolated", List.of("x"));
        Drought.Around around = c -> c.id().equals(near.id()) ? Optional.of(new Drought.Neighbour(wet, 500.0))
                : c.id().equals(far.id()) ? Optional.of(new Drought.Neighbour(dry, 500.0))
                : c.id().equals(ring.get(3).id()) ? Optional.of(new Drought.Neighbour(borrowed, 500.0)) : Optional.empty();
        DroughtRule rule = DroughtRule.fixed(3, 10);
        Optional<DroughtState> made = drought(List.of(), rule).interpolate(here, 500.0, around);
        assertThat(made).isPresent();
        DroughtState s = made.get();
        assertThat(s.interpolated()).isTrue();
        assertThat(s.fromHexagons()).as("nearest first, and an interpolated neighbour is never a source").containsExactly(near.id(), far.id());
        // The near one, a width away, weighs four times the far one at two widths: 20 and 80 blend nearer 20.
        assertThat(s.kbdiMm()).isCloseTo(32, offset(1.0));
        assertThat(s.meanAnnualRainfallMm()).isCloseTo(560, offset(2.0));
        assertThat(s.recentRainMm()).hasSize(3);
        assertThat(s.recentRainMm().get(2)).isCloseTo(2.4, offset(0.1));
        assertThat(s.computedFor()).as("the oldest of its sources").isEqualTo(day.minusDays(1));
        assertThat(s.days()).isEqualTo(365);
        // Height keeps its say: a source 300 m off is thirty kilometres further, and the other wins outright.
        Drought.Around uphill = c -> c.id().equals(near.id()) ? Optional.of(new Drought.Neighbour(wet, 800.0))
                : c.id().equals(far.id()) ? Optional.of(new Drought.Neighbour(dry, 500.0)) : Optional.empty();
        assertThat(drought(List.of(), rule).interpolate(here, 500.0, uphill).get().kbdiMm()).isCloseTo(59.4, offset(1.0));
        assertThat(drought(List.of(), rule).interpolate(here, 500.0, c -> Optional.empty())).as("nothing around: nothing made").isEmpty();
    }
}
