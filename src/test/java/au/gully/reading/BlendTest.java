package au.gully.reading;

import au.gully.reach.ReachRule;
import au.gully.reach.Terrain;
import au.gully.upstreams.Conditions;
import au.gully.upstreams.Forecast;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BlendTest {

    @Test
    void temperatureIsBroughtToThePointsHeightByTheLapseRate() {
        // Mount Lofty at 700 m says 10 °C; the point is at 300 m: 400 m lower, 2.6 warmer.
        assertThat(Blend.toHeight(10.0, 700.0, 300.0, Blend.LAPSE_C_PER_KM)).isCloseTo(12.6, within(0.001));
        // West Terrace at 30 m says 20 °C at the same point: 270 m higher, 1.75 cooler.
        assertThat(Blend.toHeight(20.0, 30.0, 300.0, Blend.LAPSE_C_PER_KM)).isCloseTo(18.245, within(0.001));
        // Unknown heights leave the value alone; a null value stays null.
        assertThat(Blend.toHeight(20.0, null, 300.0, Blend.LAPSE_C_PER_KM)).isEqualTo(20.0);
        assertThat(Blend.toHeight(null, 30.0, 300.0, Blend.LAPSE_C_PER_KM)).isNull();
    }

    @Test
    void weightsFallWithTheSquareOfTheCostAndNeverBlowUp() {
        assertThat(Blend.weight(10)).isCloseTo(0.01, within(1e-9));
        assertThat(Blend.weight(20)).isCloseTo(0.0025, within(1e-9));
        assertThat(Blend.weight(0)).as("a station at the point weighs as one half a kilometre away").isEqualTo(Blend.weight(0.5));
        List<Blend.Part> parts = List.of(new Blend.Part("a", Blend.weight(10), 20.0), new Blend.Part("b", Blend.weight(20), 10.0));
        // Four to one: (4 × 20 + 1 × 10) / 5 = 18.
        assertThat(Blend.mean(parts)).isCloseTo(18.0, within(1e-9));
        assertThat(Blend.shares(parts)).containsExactly(0.8, 0.2);
        assertThat(Blend.mean(List.of())).isNull();
    }

    @Test
    void bearingsBlendTheShortWayRound() {
        List<Blend.Part> parts = List.of(new Blend.Part("a", 1, 350.0), new Blend.Part("b", 1, 10.0));
        assertThat(Blend.meanBearing(parts)).isEqualTo(0);
        assertThat(Blend.meanBearing(List.of(new Blend.Part("a", 1, 90.0), new Blend.Part("b", 3, 90.0)))).isEqualTo(90);
        assertThat(Blend.meanBearing(List.of(new Blend.Part("a", 1, 0.0), new Blend.Part("b", 1, 180.0)))).as("opposite winds cancel").isNull();
    }

    @Test
    void theCostToAPointIsTheReachsOwnArithmetic() {
        // Flat terrain: the cost is the distance. A 300 m rise at 5 km on bearing 0 costs 30 km at 10 km per 100 m.
        double[] e = new double[Terrain.BEARINGS * Terrain.STEPS];
        java.util.Arrays.fill(e, 30);
        e[4] = 330; // bearing 0, step 5
        Terrain t = new Terrain("x", -34.9, 138.6, 30, e, Instant.now(), 1);
        ReachRule.Rule r = ReachRule.Rule.of(40, 10);
        assertThat(Readings.cost(t, 12, 12.3, r)).isEqualTo(12.3);
        assertThat(Readings.cost(t, 0, 3, r)).as("before the rise").isEqualTo(3.0);
        assertThat(Readings.cost(t, 0, 8, r)).as("a one-kilometre rise is no barrier (W-16): the distance alone").isEqualTo(8.0);
        // A rise that holds for three kilometres is a barrier, and costs its climb.
        double[] wall = new double[Terrain.BEARINGS * Terrain.STEPS];
        java.util.Arrays.fill(wall, 30);
        for (int s = 5; s <= 7; s++) {
            wall[s - 1] = 330;
        }
        Terrain w = new Terrain("x", -34.9, 138.6, 30, wall, Instant.now(), 1);
        assertThat(Readings.cost(w, 0, 4, r)).as("before the wall").isEqualTo(4.0);
        assertThat(Readings.cost(w, 0, 8, r)).as("past the wall: 8 + 30").isEqualTo(38.0);
        // The same wall below the station costs half, the descent share.
        double[] pit = new double[Terrain.BEARINGS * Terrain.STEPS];
        java.util.Arrays.fill(pit, 330);
        for (int s = 5; s <= 7; s++) {
            pit[s - 1] = 30;
        }
        assertThat(Readings.cost(new Terrain("x", -34.9, 138.6, 330, pit, Instant.now(), 1), 0, 8, r)).as("8 + 15").isEqualTo(23.0);
    }

    @Test
    void thePointsCurrentCarriesTheRainSinceNineAndTheDaysTotal() {
        ZoneId zone = ZoneId.of("Australia/Adelaide");
        // 2026-09-21T15:00 Adelaide; the series from 9 am yesterday, 1 mm every hour.
        Instant now = java.time.LocalDateTime.parse("2026-09-21T15:00").atZone(zone).toInstant();
        Instant start = java.time.LocalDateTime.parse("2026-09-20T09:00").atZone(zone).toInstant();
        List<Conditions> hourly = new ArrayList<>();
        for (int h = 0; h < 31; h++) {
            hourly.add(Conditions.at(start.plusSeconds(h * 3600L)).temperature(10.0 + h % 12).precipitation(1.0).build());
        }
        Conditions current = Conditions.at(now).temperature(21.5).humidity(40).wind(15.0).windDirection(200).gust(25.0).precipitation(0.0).build();
        Forecast f = new Forecast("open-meteo", "best_match", "", now, 120.0, zone.getId(), current, hourly, List.of());
        var o = PointCurrent.of("p-1", f, zone);
        assertThat(o.temperatureC()).isEqualTo(21.5);
        assertThat(o.rainSince9amMm()).as("the six hours from 9 am to 3 pm today").isEqualTo(6.0);
        assertThat(o.rain24hMm()).as("yesterday's 24 hours to 9 am").isEqualTo(24.0);
        assertThat(o.maxTemperatureC()).as("the warmest hour since 9 am, or the current").isEqualTo(21.5);
        assertThat(o.windDirectionDeg()).isEqualTo(200);
        // A series that starts after 9 am yesterday cannot give the day's total.
        Forecast late = new Forecast("open-meteo", "best_match", "", now, 120.0, zone.getId(), current, hourly.subList(3, hourly.size()), List.of());
        assertThat(PointCurrent.of("p-1", late, zone).rain24hMm()).isNull();
        assertThat(PointCurrent.of("p-1", late, zone).rainSince9amMm()).isEqualTo(6.0);
    }

    @Test
    void aPointsIdIsItsPlace() {
        assertThat(Points.idOf(-34.98, 138.66)).startsWith("p-").isEqualTo(Points.idOf(-34.98004, 138.66004));
        assertThat(Points.idOf(-34.98, 138.66)).isNotEqualTo(Points.idOf(-34.99, 138.66));
    }
}
