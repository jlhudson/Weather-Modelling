package au.gully.hexagons;

import au.gully.bureau.Observation;
import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.bureau.States;
import au.gully.science.Conditions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.assertj.core.api.Assertions.within;

/**
 * "Now" from the neighbours (W-13): the share rule over one ring and two, the inverse-distance
 * weights, the lapse rates that bring a station's temperature and dew point to the hexagon's
 * elevation, and the humidity that follows from them rather than being averaged.
 */
class InterpolationTest {

    private static final Grid GRID = Grid.DEFAULT;
    private static final Instant NOW = Instant.parse("2026-09-19T03:00:00Z");

    /**
     * A register with the given stations, each reporting the same moment.
     */
    private static StationRegistry registry(List<Station> stations, List<Observation> observations) {
        return new StationRegistry(null, null) {
            @Override
            public List<Station> inCells(Grid grid, java.util.Collection<Cell> cells) {
                // As the register does: a station counts for the hexagon it is in and any it reaches.
                java.util.Set<String> ids = cells.stream().map(Cell::id).collect(java.util.stream.Collectors.toSet());
                return stations.stream().filter(s -> grid.cellsReaching(s.lat(), s.lon(), Grid.DEFAULT_STATION_REACH_KM).stream().anyMatch(c -> ids.contains(c.id()))).toList();
            }

            @Override
            public Optional<Observation> latest(String id) {
                return observations.stream().filter(o -> o.stationId().equals(id)).findFirst();
            }
        };
    }

    private static Station station(String id, Cell in, Double heightM) {
        return new Station(id, null, "STATION " + id, in.lat(), in.lon(), heightM, "Australia/Adelaide", "SA_PW001", "sa");
    }

    private static Observation reading(String id, Double t, Integer rh, Double wind, Integer dir) {
        return new Observation(id, NOW.minusSeconds(600), t, null, null, rh, wind, dir, null, null, 1015.0, 0.0, null, null, null, null, null, null, null);
    }

    @Test
    void twoOfTheSixNeighboursAreEnoughAndTheLapseRatesBringTheValuesToTheHexagonsHeight() {
        Cell centre = GRID.cell(0, 0);
        List<Cell> ring = GRID.ring(centre);
        // Two stations on the plains, 20 m and 40 m up, both 25 °C at 40 % with a westerly; the hexagon is a ridge at 620 m.
        List<Station> stations = List.of(station("A", ring.get(0), 20.0), station("B", ring.get(3), 40.0));
        List<Observation> obs = List.of(reading("A", 25.0, 40, 20.0, 270), reading("B", 25.0, 40, 20.0, 270));
        Interpolation.Result r = Interpolation.at(GRID, registry(stations, obs), centre, 620.0, NOW).orElseThrow();
        assertThat(r.ring()).isEqualTo(1);
        assertThat(r.stations()).hasSize(2);
        assertThat(r.elevationApplied()).isTrue();
        Conditions c = r.conditions();
        // 600 m up at -6.5 °C/km is 3.9 °C cooler.
        assertThat(c.temperatureC()).isCloseTo(25.0 - 6.5 * 0.59, offset(0.15));
        // The dew point falls by only 2 °C/km, so the air is wetter up here than the plains' 40 %.
        assertThat(c.humidityPct()).isGreaterThan(44).isLessThan(55);
        assertThat(c.windSpeedKmh()).isEqualTo(20.0);
        assertThat(c.windDirectionDeg()).isEqualTo(270);
        assertThat(c.apparentTemperatureC()).isNotNull();
        assertThat(c.at()).isEqualTo(NOW.minusSeconds(600));
        // The two stations weigh by the inverse square of their distance, and the weights add to one.
        assertThat(r.stations().stream().mapToDouble(Interpolation.Used::weight).sum()).isCloseTo(1.0, within(0.01));
    }

    @Test
    void oneNeighbourIsNotEnoughButSixOfEighteenAre() {
        Cell centre = GRID.cell(0, 0);
        List<Cell> ring1 = GRID.ring(centre);
        List<Cell> ring2 = GRID.ring(centre, 2);
        assertThat(ring2).hasSize(12);
        Station only = station("A", ring1.get(0), 100.0);
        assertThat(Interpolation.at(GRID, registry(List.of(only), List.of(reading("A", 20.0, 50, 10.0, 90))), centre, 100.0, NOW)).isEmpty();

        // Five more in the second ring: six of eighteen, which is the share.
        List<Station> six = new java.util.ArrayList<>(List.of(only));
        List<Observation> obs = new java.util.ArrayList<>(List.of(reading("A", 20.0, 50, 10.0, 90)));
        for (int i = 0; i < 5; i++) {
            six.add(station("R" + i, ring2.get(i * 2), 100.0));
            obs.add(reading("R" + i, 22.0, 50, 10.0, 90));
        }
        Interpolation.Result r = Interpolation.at(GRID, registry(six, obs), centre, 100.0, NOW).orElseThrow();
        assertThat(r.ring()).isEqualTo(2);
        assertThat(r.stations()).hasSize(6);
        // The near station, one ring in, weighs more than any of the five two rings out.
        assertThat(r.stations().getFirst().weight()).isGreaterThan(r.stations().get(1).weight());
        assertThat(r.conditions().temperatureC()).isBetween(20.0, 22.0);
    }

    @Test
    void withoutTheHexagonsElevationTheValuesAreWeightedAsTheyAre() {
        Cell centre = GRID.cell(0, 0);
        List<Cell> ring = GRID.ring(centre);
        List<Station> stations = List.of(station("A", ring.get(0), 20.0), station("B", ring.get(3), 40.0));
        List<Observation> obs = List.of(reading("A", 25.0, 40, 20.0, 270), reading("B", 25.0, 40, 20.0, 270));
        Interpolation.Result r = Interpolation.at(GRID, registry(stations, obs), centre, null, NOW).orElseThrow();
        assertThat(r.elevationApplied()).isFalse();
        assertThat(r.conditions().temperatureC()).isEqualTo(25.0);
        assertThat(r.conditions().humidityPct()).isEqualTo(40);
    }

    @Test
    void theMagnusPairRoundTripsAndTheStatesAreFound() {
        double td = Interpolation.dewPoint(25.0, 40.0);
        assertThat(td).isCloseTo(10.5, offset(0.3));
        assertThat(Interpolation.humidity(25.0, td)).isCloseTo(40.0, offset(0.5));
        assertThat(States.covering(-35.13, 139.27)).containsExactly("sa");
        assertThat(States.covering(-34.93, 138.60)).containsExactly("sa");
        assertThat(States.covering(-36.1, 141.0)).as("the SA/Vic border: both").contains("sa", "vic");
        assertThat(States.covering(-42.88, 147.33)).contains("tas");
        assertThat(States.covering(-31.95, 115.86)).containsExactly("wa");
        assertThat(States.covering(-12.46, 130.84)).containsExactly("nt");
        assertThat(States.covering(-27.47, 153.03)).contains("qld");
        assertThat(States.covering(-33.87, 151.21)).contains("nsw").doesNotContain("sa");
    }

    @Test
    void aStationJustOverTheEdgeIsBlendedAsOneOfTheHexagonsOwn() {
        Cell centre = GRID.cell(0, 0);
        double[] c0 = Albers.forward(centre.lat(), centre.lon());
        // One station inside, one a kilometre over the northern edge: both the hexagon's, so a blend at ring 0.
        double[] over = Albers.inverse(c0[0], c0[1] + 9500);
        List<Station> stations = List.of(
                new Station("IN", null, "INSIDE", centre.lat(), centre.lon(), 50.0, "Australia/Adelaide", "SA_PW001", "sa"),
                new Station("OVER", null, "OVER THE EDGE", over[0], over[1], 50.0, "Australia/Adelaide", "SA_PW001", "sa"));
        List<Observation> obs = List.of(reading("IN", 20.0, 50, 10.0, 90), reading("OVER", 24.0, 50, 10.0, 90));
        Interpolation.Result r = Interpolation.inCell(GRID, registry(stations, obs), centre, 50.0, NOW).orElseThrow();
        assertThat(r.ring()).isEqualTo(0);
        assertThat(r.stations()).extracting(Interpolation.Used::id).containsExactlyInAnyOrder("IN", "OVER");
        // The one at the centre is nearer, so it weighs more: the blend sits below the midpoint of 22.
        assertThat(r.conditions().temperatureC()).isBetween(20.0, 22.0);
    }
}
